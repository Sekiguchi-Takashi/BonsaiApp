package com.appathy.bonsai

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import com.appathy.bonsai.mail.MailQueue
import com.appathy.bonsai.mail.MailWatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Gmail 連携の設定とキュー確認（フェーズ2）。
 * OAuth は使わず、アプリパスワード + IMAP。
 */
class MailActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var queue: MailQueue
    private lateinit var watcher: MailWatcher

    private lateinit var userInput: EditText
    private lateinit var passInput: EditText
    private lateinit var filterInput: EditText
    private lateinit var toggleBtn: Button
    private lateinit var status: TextView
    private lateinit var list: TextView

    private val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN)
    private var ticking = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = MailQueue(this)
        watcher = MailWatcher(this, queue)

        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#101014"))
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 12f
            setPadding(0, (10 * d).toInt(), 0, 0)
        }

        fun field(hint: String, value: String, pw: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#5F6368"))
            textSize = 14f
            inputType = if (pw)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
        }

        root.addView(label("Gmail アドレス"))
        userInput = field("you@gmail.com", watcher.user)
        root.addView(userInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(label("アプリパスワード（16桁・通常のパスワードではありません）"))
        passInput = field("xxxx xxxx xxxx xxxx", watcher.appPassword, pw = true)
        root.addView(passInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        Button(this).apply {
            text = "アプリパスワードを発行する"
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://myaccount.google.com/apppasswords")))
                } catch (_: Exception) {}
            }
            root.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        root.addView(label("差出人フィルタ（空欄なら全件・カンマ区切りで部分一致）"))
        filterInput = field("boss@example.com, @mycompany.co.jp", watcher.senderFilter)
        root.addView(filterInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        toggleBtn = Button(this).apply {
            setOnClickListener { toggle() }
        }
        root.addView(toggleBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        Button(this).apply {
            text = "受信位置をリセット（未読を再取得）"
            setOnClickListener {
                watcher.resetCursor()
                status.text = "受信位置をリセットしました"
            }
            root.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        Button(this).apply {
            text = "キューを全消去"
            setOnClickListener {
                queue.clearAll(); refresh()
            }
            root.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#63BA80")); textSize = 12f
        }
        root.addView(status)

        root.addView(label("受信キュー"))
        list = TextView(this).apply {
            setTextColor(Color.parseColor("#E8EAED"))
            textSize = 13f
            setTextIsSelectable(true)
        }
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
        tick()
    }

    private fun save() {
        watcher.user = userInput.text.toString()
        watcher.appPassword = passInput.text.toString()
        watcher.senderFilter = filterInput.text.toString()
    }

    private fun toggle() {
        save()
        if (ServerService.isMailRunning || ServerService.mailWanted(this)) {
            ServerService.stopMail(this)
            status.text = "停止しました"
        } else {
            if (!watcher.isConfigured) {
                status.text = "アドレスとアプリパスワードを入力してください"
                return
            }
            ServerService.startMail(this)
            status.text = "起動しました"
        }
        ui.postDelayed({ refresh() }, 500)
    }

    private fun refresh() {
        val on = ServerService.mailWanted(this)
        toggleBtn.text = if (on) "メール監視を停止" else "メール監視を開始"

        val (p, d, e) = queue.counts()
        status.text = "${ServerService.mailStatus}  /  未処理 $p ・処理済 $d ・失敗 $e"

        val items = queue.recent(20)
        list.text = if (items.isEmpty()) "（まだありません）"
        else buildString {
            for (m in items) {
                append("[${m.status}] ${fmt.format(Date(m.receivedAt))}\n")
                append("From: ${m.sender.take(60)}\n")
                append("Sub : ${m.subject.take(60)}\n")
                append(m.body.take(120).replace("\n", " "))
                append("\n\n")
            }
        }
    }

    /** 監視ステータスとキューを定期更新する */
    private fun tick() {
        if (!ticking) return
        refresh()
        ui.postDelayed({ tick() }, 3000)
    }

    override fun onDestroy() {
        ticking = false
        super.onDestroy()
    }
}
