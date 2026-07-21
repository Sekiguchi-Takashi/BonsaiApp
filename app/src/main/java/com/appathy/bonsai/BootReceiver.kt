package com.appathy.bonsai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 端末再起動時の復帰。
 *
 * **ユーザーが停止した機能は復帰させない。**
 * ServerService が保存しているON/OFF状態を見て、ONだったものだけ立ち上げる。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val server = ServerService.serverWanted(ctx)
        val mail = ServerService.mailWanted(ctx)
        Log.i("BootReceiver", "boot: server=$server mail=$mail")

        if (server) ServerService.startServer(ctx, 8080, false)
        if (mail) ServerService.startMail(ctx)
    }
}
