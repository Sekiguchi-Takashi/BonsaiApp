package com.appathy.bonsai.rag

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * 端末内フォルダを走査して .txt / .md をインデックスする。
 *
 * クラウドAPIはアプリに持たない。OneDrive / Google Drive / Dropbox からの
 * 取得は Termux の rclone に任せ、アプリは「同期されたフォルダを読む」だけ。
 * これにより Azure / GCP のアプリ登録が一切不要になる。
 *
 * SAF（ACTION_OPEN_DOCUMENT_TREE）でユーザーがフォルダを選ぶので
 * ストレージ権限の宣言も不要。androidx の DocumentFile は使わず、
 * プラットフォーム API の DocumentsContract を直接叩いている。
 */
class FolderSync(private val ctx: Context, private val db: RagDb) {

    companion object {
        private const val TAG = "FolderSync"
        private const val PREFS = "foldersync"
        private const val K_TREE = "tree_uri"
        private val TEXT_EXT = setOf("txt", "md", "markdown", "text")
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    }

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cr: ContentResolver get() = ctx.contentResolver

    var treeUri: Uri?
        get() = prefs.getString(K_TREE, null)?.let { Uri.parse(it) }
        set(v) = prefs.edit().apply {
            if (v == null) remove(K_TREE) else putString(K_TREE, v.toString())
        }.apply()

    /** SAFで選んだフォルダの永続アクセス権を確保する */
    fun persist(uri: Uri) {
        cr.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        treeUri = uri
    }

    fun folderLabel(): String {
        val uri = treeUri ?: return "未選択"
        return Uri.decode(uri.lastPathSegment ?: uri.toString())
    }

    data class Result(
        val added: Int, val updated: Int, val deleted: Int,
        val unchanged: Int, val skipped: Int
    )

    private data class Entry(val docId: String, val name: String, val mtime: Long, val size: Long)

    /**
     * 差分インデックス。
     * mtime とサイズが前回と同じファイルは読み込まずにスキップする。
     */
    fun sync(onProgress: (String) -> Unit): Result {
        val tree = treeUri ?: throw IllegalStateException("フォルダが未選択です")

        onProgress("フォルダを走査中…")
        val files = ArrayList<Entry>()
        walk(tree, DocumentsContract.getTreeDocumentId(tree), files, 0)

        var added = 0; var updated = 0; var unchanged = 0; var skipped = 0
        val seen = HashSet<String>()

        for (f in files) {
            val ext = f.name.substringAfterLast('.', "").lowercase()
            if (ext !in TEXT_EXT) { skipped++; continue }
            if (f.size > MAX_FILE_BYTES) { skipped++; continue }

            val key = "stamp:${f.docId}"
            val stamp = "${f.mtime}:${f.size}"
            seen.add(f.docId)

            if (db.getMeta(key) == stamp) { unchanged++; continue }

            onProgress("読込中: ${f.name}")
            val text = try {
                readText(tree, f.docId)
            } catch (e: Exception) {
                Log.e(TAG, "read failed: ${f.name}", e); skipped++; continue
            }

            val existed = db.getMeta(key) != null
            db.upsertDoc(f.docId, folderLabel(), f.name, f.mtime.toString(), text)
            db.setMeta(key, stamp)
            if (existed) updated++ else added++
        }

        // フォルダから消えたファイルをインデックスからも削除
        var deleted = 0
        for (itemId in db.allDocIds()) {
            if (itemId !in seen) {
                db.deleteDoc(itemId)
                db.setMeta("stamp:$itemId", null)
                deleted++
            }
        }

        Log.i(TAG, "sync +$added ~$updated -$deleted =$unchanged skip=$skipped")
        return Result(added, updated, deleted, unchanged, skipped)
    }

    private fun walk(tree: Uri, parentId: String, out: MutableList<Entry>, depth: Int) {
        if (depth > 8) return   // 循環・過剰な深さの保険

        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        )

        cr.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val mtime = if (c.isNull(3)) 0L else c.getLong(3)
                val size = if (c.isNull(4)) 0L else c.getLong(4)

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(tree, id, out, depth + 1)
                } else {
                    out.add(Entry(id, name, mtime, size))
                }
            }
        }
    }

    private fun readText(tree: Uri, docId: String): String {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        cr.openInputStream(uri)!!.use { ins ->
            val bytes = ins.readBytes()
            val s = String(bytes, Charsets.UTF_8)
            return if (s.startsWith("\uFEFF")) s.substring(1) else s
        }
    }
}
