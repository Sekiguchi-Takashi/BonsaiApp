package com.appathy.bonsai.rag

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OneDrive（Microsoft Graph）から .txt / .md を差分同期する。
 *
 * 外部ライブラリはゼロ。MSAL は使わず device code flow を素の HTTP で実装している。
 * スマホ単体・無料で完結する構成。
 *
 * 認証:  device code flow（ブラウザでコードを入力するだけ。リダイレクトURI不要）
 * 差分:  Graph の delta API。deltaLink を保存して次回は差分だけ取得する
 */
class OneDrive(private val ctx: Context, private val db: RagDb) {

    companion object {
        private const val TAG = "OneDrive"

        private const val AUTH = "https://login.microsoftonline.com/common/oauth2/v2.0"
        private const val GRAPH = "https://graph.microsoft.com/v1.0"
        private const val SCOPE = "Files.Read offline_access"

        const val KEY_DELTA = "delta_link"
        private const val PREFS = "onedrive"
        private const val K_CLIENT = "client_id"
        private const val K_FOLDER = "folder"
        private const val K_ACCESS = "access_token"
        private const val K_REFRESH = "refresh_token"
        private const val K_EXPIRES = "expires_at"

        private val TEXT_EXT = setOf("txt", "md", "markdown", "text")
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    }

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var clientId: String
        get() = prefs.getString(K_CLIENT, "") ?: ""
        set(v) = prefs.edit().putString(K_CLIENT, v.trim()).apply()

    var folder: String
        get() = prefs.getString(K_FOLDER, "/RAG") ?: "/RAG"
        set(v) = prefs.edit().putString(K_FOLDER, v.trim().ifEmpty { "/RAG" }).apply()

    val isSignedIn: Boolean get() = !prefs.getString(K_REFRESH, null).isNullOrEmpty()

    fun signOut() {
        prefs.edit().remove(K_ACCESS).remove(K_REFRESH).remove(K_EXPIRES).apply()
        db.setMeta(KEY_DELTA, null)
    }

    // ------------------------------------------------------------- 認証

    data class DeviceCode(
        val userCode: String,
        val verificationUri: String,
        val deviceCode: String,
        val interval: Int,
        val expiresIn: Int
    )

    /** 手順1: ユーザーに見せるコードを取得する */
    fun startDeviceCode(): DeviceCode {
        require(clientId.isNotEmpty()) { "client_id が未設定です" }
        val body = "client_id=${enc(clientId)}&scope=${enc(SCOPE)}"
        val json = postForm("$AUTH/devicecode", body)
        return DeviceCode(
            json.getString("user_code"),
            json.getString("verification_uri"),
            json.getString("device_code"),
            json.optInt("interval", 5),
            json.optInt("expires_in", 900)
        )
    }

    /**
     * 手順2: ユーザーがブラウザで承認するまでポーリングする。
     * 承認されたら true。タイムアウト/拒否なら例外。
     */
    fun pollForToken(dc: DeviceCode, onWait: (Int) -> Unit): Boolean {
        val deadline = System.currentTimeMillis() + dc.expiresIn * 1000L
        var waited = 0
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(dc.interval * 1000L)
            waited += dc.interval
            onWait(waited)

            val body = "grant_type=urn:ietf:params:oauth:grant-type:device_code" +
                    "&client_id=${enc(clientId)}&device_code=${enc(dc.deviceCode)}"
            val (code, text) = postFormRaw("$AUTH/token", body)
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }

            if (code in 200..299) {
                saveTokens(json)
                return true
            }
            when (json.optString("error")) {
                "authorization_pending" -> {}                  // まだ承認されていない
                "slow_down" -> Thread.sleep(dc.interval * 1000L)
                "expired_token" -> throw IllegalStateException("コードの有効期限切れ")
                "authorization_declined" -> throw IllegalStateException("承認が拒否されました")
                else -> throw IllegalStateException(
                    json.optString("error_description").ifEmpty { "認証失敗 ($code)" })
            }
        }
        throw IllegalStateException("タイムアウト")
    }

    private fun saveTokens(json: JSONObject) {
        val expires = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000L - 60_000
        prefs.edit()
            .putString(K_ACCESS, json.getString("access_token"))
            .putString(K_REFRESH, json.optString("refresh_token",
                prefs.getString(K_REFRESH, "") ?: ""))
            .putLong(K_EXPIRES, expires)
            .apply()
    }

    private fun accessToken(): String {
        val now = System.currentTimeMillis()
        val exp = prefs.getLong(K_EXPIRES, 0)
        val access = prefs.getString(K_ACCESS, null)
        if (access != null && now < exp) return access

        val refresh = prefs.getString(K_REFRESH, null)
            ?: throw IllegalStateException("サインインしていません")
        val body = "grant_type=refresh_token&client_id=${enc(clientId)}" +
                "&refresh_token=${enc(refresh)}&scope=${enc(SCOPE)}"
        val json = postForm("$AUTH/token", body)
        saveTokens(json)
        return json.getString("access_token")
    }

    // ------------------------------------------------------------- 同期

    data class SyncResult(
        val added: Int, val updated: Int, val deleted: Int,
        val skipped: Int, val fullSync: Boolean
    )

    /**
     * 差分同期。初回はフォルダ全体、2回目以降は deltaLink で差分だけ取得する。
     */
    fun sync(onProgress: (String) -> Unit): SyncResult {
        val token = accessToken()
        var added = 0; var updated = 0; var deleted = 0; var skipped = 0

        val saved = db.getMeta(KEY_DELTA)
        val fullSync = saved == null
        var url = saved ?: run {
            val p = folder.trim('/')
            if (p.isEmpty()) "$GRAPH/me/drive/root/delta"
            else "$GRAPH/me/drive/root:/$p:/delta"
        }

        onProgress(if (fullSync) "初回同期（全件取得）" else "差分を確認中")

        var pages = 0
        while (true) {
            val json = getJson(url, token)
            val items = json.optJSONArray("value") ?: break
            pages++

            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val itemId = it.optString("id")
                val name = it.optString("name")

                if (it.has("deleted")) {
                    db.deleteDoc(itemId); deleted++
                    continue
                }
                if (it.has("folder")) continue

                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in TEXT_EXT) { skipped++; continue }
                if (it.optLong("size", 0) > MAX_FILE_BYTES) { skipped++; continue }

                val path = it.optJSONObject("parentReference")
                    ?.optString("path", "") ?: ""
                val mtime = it.optString("lastModifiedDateTime")

                onProgress("取得中: $name")
                val text = try {
                    downloadText(itemId, token)
                } catch (e: Exception) {
                    Log.e(TAG, "download failed: $name", e); skipped++; continue
                }

                val existed = db.getMeta("doc:$itemId") != null
                db.upsertDoc(itemId, path, name, mtime, text)
                db.setMeta("doc:$itemId", mtime)
                if (existed) updated++ else added++
            }

            val next = json.optString("@odata.nextLink", "")
            if (next.isNotEmpty()) { url = next; continue }

            val delta = json.optString("@odata.deltaLink", "")
            if (delta.isNotEmpty()) db.setMeta(KEY_DELTA, delta)
            break
        }

        Log.i(TAG, "sync done pages=$pages +$added ~$updated -$deleted skip=$skipped")
        return SyncResult(added, updated, deleted, skipped, fullSync)
    }

    private fun downloadText(itemId: String, token: String): String {
        val conn = (URL("$GRAPH/me/drive/items/$itemId/content").openConnection()
                as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        conn.inputStream.use { ins ->
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_FILE_BYTES) break
                bos.write(buf, 0, n)
            }
            return stripBom(String(bos.toByteArray(), Charsets.UTF_8))
        }
    }

    private fun stripBom(s: String) = if (s.startsWith("\uFEFF")) s.substring(1) else s

    // -------------------------------------------------------------- HTTP

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun postForm(url: String, body: String): JSONObject {
        val (code, text) = postFormRaw(url, body)
        val json = JSONObject(text)
        if (code !in 200..299) {
            throw IllegalStateException(
                json.optString("error_description").ifEmpty { "HTTP $code" })
        }
        return json
    }

    private fun postFormRaw(url: String, body: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 20_000
            readTimeout = 30_000
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        return Pair(code, text)
    }

    private fun getJson(url: String, token: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = try {
                JSONObject(text).optJSONObject("error")?.optString("message") ?: "HTTP $code"
            } catch (e: Exception) { "HTTP $code" }
            if (code == 404) throw IllegalStateException("フォルダが見つかりません: $folder")
            throw IllegalStateException(msg)
        }
        return JSONObject(text)
    }
}
