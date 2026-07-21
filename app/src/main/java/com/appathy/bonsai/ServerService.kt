package com.appathy.bonsai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.appathy.bonsai.mail.MailQueue
import com.appathy.bonsai.mail.MailWatcher
import com.appathy.bonsai.mail.Pipeline
import java.io.File
import kotlin.concurrent.thread

/**
 * サーバーとメール監視をまとめて抱えるフォアグラウンドサービス。
 *
 * ライフサイクルの方針（v0.9で整理）:
 *  - 機能が1つでもONならサービスは生存し、常駐通知を出す
 *  - **すべてOFFになった時点でサービスは完全に終了する**
 *    （通知も消え、モデルも解放され、プロセスは常駐しなくなる）
 *  - ONの状態は SharedPreferences に保存し、システムに殺されて
 *    再生成された場合はその状態を復元する。OFFで殺された場合は即座に自殺する
 *  - 端末再起動時は BootReceiver がこの保存状態を見て、ONだった時だけ復帰する
 */
class ServerService : Service() {

    companion object {
        private const val TAG = "ServerService"
        const val CHANNEL_ID = "bonsai_service"
        const val ANSWER_CHANNEL_ID = "bonsai_answer"
        const val NOTIF_ID = 1
        const val NOTIF_ANSWER_ID = 2

        const val ACTION_START_SERVER = "com.appathy.bonsai.START_SERVER"
        const val ACTION_STOP_SERVER = "com.appathy.bonsai.STOP_SERVER"
        const val ACTION_START_MAIL = "com.appathy.bonsai.START_MAIL"
        const val ACTION_STOP_MAIL = "com.appathy.bonsai.STOP_MAIL"

        const val EXTRA_PORT = "port"
        const val EXTRA_BIND_ALL = "bind_all"

        private const val PREFS = "service_state"
        private const val K_SERVER = "server_on"
        private const val K_MAIL = "mail_on"
        private const val K_PORT = "port"
        private const val K_BIND_ALL = "bind_all"

        @Volatile var server: LlamaServer? = null
            private set
        @Volatile var watcher: MailWatcher? = null
            private set

        /** 推論パイプラインの現在状態（UI表示用） */
        @Volatile var pipelineStatus: String = "待機"
            private set

        val isServerRunning: Boolean get() = server?.running == true
        val isMailRunning: Boolean get() = watcher?.running == true

        /** 直近のメール監視ステータス（UI表示用） */
        val mailStatus: String get() = watcher?.status ?: "停止中"

        fun wanted(ctx: Context, key: String): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, false)

        fun serverWanted(ctx: Context) = wanted(ctx, K_SERVER)
        fun mailWanted(ctx: Context) = wanted(ctx, K_MAIL)

        fun startServer(ctx: Context, port: Int, bindAll: Boolean) {
            ctx.startForegroundService(Intent(ctx, ServerService::class.java).apply {
                action = ACTION_START_SERVER
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_BIND_ALL, bindAll)
            })
        }

        fun stopServer(ctx: Context) {
            ctx.startService(Intent(ctx, ServerService::class.java).apply {
                action = ACTION_STOP_SERVER
            })
        }

        fun startMail(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ServerService::class.java).apply {
                action = ACTION_START_MAIL
            })
        }

        fun stopMail(ctx: Context) {
            ctx.startService(Intent(ctx, ServerService::class.java).apply {
                action = ACTION_STOP_MAIL
            })
        }
    }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> {
                val port = intent.getIntExtra(EXTRA_PORT, 8080)
                val bindAll = intent.getBooleanExtra(EXTRA_BIND_ALL, false)
                prefs.edit().putBoolean(K_SERVER, true)
                    .putInt(K_PORT, port).putBoolean(K_BIND_ALL, bindAll).apply()
                startServerInternal(port, bindAll)
            }
            ACTION_STOP_SERVER -> {
                prefs.edit().putBoolean(K_SERVER, false).apply()
                server?.stop(); server = null
            }
            ACTION_START_MAIL -> {
                prefs.edit().putBoolean(K_MAIL, true).apply()
                startMailInternal()
            }
            ACTION_STOP_MAIL -> {
                prefs.edit().putBoolean(K_MAIL, false).apply()
                watcher?.stop(); watcher = null
                stopPipeline()
            }
            else -> {
                // intent == null はシステムによる再生成。保存状態に従う
                Log.i(TAG, "restarted by system")
                if (prefs.getBoolean(K_SERVER, false)) {
                    startServerInternal(
                        prefs.getInt(K_PORT, 8080),
                        prefs.getBoolean(K_BIND_ALL, false))
                }
                if (prefs.getBoolean(K_MAIL, false)) startMailInternal()
            }
        }

        // どちらもOFFになったら完全終了する
        val anyOn = prefs.getBoolean(K_SERVER, false) || prefs.getBoolean(K_MAIL, false)
        if (!anyOn) {
            shutdown()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    private fun shutdown() {
        Log.i(TAG, "all features off -> shutting down")
        server?.stop(); server = null
        watcher?.stop(); watcher = null
        stopPipeline()
        // 画面が閉じているならモデルも解放してRAMを返す
        if (!MainActivity.isForeground) Engine.bridge.free()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------- 機能

    private fun startServerInternal(port: Int, bindAll: Boolean) {
        if (server == null) {
            server = LlamaServer(port, bindAll).also { it.start() }
        }
        ensureModelLoaded()
    }

    private fun startMailInternal() {
        if (watcher != null && watcher!!.running) return
        val q = MailQueue(applicationContext)
        val w = MailWatcher(applicationContext, q)
        watcher = w
        try {
            w.start { updateNotification() }
        } catch (e: Exception) {
            Log.e(TAG, "mail start failed", e)
            prefs.edit().putBoolean(K_MAIL, false).apply()
            watcher = null
            return
        }
        // 受信だけでは意味がないので、推論パイプラインも一緒に起動する
        ensureModelLoaded()
        startPipeline(q)
    }

    // --------------------------------------------------- 推論パイプライン

    @Volatile private var pipelineThread: Thread? = null

    private fun startPipeline(queue: MailQueue) {
        if (pipelineThread?.isAlive == true) return

        pipelineThread = thread(name = "mail-pipeline") {
            val pipeline = Pipeline(applicationContext, queue)
            Log.i(TAG, "pipeline started")

            while (prefs.getBoolean(K_MAIL, false)) {
                val item = queue.nextPending()
                if (item == null) {
                    pipelineStatus = "待機"
                    try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
                    continue
                }

                if (!Engine.isLoaded) {
                    pipelineStatus = "モデル未読込"
                    updateNotification()
                    try { Thread.sleep(5000) } catch (e: InterruptedException) { break }
                    continue
                }

                pipelineStatus = "処理中: ${item.subject.take(20)}"
                updateNotification()

                try {
                    val out = pipeline.process(item)
                    queue.setAnswer(item.id, out.answer, MailQueue.DONE)
                    notifyAnswered(item.subject)
                    Log.i(TAG, "answered id=${item.id} in ${out.ms}ms")
                } catch (e: Exception) {
                    Log.e(TAG, "pipeline failed id=${item.id}", e)
                    queue.setAnswer(item.id, "処理に失敗しました: ${e.message}",
                        MailQueue.ERROR)
                }
                pipelineStatus = "待機"
                updateNotification()
            }
            pipelineStatus = "停止"
            Log.i(TAG, "pipeline stopped")
        }
    }

    private fun stopPipeline() {
        pipelineThread?.interrupt()
        pipelineThread = null
        pipelineStatus = "停止"
    }

    /** 回答ができたことを知らせる（常駐通知とは別枠） */
    private fun notifyAnswered(subject: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(NotificationChannel(
                    ANSWER_CHANNEL_ID, "回答", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val tap = PendingIntent.getActivity(
                this, 1, Intent(this, MailActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            nm.notify(NOTIF_ANSWER_ID, Notification.Builder(this, ANSWER_CHANNEL_ID)
                .setContentTitle("回答ができました")
                .setContentText(subject.take(60))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build())
        } catch (_: Exception) {}
    }

    /**
     * サービスがシステムに再生成された場合、Activityを経由しないので
     * モデルが読み込まれていない。ここで自前で読み込む。
     */
    private fun ensureModelLoaded() {
        if (Engine.isLoaded) return
        val f = File(filesDir, "model.gguf")
        if (!f.exists()) {
            Log.w(TAG, "model file not found; server will report no_model")
            return
        }
        thread(name = "model-load") {
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
            val ok = Engine.bridge.load(f.absolutePath, nCtx = 2048, nThreads = threads)
            Log.i(TAG, "model load from service: $ok")
            updateNotification()
        }
    }

    // ------------------------------------------------------------- 通知

    private fun updateNotification() {
        if (!prefs.getBoolean(K_SERVER, false) && !prefs.getBoolean(K_MAIL, false)) return
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Bonsai 常駐", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "サーバーとメール監視の稼働状態" })
        }

        val lines = ArrayList<String>()
        if (prefs.getBoolean(K_SERVER, false)) {
            lines.add("API " + (server?.endpoint ?: "起動中") +
                    if (Engine.isLoaded) "" else "（モデル未読込）")
        }
        if (prefs.getBoolean(K_MAIL, false)) {
            lines.add("メール: ${watcher?.status ?: "起動中"}")
            lines.add("推論: $pipelineStatus")
        }

        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Bonsai 稼働中")
            .setContentText(lines.joinToString(" / "))
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        server?.stop(); server = null
        watcher?.stop(); watcher = null
        super.onDestroy()
    }
}
