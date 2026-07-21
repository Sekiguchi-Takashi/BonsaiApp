package com.appathy.bonsai

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import com.appathy.bonsai.rag.FolderSync
import com.appathy.bonsai.rag.RagDb
import kotlin.concurrent.thread

/**
 * RAG設定画面（v0.8）。
 *
 * クラウド認証はアプリに持たない。Termux の rclone が端末内フォルダへ同期し、
 * ここではそのフォルダを選んでインデックスするだけ。
 */
class RagActivity : Activity() {

    companion object {
        private const val REQ_TREE = 3001
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var db: RagDb
    private lateinit var sync: FolderSync

    private lateinit var folderView: TextView
    private lateinit var syncBtn: Button
    private lateinit var log: TextView
    private lateinit var queryInput: EditText
    private lateinit var results: TextView
    private lateinit var statsView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = RagDb(this)
        sync = FolderSync(this, db)

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

        root.addView(label("同期フォルダ（rclone の出力先を選ぶ）"))

        folderView = TextView(this).apply {
            setTextColor(Color.parseColor("#63BA80"))
            textSize = 14f
        }
        root.addView(folderView)

        Button(this).apply {
            text = "フォルダを選択"
            setOnClickListener { pickFolder() }
            root.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        syncBtn = Button(this).apply {
            text = "インデックス更新"
            setOnClickListener { doSync() }
            root.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

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
        queryInput = EditText(this).apply {
            hint = "キーワードを入力"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#5F6368"))
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 14f
        }
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
        root.addView(results, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun refresh() {
        folderView.text = sync.treeUri?.let { sync.folderLabel() } ?: "未選択"
        syncBtn.isEnabled = sync.treeUri != null
        val s = db.stats()
        statsView.text = "文書 ${s.docs} / チャンク ${s.chunks} / 語彙 ${s.terms}"
    }

    // ------------------------------------------------------------ フォルダ

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                     Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, REQ_TREE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE || resultCode != RESULT_OK) return
        data?.data?.let {
            try {
                sync.persist(it)
                log.text = "フォルダを設定しました"
            } catch (e: Exception) {
                log.text = "設定失敗: ${e.message}"
            }
            refresh()
        }
    }

    // ------------------------------------------------------------ 同期

    private fun doSync() {
        syncBtn.isEnabled = false
        log.text = "開始します…"
        thread {
            try {
                val r = sync.sync { msg -> ui.post { log.text = msg } }
                ui.post {
                    log.text = "完了\n追加 ${r.added} / 更新 ${r.updated} / " +
                            "削除 ${r.deleted} / 変更なし ${r.unchanged}" +
                            if (r.skipped > 0) " / 対象外 ${r.skipped}" else ""
                    syncBtn.isEnabled = true
                    refresh()
                }
            } catch (e: Exception) {
                ui.post {
                    log.text = "失敗: ${e.message}"
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
            log.text = "もう一度押すと全消去します"
        }
    }

    // ------------------------------------------------------------ 検索

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
