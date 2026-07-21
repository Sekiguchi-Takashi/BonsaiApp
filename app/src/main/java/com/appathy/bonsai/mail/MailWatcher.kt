package com.appathy.bonsai.mail

import android.content.Context
import android.util.Log
import kotlin.concurrent.thread

/**
 * IMAP IDLE で新着を待ち受け、キューに積む常駐ワーカー。
 *
 * ポーリングではなく IDLE を使うので、通信量と電池消費を抑えつつ
 * ほぼリアルタイムに反応する。切断されたら指数バックオフで再接続する。
 */
class MailWatcher(private val ctx: Context, private val queue: MailQueue) {

    companion object {
        private const val TAG = "MailWatcher"
        private const val PREFS = "mail"
        private const val K_USER = "user"
        private const val K_PASS = "app_password"
        private const val K_FILTER = "sender_filter"
        private const val K_LAST_UID = "last_uid"

        /** Gmail は29分でIDLEを切るので、その手前で張り直す */
        private const val IDLE_MS = 24 * 60 * 1000
    }

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var user: String
        get() = prefs.getString(K_USER, "") ?: ""
        set(v) = prefs.edit().putString(K_USER, v.trim()).apply()

    var appPassword: String
        get() = prefs.getString(K_PASS, "") ?: ""
        set(v) = prefs.edit().putString(K_PASS, v.trim()).apply()

    /** 空なら全件。カンマ区切りで部分一致（差出人アドレス） */
    var senderFilter: String
        get() = prefs.getString(K_FILTER, "") ?: ""
        set(v) = prefs.edit().putString(K_FILTER, v.trim()).apply()

    private var lastUid: Long
        get() = prefs.getLong(K_LAST_UID, 0)
        set(v) = prefs.edit().putLong(K_LAST_UID, v).apply()

    val isConfigured: Boolean get() = user.isNotEmpty() && appPassword.isNotEmpty()

    @Volatile var running = false
        private set

    @Volatile var status: String = "停止中"
        private set

    private var worker: Thread? = null
    private val client = ImapClient()

    fun start(onEvent: (String) -> Unit) {
        if (running) return
        if (!isConfigured) throw IllegalStateException("メールアドレスとアプリパスワードが必要です")
        running = true

        worker = thread(name = "mail-watcher") {
            var backoff = 5_000L
            while (running) {
                try {
                    status = "接続中"
                    onEvent(status)
                    client.connect(user, appPassword)
                    client.selectInbox()
                    backoff = 5_000L

                    // 接続直後に取りこぼし分を回収
                    drain(onEvent)

                    while (running) {
                        status = "待機中 (IDLE)"
                        onEvent(status)
                        val hasNew = client.idle(IDLE_MS) { !running }
                        if (!running) break
                        if (hasNew) drain(onEvent)
                        else {
                            // タイムアウト時はIDLEを張り直す前に軽く確認
                            client.selectInbox()
                            drain(onEvent)
                        }
                    }
                } catch (e: Exception) {
                    if (!running) break
                    Log.e(TAG, "watcher error", e)
                    status = "再接続待ち: ${e.message}"
                    onEvent(status)
                    try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
                    backoff = minOf(backoff * 2, 5 * 60_000L)
                } finally {
                    client.close()
                }
            }
            status = "停止中"
            onEvent(status)
        }
    }

    fun stop() {
        running = false
        client.close()
        worker?.interrupt()
        worker = null
        status = "停止中"
    }

    /** 未取得の新着をキューへ */
    private fun drain(onEvent: (String) -> Unit) {
        val uids = client.searchUnseen(lastUid)
        if (uids.isEmpty()) return

        status = "取得中 ${uids.size}件"
        onEvent(status)

        val filters = senderFilter.split(",")
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }

        var queued = 0
        for (uid in uids) {
            if (!running) return
            val mail = client.fetchMessage(uid) ?: continue

            if (filters.isNotEmpty() &&
                filters.none { mail.from.lowercase().contains(it) }) {
                lastUid = maxOf(lastUid, uid)
                continue
            }

            if (queue.enqueue(uid, mail)) queued++
            lastUid = maxOf(lastUid, uid)
        }

        if (queued > 0) {
            status = "新着 ${queued}件をキューに追加"
            onEvent(status)
        }
    }

    /** 手動での1回取得（設定画面のテスト用） */
    fun fetchOnce(): Int {
        client.connect(user, appPassword)
        try {
            client.selectInbox()
            var n = 0
            drain { }
            queue.counts().let { n = it.first }
            return n
        } finally {
            client.close()
        }
    }

    fun resetCursor() { lastUid = 0 }
}
