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

/**
 * HTTPサーバーをフォアグラウンドサービスとして保持する。
 *
 * これが無いと、アプリを閉じた瞬間にプロセスが落ちてサーバーも死ぬ。
 * 常駐通知が出るのはAndroidの仕様上避けられない。
 */
class ServerService : Service() {

    companion object {
        const val CHANNEL_ID = "bonsai_server"
        const val NOTIF_ID = 1
        const val ACTION_START = "com.appathy.bonsai.START"
        const val ACTION_STOP = "com.appathy.bonsai.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_BIND_ALL = "bind_all"

        @Volatile var server: LlamaServer? = null
            private set

        val isRunning: Boolean get() = server?.running == true

        fun start(ctx: Context, port: Int, bindAll: Boolean) {
            val i = Intent(ctx, ServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_BIND_ALL, bindAll)
            }
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ServerService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                server?.stop()
                server = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080
                val bindAll = intent?.getBooleanExtra(EXTRA_BIND_ALL, false) ?: false
                if (server == null) {
                    server = LlamaServer(port, bindAll).also { it.start() }
                }
                startForeground(NOTIF_ID, buildNotification(server!!.endpoint))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun buildNotification(endpoint: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Bonsai サーバー", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "ローカル推論サーバーの稼働状態" }
            nm.createNotificationChannel(ch)
        }

        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Bonsai 稼働中")
            .setContentText(endpoint)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }
}
