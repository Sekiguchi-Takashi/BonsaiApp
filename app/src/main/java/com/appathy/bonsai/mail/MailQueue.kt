package com.appathy.bonsai.mail

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 受信メールのキュー。
 *
 * フェーズ2ではここに積むところまで。
 * フェーズ3で pending を拾って RAG検索 → 推論 → answer に書き戻す。
 */
class MailQueue(ctx: Context) : SQLiteOpenHelper(ctx, "mail.db", null, 1) {

    companion object {
        const val PENDING = "pending"
        const val DONE = "done"
        const val ERROR = "error"
    }

    data class Item(
        val id: Long,
        val uid: Long,
        val sender: String,
        val subject: String,
        val body: String,
        val receivedAt: Long,
        val status: String,
        val answer: String?
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE mails(
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                uid         INTEGER UNIQUE,
                sender      TEXT,
                subject     TEXT,
                body        TEXT,
                received_at INTEGER,
                status      TEXT,
                answer      TEXT
            )""")
        db.execSQL("CREATE INDEX idx_status ON mails(status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS mails")
        onCreate(db)
    }

    /** 既に取り込み済みなら false */
    fun enqueue(uid: Long, mail: MimeParser.Mail): Boolean {
        val db = writableDatabase
        val exists = db.rawQuery("SELECT 1 FROM mails WHERE uid=?",
            arrayOf(uid.toString())).use { it.moveToFirst() }
        if (exists) return false

        db.insert("mails", null, ContentValues().apply {
            put("uid", uid)
            put("sender", mail.from)
            put("subject", mail.subject)
            put("body", mail.body.take(20000))
            put("received_at", System.currentTimeMillis())
            put("status", PENDING)
        })
        return true
    }

    fun recent(limit: Int = 30): List<Item> {
        val out = ArrayList<Item>()
        readableDatabase.rawQuery(
            "SELECT id,uid,sender,subject,body,received_at,status,answer " +
            "FROM mails ORDER BY id DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) {
                out.add(Item(
                    c.getLong(0), c.getLong(1), c.getString(2) ?: "",
                    c.getString(3) ?: "", c.getString(4) ?: "",
                    c.getLong(5), c.getString(6) ?: PENDING,
                    if (c.isNull(7)) null else c.getString(7)))
            }
        }
        return out
    }

    fun nextPending(): Item? = readableDatabase.rawQuery(
        "SELECT id,uid,sender,subject,body,received_at,status,answer " +
        "FROM mails WHERE status=? ORDER BY id ASC LIMIT 1",
        arrayOf(PENDING)).use { c ->
        if (!c.moveToFirst()) null
        else Item(c.getLong(0), c.getLong(1), c.getString(2) ?: "",
            c.getString(3) ?: "", c.getString(4) ?: "",
            c.getLong(5), c.getString(6) ?: PENDING,
            if (c.isNull(7)) null else c.getString(7))
    }

    fun setAnswer(id: Long, answer: String, status: String = DONE) {
        writableDatabase.update("mails", ContentValues().apply {
            put("answer", answer); put("status", status)
        }, "id=?", arrayOf(id.toString()))
    }

    fun counts(): Triple<Int, Int, Int> {
        fun n(st: String) = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM mails WHERE status=?", arrayOf(st)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        return Triple(n(PENDING), n(DONE), n(ERROR))
    }

    /** 失敗した項目を未処理に戻す */
    fun retryErrors(): Int {
        val db = writableDatabase
        val n = db.rawQuery("SELECT COUNT(*) FROM mails WHERE status=?",
            arrayOf(ERROR)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        db.execSQL("UPDATE mails SET status=?, answer=NULL WHERE status=?",
            arrayOf(PENDING, ERROR))
        return n
    }

    fun clearAll() {
        writableDatabase.execSQL("DELETE FROM mails")
    }
}
