package com.appathy.bonsai

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import com.appathy.bonsai.mail.Pipeline
import kotlin.concurrent.thread

/**
 * v0.3
 *  - UI更新を 60ms 間引き（1トークンごとの全文字列再設定をやめる）
 *  - 停止ボタン
 *  - Qwen3 系の <think> ブロックを表示から除去
 *  - 空きRAM表示
 *  - n_ctx を 1024 に（8B / 低RAM端末向け）
 */
class MainActivity : Activity() {

    companion object {
        /** サービスがモデル解放の可否を判断するために参照する */
        @Volatile var isForeground = false

        private const val REQ_MODEL_TREE = 1002
        private const val N_CTX = 2048   // RAG の文脈を積むため v0.7 で 2048 に戻した
        private const val MAX_TOKENS = 512
        private const val SYSTEM_PROMPT =
            "あなたは日本語で応答するアシスタントです。" +
            "回答は必ず日本語だけで書いてください。" +
            "中国語・簡体字・英語は使わないでください。"
        private const val UI_INTERVAL_MS = 60L
        private const val SERVER_PORT = 8080
        // true にすると LAN の他端末からも到達できるが、認証が無い点に注意
        private const val BIND_ALL = false
    }

    private val llama get() = Engine.bridge
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var pickBtn: Button
    private lateinit var serverBtn: Button
    private lateinit var ragBtn: Button
    private lateinit var mailBtn: Button
    private lateinit var editorBtn: Button
    private lateinit var ragToggle: Button
    private var useRag = true
    private lateinit var tagBtn: Button
    private var selectedTag: String = ""
    private val pipeline by lazy { Pipeline(applicationContext) }
    private lateinit var serverInfo: TextView
    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var runBtn: Button

    @Volatile private var generating = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#101014"))
        }

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 12f
            text = "初期化中…"
        }
        root.addView(status)

        // v1.7: ボタンの縦積みでキーボードに入力欄が隠れていたため横並びにした。
        // v1.8: 「使い方」を足して 3列×2行 にする。
        fun navRow() = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            root.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val row1 = navRow()
        val row2 = navRow()
        fun nav(row: LinearLayout, label: String, onTap: () -> Unit) = Button(this).apply {
            text = label
            textSize = 11f
            setPadding(2, 0, 2, 0)
            setOnClickListener { onTap() }
            row.addView(this, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        pickBtn = nav(row1, "モデル") { chooseModel() }
        serverBtn = nav(row1, "サーバー") { toggleServer() }
        nav(row1, "使い方") {
            startActivity(Intent(this@MainActivity, ManualActivity::class.java)) }

        ragBtn = nav(row2, "RAG設定") {
            startActivity(Intent(this@MainActivity, RagActivity::class.java)) }
        editorBtn = nav(row2, "エディタ") {
            startActivity(Intent(this@MainActivity, EditorActivity::class.java)) }
        mailBtn = nav(row2, "メール") {
            startActivity(Intent(this@MainActivity, MailActivity::class.java)) }


        serverInfo = TextView(this).apply {
            setTextColor(Color.parseColor("#63BA80"))
            textSize = 12f
            setTextIsSelectable(true)
            text = ""
        }
        root.addView(serverInfo)

        input = EditText(this).apply {
            hint = "プロンプトを入力"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#5F6368"))
            setText("日本語で自己紹介してください。")
        }
        root.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val ragRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ragToggle = Button(this).apply {
            textSize = 11f
            setPadding(2, 0, 2, 0)
            setOnClickListener {
                useRag = !useRag
                updateRagToggle()
            }
        }
        ragRow.addView(ragToggle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        tagBtn = Button(this).apply {
            textSize = 11f
            setPadding(2, 0, 2, 0)
            setOnClickListener { pickTag() }
        }
        ragRow.addView(tagBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(ragRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        runBtn = Button(this).apply {
            text = "生成"
            isEnabled = false
            setOnClickListener {
                if (generating) llama.stop() else generate()
            }
        }
        root.addView(runBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        output = TextView(this).apply {
            setTextColor(Color.parseColor("#E8EAED"))
            textSize = 15f
            gravity = Gravity.TOP
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(output) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        updateRagToggle()
        setContentView(root)
        loadModel()
    }

    // ---------- メモリ ----------

    private fun freeRamMb(): Long {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem / 1024 / 1024
    }

    // ---------- モデル取込（SAF） ----------

    private val modelStore by lazy { ModelStore(this) }

    // ---------- モデルフォルダ（v1.9: コピーせず直接読む） ----------

    private fun pickModelFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                     Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, REQ_MODEL_TREE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_MODEL_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            modelStore.persist(uri)
            loadModel()
        } catch (e: Exception) {
            alert("フォルダの設定に失敗しました", e.message ?: "")
        }
    }

    /** モデルボタン。複数あれば選択、1つなら即読込、無ければエラー表示 */
    private fun chooseModel() {
        if (!modelStore.isConfigured) { pickModelFolder(); return }
        val list = modelStore.listModels()
        when {
            list.isEmpty() -> noModelDialog()
            list.size == 1 -> loadModel()
            else -> {
                val labels = list.map { "${it.name}  (${it.size / 1024 / 1024}MB)" }
                AlertDialog.Builder(this)
                    .setTitle("モデルを選択")
                    .setItems(labels.toTypedArray()) { _, w ->
                        modelStore.fileName = list[w].name
                        loadModel()
                    }
                    .setNeutralButton("フォルダを変更") { _, _ -> pickModelFolder() }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
        }
    }

    private fun alert(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title).setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    /** 起動時にモデルが見つからないときのポップアップ */
    private fun noModelDialog() {
        val where = if (modelStore.isConfigured)
            "フォルダ「${modelStore.folderLabel()}」に .gguf がありません。"
        else
            "モデルフォルダが未設定です。"
        AlertDialog.Builder(this)
            .setTitle("モデルが見つかりません")
            .setMessage(where + "\n\n.gguf を置いたフォルダを選んでください。" +
                        "\nファイルはコピーされず、その場所のまま読み込まれます。")
            .setPositiveButton("フォルダを選ぶ") { _, _ -> pickModelFolder() }
            .setNegativeButton("あとで", null)
            .setCancelable(false)
            .show()
    }

    // ---------- モデル読込 ----------

    private fun loadModel() {
        val entry = if (modelStore.isConfigured) modelStore.preferred() else null
        if (entry == null) {
            status.text = "モデル未設定"
            pickBtn.text = "モデル"
            runBtn.isEnabled = false
            noModelDialog()
            return
        }

        pickBtn.text = "モデル変更"
        status.text = "読込中… ${entry.name} (${entry.size / 1024 / 1024}MB)"

        thread {
            val t0 = System.currentTimeMillis()
            val ok = try {
                val path = modelStore.openPath(entry)
                llama.load(path, nCtx = N_CTX, nThreads = threadCount())
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "load failed", e)
                false
            }
            val ms = System.currentTimeMillis() - t0
            ui.post {
                if (ok) {
                    status.text = "読込完了 ${ms}ms / ${entry.name} / " +
                            "threads=${threadCount()} / 空きRAM ${freeRamMb()}MB"
                } else {
                    modelStore.release()
                    status.text = "読込失敗: ${entry.name}"
                    alert("モデルを読み込めませんでした",
                        "ファイル: ${entry.name}\n\n" +
                        "・ファイルが壊れていないか\n" +
                        "・空きRAMが足りているか（現在 ${freeRamMb()}MB）\n" +
                        "を確認してください。")
                }
                runBtn.isEnabled = ok
                serverBtn.isEnabled = ok
                refreshServerInfo()
            }
        }
    }

    // ---------- OpenAI互換サーバー ----------

    private fun toggleServer() {
        if (ServerService.serverWanted(this)) {
            ServerService.stopServer(this)
        } else {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
            }
            ServerService.startServer(this, SERVER_PORT, BIND_ALL)
        }
        // サービス起動は非同期なので少し待ってから反映
        ui.postDelayed({ refreshServerInfo() }, 400)
    }

    private fun refreshServerInfo() {
        val on = ServerService.serverWanted(this)
        serverBtn.text = if (on) "停止" else "サーバー"
        serverInfo.text = if (on)
            "http://127.0.0.1:$SERVER_PORT/v1\nOpenAI互換 / api_key は任意の文字列で可"
        else ""
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        if (::serverBtn.isInitialized) refreshServerInfo()
    }

    override fun onPause() {
        isForeground = false
        super.onPause()
    }

    private fun threadCount() =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    // ---------- 生成 ----------

    /**
     * 表示用の整形。
     *  - Qwen3 系が出す <think>…</think> を除去
     *  - Markdown 記法（**強調** / ### 見出し / --- 罫線）を平文に落とす
     */
    private fun strip(raw: String): String {
        var s = Regex("(?s)<think>.*?</think>").replace(raw, "")
        val idx = s.indexOf("<think>")
        if (idx >= 0) s = s.substring(0, idx)          // 閉じタグ未到達の途中経過

        s = Regex("\\*\\*(.+?)\\*\\*").replace(s, "\$1")   // **強調**
        s = Regex("(?m)^#{1,6}\\s*").replace(s, "")             // ### 見出し
        s = Regex("(?m)^\\s*[-*_]{3,}\\s*$").replace(s, "")   // --- 罫線
        s = Regex("\\n{3,}").replace(s, "\n\n")               // 余分な空行

        return s.trimStart()
    }

    private fun updateRagToggle() {
        ragToggle.text = if (useRag) "RAG: ON" else "RAG: OFF"
        tagBtn.isEnabled = useRag
        tagBtn.text = when {
            !useRag -> "（タグ不要）"
            selectedTag.isEmpty() -> "タグ未選択 ▼"
            else -> "$selectedTag ▼"
        }
    }

    /**
     * タグ選択。資料が増えると、タグを指定しないと別アプリの資料が混ざるため、
     * RAG参照 ON のときは必ずタグを選ばせる。
     */
    private fun pickTag() {
        val tags = pipeline.tags()
        if (tags.isEmpty()) {
            status.text = "タグがありません。RAG設定でインデックスを作成してください"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("資料タグを選択")
            .setItems(tags.toTypedArray()) { _, which ->
                selectedTag = tags[which]
                updateRagToggle()
                status.text = "タグ「$selectedTag」の資料だけを検索します"
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun generate() {
        val prompt = input.text.toString()

        // RAG参照 ON でタグ未選択なら検索できない（仕様）
        if (useRag && selectedTag.isEmpty()) {
            status.text = "タグを選択してください（右のボタン）"
            pickTag()
            return
        }

        generating = true
        runBtn.text = "停止"
        pickBtn.isEnabled = false
        output.text = ""

        val sb = StringBuilder()
        var n = 0
        val t0 = System.currentTimeMillis()
        var lastUi = 0L
        var dirty = false

        fun push(piece: String) {
            synchronized(sb) { sb.append(piece) }
            n++
            val now = System.currentTimeMillis()
            if (now - lastUi >= UI_INTERVAL_MS) {
                lastUi = now; dirty = false
                val text = synchronized(sb) { sb.toString() }
                ui.post { output.text = strip(text) }
            } else dirty = true
        }

        thread {
            var sources = emptyList<String>()
            try {
                if (useRag) {
                    ui.post { status.text = "資料を検索中…" }
                    val out = pipeline.answer(
                        searchQuery = prompt,
                        userBlock = { context ->
                            "【参考資料】\n" + context + "\n\n【質問】\n" + prompt +
                            "\n\n参考資料に基づいて回答してください。"
                        },
                        onToken = { push(it) },
                        tag = selectedTag
                    )
                    sources = out.sources
                } else {
                    Engine.generate(
                        listOf(
                            LlamaBridge.Msg("system", SYSTEM_PROMPT),
                            LlamaBridge.Msg("user", prompt)
                        ),
                        LlamaBridge.Params(maxTokens = MAX_TOKENS),
                        object : LlamaBridge.TokenCallback {
                            override fun onToken(piece: String) { push(piece) }
                        })
                }
            } catch (e: Exception) {
                ui.post { output.text = "エラー: " + e.message }
            }

            val sec = (System.currentTimeMillis() - t0) / 1000.0
            val text = synchronized(sb) { sb.toString() }
            ui.post {
                if (dirty) output.text = strip(text)
                if (useRag) {
                    output.append(
                        if (sources.isEmpty()) "\n\n（参照した資料なし）"
                        else "\n\n参照: " + sources.joinToString(", "))
                }
                status.text = "%d tok / %.1fs = %.2f tok/s / 空きRAM %dMB"
                    .format(n, sec, if (sec > 0) n / sec else 0.0, freeRamMb())
                generating = false
                runBtn.text = "生成"
                pickBtn.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        llama.stop()
        // サーバー稼働中は他アプリが使うのでモデルを解放しない
        if (!ServerService.serverWanted(this) && !ServerService.mailWanted(this)) {
            llama.free()
            modelStore.release()   // モデル解放後でないと記述子が無効になる
        }
        super.onDestroy()
    }
}
