package com.appathy.bonsai

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import com.appathy.bonsai.rag.FolderSync
import com.appathy.bonsai.rag.RagDb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * RAG資料エディタ（v1.5 / フェーズ4-a）。
 *
 * アプリ内で資料を書き、RAGフォルダへ直接保存し、その場で再インデックスする。
 * 配布キットと同じ frontmatter 形式のテンプレートを内蔵している。
 *
 * 保存先は RAG設定で選んだフォルダそのもの。書き込みには WRITE 権限が要るため、
 * v1.4 以前にフォルダを選んでいた場合は一度選び直す必要がある。
 */
class EditorActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var db: RagDb
    private lateinit var sync: FolderSync

    private lateinit var nameInput: EditText
    private lateinit var body: EditText
    private lateinit var status: TextView

    private var pendingTemplate: String? = null

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).format(Date())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = RagDb(this)
        sync = FolderSync(this, db)

        val d = resources.displayMetrics.density
        val pad = (12 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#101014"))
        }

        // ---- ファイル名 ----
        nameInput = EditText(this).apply {
            hint = "ファイル名（例: app_KakeiApp.md）"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#5F6368"))
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 14f
        }
        root.addView(nameInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ---- ボタン列1: テンプレート ----
        val tmplRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tmplBtn(label: String, maker: () -> Pair<String, String>) = Button(this).apply {
            text = label
            textSize = 12f
            setOnClickListener { insertTemplate(maker) }
            tmplRow.addView(this, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        tmplBtn("アプリ資料") { templateAppDoc() }
        tmplBtn("キャラ設定") { templateCharacter() }
        tmplBtn("フリー") { templateFree() }
        root.addView(tmplRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ---- ボタン列2: 開く / 保存 ----
        val opRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Button(this).apply {
            text = "開く"
            textSize = 12f
            setOnClickListener { openPicker() }
            opRow.addView(this, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        Button(this).apply {
            text = "保存して反映"
            textSize = 12f
            setOnClickListener { save() }
            opRow.addView(this, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        root.addView(opRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#63BA80"))
            textSize = 12f
            text = if (sync.treeUri == null)
                "先に RAG設定 でフォルダを選んでください"
            else "保存先: ${sync.folderLabel()}"
        }
        root.addView(status)

        // ---- 本文 ----
        body = EditText(this).apply {
            setTextColor(Color.parseColor("#E8EAED"))
            setHintTextColor(Color.parseColor("#5F6368"))
            hint = "上のテンプレートボタンから書式を挿入できます"
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(false)
        }
        root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    // ------------------------------------------------------ テンプレート

    private fun insertTemplate(maker: () -> Pair<String, String>) {
        if (body.text.isNotBlank() && pendingTemplate != "confirm") {
            pendingTemplate = "confirm"
            status.text = "本文が消えます。よければもう一度同じボタンを押してください"
            return
        }
        pendingTemplate = null
        val (suggestedName, content) = maker()
        if (nameInput.text.isBlank()) nameInput.setText(suggestedName)
        body.setText(content)
        status.text = "テンプレートを挿入しました"
    }

    private fun templateAppDoc(): Pair<String, String> = Pair(
        "app_アプリ名.md",
        """---
type: app_doc
app_name: 
app_label: 
category: app-android
audience: both
updated: ${today()}
source: 手入力（Bonsai資料エディタ）
---

# ［表示名］（［app_name］）

## 概要


## 主な機能
- 

## 使い方（QA）

### Q. 
A. 

### Q. 
A. 

## 制限・既知の注意点

### Q. 
A. 

## 開発者向け構成情報

- ビルド構成: 
- 主要ファイル: 
- 外部依存: 
- 既知の落とし穴: 
- リポジトリ: 
"""
    )

    private fun templateCharacter(): Pair<String, String> = Pair(
        "char_作品名_キャラ名.md",
        """---
type: character
story: 
character_name: 
character_role: 
canon: full
updated: ${today()}
source: 手入力（Bonsai資料エディタ）
---

# ［キャラ名］ インタビュー

## プロフィール
- 名前: 
- 役割: 
- 一言で言うと: 

## 基本情報（QA）

### Q. 自己紹介をお願いします
A. 

### Q. 趣味は何ですか
A. 

### Q. 特技を教えてください
A. 

### Q. 好きなもの・苦手なものは
A. 

## 背景・過去（QA）

### Q. どんな生い立ちですか
A. 

### Q. 物語の中での目的は
A. 

## 人間関係（QA）

### Q. ［相手の名前］とはどんな関係ですか
A. 

## 性格・話し方

- 性格: 
- 口調の特徴: 
- 決め台詞: 
"""
    )

    private fun templateFree(): Pair<String, String> = Pair(
        "memo_${today().replace("-", "")}.md",
        """---
type: note
updated: ${today()}
source: 手入力（Bonsai資料エディタ）
---

# ［タイトル：検索されそうな言葉を入れる］

## ［小見出し］

質問: 
回答: 
"""
    )

    // ------------------------------------------------------ 開く

    private fun openPicker() {
        if (sync.treeUri == null) {
            status.text = "先に RAG設定 でフォルダを選んでください"
            return
        }
        status.text = "一覧を取得中…"
        thread {
            val files = try { sync.listTextFiles() } catch (e: Exception) {
                ui.post { status.text = "一覧取得失敗: ${e.message}" }; return@thread
            }
            ui.post {
                if (files.isEmpty()) {
                    status.text = "フォルダにファイルがありません"
                    return@post
                }
                status.text = "保存先: ${sync.folderLabel()}"
                val names = files.map { it.name }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("開くファイルを選択")
                    .setItems(names) { _, which -> load(files[which]) }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
        }
    }

    private fun load(f: FolderSync.FileEntry) {
        status.text = "読込中: ${f.name}"
        thread {
            try {
                val text = sync.readFile(f.docId)
                ui.post {
                    nameInput.setText(f.name)
                    body.setText(text)
                    status.text = "読込完了: ${f.name}"
                }
            } catch (e: Exception) {
                ui.post { status.text = "読込失敗: ${e.message}" }
            }
        }
    }

    // ------------------------------------------------------ 保存

    private fun normalizeName(raw: String): String? {
        var n = raw.trim()
        if (n.isEmpty()) return null
        if (n.contains('/') || n.contains('\\') || n.contains("..")) return null
        val ext = n.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("md", "txt", "markdown", "text")) n = "$n.md"
        return n
    }

    private fun save() {
        val name = normalizeName(nameInput.text.toString())
        if (name == null) {
            status.text = "ファイル名が不正です（/ や .. は使えません）"
            return
        }
        if (body.text.isBlank()) {
            status.text = "本文が空です"
            return
        }
        if (sync.treeUri == null) {
            status.text = "先に RAG設定 でフォルダを選んでください"
            return
        }

        status.text = "保存中…"
        thread {
            try {
                sync.writeFile(name, body.text.toString())
            } catch (e: SecurityException) {
                ui.post {
                    status.text = "書き込み権限がありません。RAG設定で" +
                            "フォルダを選び直してください（v1.5から読み書き権限が必要）"
                }
                return@thread
            } catch (e: Exception) {
                ui.post { status.text = "保存失敗: ${e.message}" }
                return@thread
            }

            ui.post { status.text = "保存完了。インデックスを更新中…" }
            try {
                val r = sync.sync { }
                ui.post {
                    status.text = "保存・反映完了: $name" +
                            "（追加${r.added} 更新${r.updated} 変更なし${r.unchanged}）"
                }
            } catch (e: Exception) {
                ui.post {
                    status.text = "保存はできましたがインデックス更新に失敗: ${e.message}"
                }
            }
        }
    }
}
