package com.appathy.bonsai.mail

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 最小限の IMAP クライアント。外部ライブラリなし。
 *
 * Gmail はアプリパスワード（2段階認証が前提）で IMAP ログインできる。
 * OAuth も Google Cloud プロジェクトも不要。
 *
 * 対応コマンド: LOGIN / SELECT / UID SEARCH / UID FETCH / IDLE / LOGOUT
 */
class ImapClient(
    private val host: String = "imap.gmail.com",
    private val port: Int = 993
) {
    companion object {
        private const val TAG = "ImapClient"
        private const val MAX_MESSAGE_BYTES = 512 * 1024
    }

    private var socket: SSLSocket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStream? = null
    private var tagSeq = 0

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    // ----------------------------------------------------------- 接続

    fun connect(user: String, appPassword: String) {
        close()
        val s = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(host, port) as SSLSocket
        s.soTimeout = 60_000
        s.startHandshake()
        socket = s
        reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.ISO_8859_1))
        writer = s.outputStream

        readLine()   // * OK Gimap ready

        // アプリパスワードは空白を含む形で表示されるが、詰めても通る
        val pw = appPassword.replace(" ", "")
        val resp = command("LOGIN ${quote(user)} ${quote(pw)}")
        if (!resp.ok) throw IllegalStateException("ログイン失敗: ${resp.text}")
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; reader = null; writer = null
    }

    // ----------------------------------------------------------- 操作

    /** INBOX を選択し、メッセージ総数を返す */
    fun selectInbox(): Int {
        var exists = 0
        val resp = command("SELECT INBOX") { line ->
            Regex("^\\* (\\d+) EXISTS").find(line)?.let { exists = it.groupValues[1].toInt() }
        }
        if (!resp.ok) throw IllegalStateException("SELECT 失敗: ${resp.text}")
        return exists
    }

    /** 未読メールの UID 一覧。sinceUid より大きいものだけ返す */
    fun searchUnseen(sinceUid: Long): List<Long> {
        val uids = ArrayList<Long>()
        val criteria = if (sinceUid > 0) "UID ${sinceUid + 1}:* UNSEEN" else "UNSEEN"
        val resp = command("UID SEARCH $criteria") { line ->
            if (line.startsWith("* SEARCH")) {
                line.removePrefix("* SEARCH").trim().split(" ")
                    .mapNotNull { it.toLongOrNull() }
                    .filterTo(uids) { it > sinceUid }
            }
        }
        if (!resp.ok) throw IllegalStateException("SEARCH 失敗: ${resp.text}")
        return uids.distinct().sorted()
    }

    /**
     * 本文を含めて取得する。
     * BODY.PEEK[] なので既読フラグは立てない（ユーザーのGmailを汚さない）。
     */
    fun fetchMessage(uid: Long): MimeParser.Mail? {
        val sb = StringBuilder()
        var literalRemaining = 0

        val resp = command("UID FETCH $uid (BODY.PEEK[])") { line ->
            if (literalRemaining > 0) {
                val take = minOf(literalRemaining, line.length + 1)
                sb.append(line).append('\n')
                literalRemaining -= take
            } else {
                // * 12 FETCH (UID 34 BODY[] {5678}
                Regex("\\{(\\d+)\\}\\s*$").find(line)?.let {
                    literalRemaining = minOf(it.groupValues[1].toInt(), MAX_MESSAGE_BYTES)
                }
            }
        }
        if (!resp.ok) {
            Log.e(TAG, "FETCH 失敗 uid=$uid: ${resp.text}")
            return null
        }
        if (sb.isEmpty()) return null

        // ISO-8859-1 で読んだバイト列を復元してからパースする
        val raw = String(sb.toString().toByteArray(Charsets.ISO_8859_1), Charsets.ISO_8859_1)
        return try {
            MimeParser.parse(raw)
        } catch (e: Exception) {
            Log.e(TAG, "parse 失敗 uid=$uid", e); null
        }
    }

    /**
     * IDLE で新着を待つ。新着があれば true、タイムアウトなら false。
     * Gmail は29分で切るので timeoutMs は25分以下にすること。
     */
    fun idle(timeoutMs: Int, isCancelled: () -> Boolean): Boolean {
        val tag = nextTag()
        send("$tag IDLE")

        val deadline = System.currentTimeMillis() + timeoutMs
        var newMail = false
        socket?.soTimeout = 20_000

        try {
            while (System.currentTimeMillis() < deadline && !isCancelled()) {
                val line = try { readLine() } catch (e: SocketTimeoutException) { continue }
                    ?: break
                if (line.contains(" EXISTS") || line.contains(" RECENT")) {
                    newMail = true
                    break
                }
            }
        } finally {
            try {
                send("DONE")
                // タグ付き応答が来るまで読み捨てる
                while (true) {
                    val l = readLine() ?: break
                    if (l.startsWith(tag)) break
                }
            } catch (_: Exception) {}
            socket?.soTimeout = 60_000
        }
        return newMail
    }

    // ----------------------------------------------------------- 内部

    private data class Response(val ok: Boolean, val text: String)

    private fun nextTag() = "A%03d".format(++tagSeq)

    private fun command(cmd: String, onLine: ((String) -> Unit)? = null): Response {
        val tag = nextTag()
        send("$tag $cmd")
        while (true) {
            val line = readLine() ?: return Response(false, "接続が切れました")
            if (line.startsWith(tag)) {
                val rest = line.removePrefix(tag).trim()
                return Response(rest.startsWith("OK"), rest)
            }
            onLine?.invoke(line)
        }
    }

    private fun send(s: String) {
        val w = writer ?: throw IllegalStateException("未接続")
        w.write((s + "\r\n").toByteArray(Charsets.ISO_8859_1))
        w.flush()
    }

    private fun readLine(): String? = reader?.readLine()

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
