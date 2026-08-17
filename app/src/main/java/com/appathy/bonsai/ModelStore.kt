package com.appathy.bonsai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log

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
     * llama.cpp に渡せるパスを作る。コピーはしない。
     * 返したパスは [release] を呼ぶまで有効。
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

    /** 記述子を閉じる。モデルを解放したあとに呼ぶこと */
    fun release() {
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
    }
}
