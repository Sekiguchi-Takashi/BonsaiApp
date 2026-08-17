package com.appathy.bonsai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * モデル専用フォルダの管理（v1.9）。
 *
 * v1.8 までは選んだ .gguf をアプリ内部へコピーしていた。570MB のファイルが
 * 端末に2つ存在することになり、アンインストールすると消えて入れ直しも必要だった。
 *
 * v1.9 では**コピーせず、選んだフォルダのファイルを直接読む**。
 * SAF から ParcelFileDescriptor を取り、`/proc/self/fd/<番号>` を
 * llama.cpp に渡す。llama.cpp 側は普通のファイルとして開けるので mmap も効く。
 *
 * 記述子はモデルを解放するまで開いたままにする（閉じるとパスが無効になる）。
 */
class ModelStore(private val ctx: Context) {

    companion object {
        private const val TAG = "ModelStore"
        private const val PREFS = "model_folder"
        private const val K_TREE = "tree_uri"
        private const val K_NAME = "file_name"
    }

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cr get() = ctx.contentResolver

    /** 読み込み中のモデルを指す記述子。解放するまで保持する */
    private var pfd: ParcelFileDescriptor? = null

    var treeUri: Uri?
        get() = prefs.getString(K_TREE, null)?.let { Uri.parse(it) }
        private set(v) = prefs.edit().apply {
            if (v == null) remove(K_TREE) else putString(K_TREE, v.toString())
        }.apply()

    /** 前回使ったファイル名。フォルダに複数ある場合の既定として使う */
    var fileName: String
        get() = prefs.getString(K_NAME, "") ?: ""
        set(v) = prefs.edit().putString(K_NAME, v).apply()

    val isConfigured: Boolean get() = treeUri != null

    fun folderLabel(): String {
        val u = treeUri ?: return "未選択"
        return Uri.decode(u.lastPathSegment ?: u.toString())
    }

    fun persist(uri: Uri) {
        cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        treeUri = uri
    }

    // ------------------------------------------------------------- 一覧

    data class Entry(val docId: String, val name: String, val size: Long)

    /** フォルダ直下の .gguf 一覧（名前順） */
    fun listModels(): List<Entry> {
        val tree = treeUri ?: return emptyList()
        val out = ArrayList<Entry>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE)
        try {
            cr.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!name.lowercase().endsWith(".gguf")) continue
                    out.add(Entry(c.getString(0), name,
                        if (c.isNull(2)) 0L else c.getLong(2)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "list failed", e)
        }
        return out.sortedBy { it.name }
    }

    /** 前回のファイル、無ければ最初の1件 */
    fun preferred(): Entry? {
        val list = listModels()
        if (list.isEmpty()) return null
        return list.firstOrNull { it.name == fileName } ?: list.first()
    }

    // ------------------------------------------------------------- 展開

    /**
     * llama.cpp に渡せるパスを作る（直接読み）。
     *
     * 返したパスは [release] を呼ぶまで有効。ただしスコープドストレージ配下では
     * llama.cpp がこのパスを開き直せず失敗することがある（後述の [cachePath]）。
     */
    fun openPath(entry: Entry): String {
        val tree = treeUri ?: throw IllegalStateException("フォルダ未選択")
        release()
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.docId)
        val d = cr.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("ファイルを開けません: ${entry.name}")
        pfd = d
        fileName = entry.name
        return "/proc/self/fd/${d.fd}"
    }

    /**
     * 直接読みが失敗したときのフォールバック。
     *
     * `/proc/self/fd/N` は実ファイルへのシンボリックリンクなので、llama.cpp が
     * 開き直すと `/storage/emulated/0/...` を直接開こうとし、
     * スコープドストレージの制限で拒否される。SAF の記述子は
     * 「その fd 経由でのみ」有効なため、この経路は端末によって通らない。
     *
     * そこでアプリ内部にキャッシュを作る。ファイル名とサイズが一致する
     * キャッシュがあれば再利用するので、コピーは初回だけで済む。
     * 元ファイルはフォルダに残るため、キャッシュが消えても再取得は不要。
     */
    fun cachePath(entry: Entry, onProgress: (Long) -> Unit): String {
        val tree = treeUri ?: throw IllegalStateException("フォルダ未選択")
        val cache = File(ctx.filesDir, "model_cache.gguf")
        val stamp = File(ctx.filesDir, "model_cache.txt")
        val want = "${entry.name}:${entry.size}"

        if (cache.exists() && stamp.exists() && stamp.readText().trim() == want) {
            Log.i(TAG, "cache hit: ${entry.name}")
            return cache.absolutePath
        }

        // 空き容量の確認（キャッシュを作り直すぶんを含む）
        val need = entry.size + 64L * 1024 * 1024
        val free = ctx.filesDir.usableSpace + (if (cache.exists()) cache.length() else 0L)
        if (entry.size > 0 && free < need) {
            throw IllegalStateException(
                "空き容量が足りません（必要 ${need / 1024 / 1024}MB / " +
                "空き ${free / 1024 / 1024}MB）")
        }

        release()
        if (cache.exists()) cache.delete()
        if (stamp.exists()) stamp.delete()

        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.docId)
        val tmp = File(ctx.filesDir, "model_cache.tmp")
        cr.openInputStream(uri)!!.use { ins ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(1 shl 20)
                var total = 0L
                var last = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (total - last >= 16L * 1024 * 1024) {
                        last = total
                        onProgress(total)
                    }
                }
            }
        }
        if (!tmp.renameTo(cache)) throw IllegalStateException("キャッシュの作成に失敗しました")
        stamp.writeText(want)
        fileName = entry.name
        Log.i(TAG, "cached: ${entry.name} (${cache.length()} bytes)")
        return cache.absolutePath
    }

    /** キャッシュを削除する（容量を空けたいとき） */
    fun clearCache() {
        File(ctx.filesDir, "model_cache.gguf").delete()
        File(ctx.filesDir, "model_cache.txt").delete()
        File(ctx.filesDir, "model_cache.tmp").delete()
    }

    /** 記述子を閉じる。モデルを解放したあとに呼ぶこと */
    fun release() {
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
    }
}
