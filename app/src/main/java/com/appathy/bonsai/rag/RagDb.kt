package com.appathy.bonsai.rag

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * BM25 による全文検索インデックス。
 *
 * SQLite は Android 標準同梱なので依存は増えない。
 * FTS5 は端末の SQLite ビルドに依存するうえ、日本語トークナイザが無いので使わず、
 * 転置インデックスと BM25 スコアリングを自前で持つ。
 *
 * 埋め込みモデルを常駐させないので、追加のRAM消費はほぼゼロ。
 */
class RagDb(ctx: Context) : SQLiteOpenHelper(ctx, "rag.db", null, 1) {

    companion object {
        private const val K1 = 1.2
        private const val B = 0.75

        /** チャンク長。n_ctx=2048 に3件詰めても収まるサイズにしてある */
        const val CHUNK_CHARS = 500
        const val CHUNK_OVERLAP = 100
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE docs(
                id       INTEGER PRIMARY KEY AUTOINCREMENT,
                item_id  TEXT UNIQUE,
                path     TEXT,
                name     TEXT,
                mtime    TEXT
            )""")
        db.execSQL("""
            CREATE TABLE chunks(
                id     INTEGER PRIMARY KEY AUTOINCREMENT,
                doc_id INTEGER,
                ord    INTEGER,
                text   TEXT,
                len    INTEGER
            )""")
        db.execSQL("""
            CREATE TABLE postings(
                term     TEXT,
                chunk_id INTEGER,
                tf       INTEGER
            )""")
        db.execSQL("CREATE INDEX idx_post_term ON postings(term)")
        db.execSQL("CREATE INDEX idx_post_chunk ON postings(chunk_id)")
        db.execSQL("CREATE INDEX idx_chunk_doc ON chunks(doc_id)")
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS docs")
        db.execSQL("DROP TABLE IF EXISTS chunks")
        db.execSQL("DROP TABLE IF EXISTS postings")
        db.execSQL("DROP TABLE IF EXISTS meta")
        onCreate(db)
    }

    // ---------------------------------------------------------------- meta

    fun getMeta(key: String): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun setMeta(key: String, value: String?) {
        val db = writableDatabase
        if (value == null) {
            db.delete("meta", "k=?", arrayOf(key))
        } else {
            db.replace("meta", null, ContentValues().apply {
                put("k", key); put("v", value)
            })
        }
    }

    // ------------------------------------------------------------ indexing

    /** ドキュメントを丸ごと入れ替える（差分同期で更新があった時に呼ぶ） */
    fun upsertDoc(itemId: String, path: String, name: String, mtime: String, text: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            deleteDocInternal(db, itemId)

            val docId = db.insert("docs", null, ContentValues().apply {
                put("item_id", itemId); put("path", path)
                put("name", name); put("mtime", mtime)
            })

            for ((ord, chunk) in chunk(text).withIndex()) {
                val tf = Tokenizer.termFreq(chunk)
                if (tf.isEmpty()) continue
                val len = tf.values.sum()

                val chunkId = db.insert("chunks", null, ContentValues().apply {
                    put("doc_id", docId); put("ord", ord)
                    put("text", chunk); put("len", len)
                })

                val stmt = db.compileStatement(
                    "INSERT INTO postings(term, chunk_id, tf) VALUES(?,?,?)")
                for ((term, freq) in tf) {
                    stmt.clearBindings()
                    stmt.bindString(1, term)
                    stmt.bindLong(2, chunkId)
                    stmt.bindLong(3, freq.toLong())
                    stmt.executeInsert()
                }
                stmt.close()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteDoc(itemId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            deleteDocInternal(db, itemId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun deleteDocInternal(db: SQLiteDatabase, itemId: String) {
        val docId = db.rawQuery("SELECT id FROM docs WHERE item_id=?", arrayOf(itemId)).use {
            if (it.moveToFirst()) it.getLong(0) else null
        } ?: return

        db.execSQL("""
            DELETE FROM postings WHERE chunk_id IN
            (SELECT id FROM chunks WHERE doc_id=?)""", arrayOf(docId))
        db.delete("chunks", "doc_id=?", arrayOf(docId.toString()))
        db.delete("docs", "id=?", arrayOf(docId.toString()))
    }

    /**
     * 見出しと段落を尊重しつつ CHUNK_CHARS 程度に分割する。
     * Markdown の見出し行はチャンクの切れ目として優先する。
     */
    fun chunk(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = ArrayList<String>()
        val sb = StringBuilder()

        fun flush() {
            val s = sb.toString().trim()
            if (s.length >= 10) out.add(s)
            sb.setLength(0)
        }

        for (line in normalized.split("\n")) {
            val isHeading = line.trimStart().startsWith("#")
            if (isHeading && sb.length > CHUNK_CHARS / 2) flush()

            sb.append(line).append('\n')

            if (sb.length >= CHUNK_CHARS) {
                val s = sb.toString()
                out.add(s.trim())
                // 末尾を少し重ねて文脈の切断を和らげる
                val keep = s.takeLast(CHUNK_OVERLAP)
                sb.setLength(0)
                sb.append(keep)
            }
        }
        flush()
        return out
    }

    // ------------------------------------------------------------- search

    data class Hit(
        val chunkId: Long,
        val score: Double,
        val text: String,
        val path: String,
        val name: String
    )

    fun search(query: String, limit: Int = 3): List<Hit> {
        val db = readableDatabase

        val total = db.rawQuery("SELECT COUNT(*), AVG(len) FROM chunks", null).use {
            if (it.moveToFirst()) Pair(it.getLong(0), it.getDouble(1)) else Pair(0L, 0.0)
        }
        val n = total.first
        if (n == 0L) return emptyList()
        val avgdl = if (total.second > 0) total.second else 1.0

        val terms = Tokenizer.tokenize(query).distinct().take(64)
        if (terms.isEmpty()) return emptyList()

        val scores = HashMap<Long, Double>()

        for (term in terms) {
            val df = db.rawQuery(
                "SELECT COUNT(*) FROM postings WHERE term=?", arrayOf(term)
            ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            if (df == 0L) continue

            // BM25 の IDF。df が大きい語（助詞相当）は自然に効かなくなる
            val idf = Math.log(1.0 + (n - df + 0.5) / (df + 0.5))

            db.rawQuery("""
                SELECT p.chunk_id, p.tf, c.len
                FROM postings p JOIN chunks c ON c.id = p.chunk_id
                WHERE p.term = ?""", arrayOf(term)).use { cur ->
                while (cur.moveToNext()) {
                    val cid = cur.getLong(0)
                    val tf = cur.getDouble(1)
                    val dl = cur.getDouble(2)
                    val denom = tf + K1 * (1 - B + B * dl / avgdl)
                    scores[cid] = (scores[cid] ?: 0.0) + idf * (tf * (K1 + 1)) / denom
                }
            }
        }

        return scores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { (cid, score) ->
                db.rawQuery("""
                    SELECT c.text, d.path, d.name
                    FROM chunks c JOIN docs d ON d.id = c.doc_id
                    WHERE c.id = ?""", arrayOf(cid.toString())).use {
                    if (it.moveToFirst())
                        Hit(cid, score, it.getString(0), it.getString(1), it.getString(2))
                    else null
                }
            }
    }

    // --------------------------------------------------------------- stats

    data class Stats(val docs: Long, val chunks: Long, val terms: Long)

    fun stats(): Stats {
        val db = readableDatabase
        fun count(sql: String) = db.rawQuery(sql, null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
        return Stats(
            count("SELECT COUNT(*) FROM docs"),
            count("SELECT COUNT(*) FROM chunks"),
            count("SELECT COUNT(DISTINCT term) FROM postings")
        )
    }

    fun clearAll() {
        val db = writableDatabase
        db.execSQL("DELETE FROM postings")
        db.execSQL("DELETE FROM chunks")
        db.execSQL("DELETE FROM docs")
        db.delete("meta", "k=?", arrayOf(OneDrive.KEY_DELTA))
    }
}
