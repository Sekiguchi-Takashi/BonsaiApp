package com.appathy.bonsai

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.appathy.bonsai.rag.OneDrive
import com.appathy.bonsai.rag.RagDb
import kotlin.concurrent.thread

/**
 * RAG設定画面（フェーズ1）。
 * OneDrive のサインイン、差分同期、BM25検索の動作確認までをここで完結させる。
 */
class RagActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var db: RagDb
    private lateinit var drive: OneDrive

    private lateinit var clientInput: EditText
    private lateinit var folderInput: EditText
    private lateinit var signBtn: Button
    private lateinit var syncBtn: Button
    private lateinit var log: TextView
    private lateinit var queryInput: EditText
    private lateinit var results: TextView
    private lateinit var statsView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = RagDb(this)
        drive = OneDrive(this, db)

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
            setPadding(0, (8 * d).toInt(), 0, 0)
        }

        fun field(hint: String, value: String) = EditText(this).apply {
            this.hint = hint
            setText(value)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#5F6368"))
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 14f
        }

        root.addView(label("Azure アプリの クライアントID"))
        clientInput = field("00000000-0000-0000-0000-000000000000", drive.clientId)
        root.addView(clientInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(label("OneDrive 上のフォルダ"))
        folderInput = field("/RAG", drive.folder)
        root.addView(folderInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        signBtn = Button(this).apply { setOnClickListener { toggleSignIn() } }
        root.addView(signBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        syncBtn = Button(this).apply {
            text = "差分同期"
            setOnClickListener { doSync() }
        }
        root.addView(syncBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        Button(this).apply {
            text = "インデックス全消去"
            setOnClickListener { confirmClear() }
            root.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        statsView = TextView(this).apply {
            setTextColor(Color.parseColor("#63BA80")); textSize = 12f
        }
        root.addView(statsView)

        log = TextView(this).apply {
            setTextColor(Color.parseColor("#E8EAED"))
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(log)

        root.addView(label("検索テスト（BM25）"))
        queryInput = field("キーワードを入力", "")
        root.addView(queryInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        Button(this).apply {
            text = "検索"
            setOnClickListener { doSearch() }
            root.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        results = TextView(this).apply {
            setTextColor(Color.parseColor("#E8EAED"))
            textSize = 13f
            setTextIsSelectable(true)
        }

        val scroll = ScrollView(this).apply { addView(results) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun refresh() {
        signBtn.text = if (drive.isSignedIn) "サインアウト" else "サインイン"
        syncBtn.isEnabled = drive.isSignedIn
        val s = db.stats()
        statsView.text = "文書 ${s.docs} / チャンク ${s.chunks} / 語彙 ${s.terms}"
    }

    private fun save() {
        drive.clientId = clientInput.text.toString()
        drive.folder = folderInput.text.toString()
    }

    // ---------------------------------------------------------- サインイン

    private fun toggleSignIn() {
        if (drive.isSignedIn) {
            drive.signOut()
            log.text = "サインアウトしました"
            refresh()
            return
        }
        save()
        if (drive.clientId.isEmpty()) {
            log.text = "クライアントIDを入力してください"
            return
        }

        signBtn.isEnabled = false
        log.text = "デバイスコードを取得中…"

        thread {
            try {
                val dc = drive.startDeviceCode()
                ui.post {
                    log.text = "コード: ${dc.userCode}\n" +
                            "${dc.verificationUri} を開いて入力してください\n" +
                            "（コードはクリップボードにコピー済み）"
                    copy(dc.userCode)
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUri)))
                    } catch (_: Exception) {}
                }
                drive.pollForToken(dc) { sec ->
                    ui.post { log.append("\n承認待ち… ${sec}秒") }
                }
                ui.post {
                    log.text = "サインインしました"
                    signBtn.isEnabled = true
                    refresh()
                }
            } catch (e: Exception) {
                ui.post {
                    log.text = "サインイン失敗: ${e.message}"
                    signBtn.isEnabled = true
                }
            }
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("code", text))
    }

    // -------------------------------------------------------------- 同期

    private fun doSync() {
        save()
        syncBtn.isEnabled = false
        log.text = "同期を開始します…"

        thread {
            try {
                val r = drive.sync { msg -> ui.post { log.text = msg } }
                ui.post {
                    log.text = buildString {
                        append(if (r.fullSync) "初回同期 完了\n" else "差分同期 完了\n")
                        append("追加 ${r.added} / 更新 ${r.updated} / 削除 ${r.deleted}")
                        if (r.skipped > 0) append(" / 対象外 ${r.skipped}")
                    }
                    syncBtn.isEnabled = true
                    refresh()
                }
            } catch (e: Exception) {
                ui.post {
                    log.text = "同期失敗: ${e.message}"
                    syncBtn.isEnabled = true
                }
            }
        }
    }

    private fun confirmClear() {
        if (log.text.startsWith("もう一度押すと")) {
            db.clearAll()
            log.text = "インデックスを消去しました"
            refresh()
        } else {
            log.text = "もう一度押すと全消去します（次回は初回同期になります）"
        }
    }

    // -------------------------------------------------------------- 検索

    private fun doSearch() {
        val q = queryInput.text.toString()
        if (q.isBlank()) return
        results.text = "検索中…"
        thread {
            val t0 = System.currentTimeMillis()
            val hits = db.search(q, limit = 5)
            val ms = System.currentTimeMillis() - t0
            ui.post {
                results.text = if (hits.isEmpty()) "該当なし (${ms}ms)"
                else buildString {
                    append("${hits.size}件 / ${ms}ms\n\n")
                    for ((i, h) in hits.withIndex()) {
                        append("[${i + 1}] ${h.name}  score=%.2f\n".format(h.score))
                        append(h.text.take(300))
                        append("\n\n")
                    }
                }
            }
        }
    }
}
