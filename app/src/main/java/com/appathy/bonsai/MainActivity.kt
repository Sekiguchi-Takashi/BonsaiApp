package com.appathy.bonsai

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import java.io.File
import java.io.FileOutputStream
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
        private const val REQ_PICK = 1001
        private const val N_CTX = 1024
        private const val MAX_TOKENS = 512
        private const val SYSTEM_PROMPT =
            "あなたは日本語で応答するアシスタントです。" +
            "回答は必ず日本語だけで書いてください。" +
            "中国語・簡体字・英語は使わないでください。"
        private const val UI_INTERVAL_MS = 60L
    }

    private val llama = LlamaBridge()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var pickBtn: Button
    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var runBtn: Button

    @Volatile private var generating = false

    private fun modelFile() = File(filesDir, "model.gguf")

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

        pickBtn = Button(this).apply {
            text = "モデルを選択 (.gguf)"
            setOnClickListener { pickModel() }
        }
        root.addView(pickBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        input = EditText(this).apply {
            hint = "プロンプトを入力"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#5F6368"))
            setText("日本語で自己紹介してください。")
        }
        root.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

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

    private fun pickModel() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(Intent.createChooser(i, "model.gguf を選択"), REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        data?.data?.let { importModel(it) }
    }

    private fun importModel(uri: Uri) {
        pickBtn.isEnabled = false
        runBtn.isEnabled = false
        llama.free()

        thread {
            val tmp = File(filesDir, "model.gguf.tmp")
            try {
                contentResolver.openInputStream(uri)!!.use { ins ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(1 shl 20)
                        var total = 0L
                        var lastPost = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total - lastPost >= 8L * 1024 * 1024) {
                                lastPost = total
                                val mb = total / 1024 / 1024
                                ui.post { status.text = "コピー中… ${mb} MB" }
                            }
                        }
                    }
                }
                val dst = modelFile()
                if (dst.exists()) dst.delete()
                if (!tmp.renameTo(dst)) throw IllegalStateException("rename failed")

                ui.post {
                    pickBtn.isEnabled = true
                    loadModel()
                }
            } catch (e: Exception) {
                tmp.delete()
                ui.post {
                    status.text = "取込失敗: ${e.message}"
                    pickBtn.isEnabled = true
                }
            }
        }
    }

    // ---------- モデル読込 ----------

    private fun loadModel() {
        val f = modelFile()
        if (!f.exists()) {
            status.text = "モデル未取込。上のボタンから .gguf を選択してください"
            pickBtn.text = "モデルを選択 (.gguf)"
            return
        }
        pickBtn.text = "モデルを再選択"
        val sizeMb = f.length() / 1024 / 1024
        status.text = "読込中… ${sizeMb}MB / 空きRAM ${freeRamMb()}MB"

        thread {
            val t0 = System.currentTimeMillis()
            val ok = llama.load(f.absolutePath, nCtx = N_CTX, nThreads = threadCount())
            val ms = System.currentTimeMillis() - t0
            ui.post {
                status.text = if (ok)
                    "読込完了 ${ms}ms / threads=${threadCount()} / 空きRAM ${freeRamMb()}MB"
                else
                    "読込失敗。RAM不足の可能性（空き ${freeRamMb()}MB）"
                runBtn.isEnabled = ok
            }
        }
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

    private fun generate() {
        val prompt = input.text.toString()
        generating = true
        runBtn.text = "停止"
        pickBtn.isEnabled = false
        output.text = ""

        val sb = StringBuilder()
        var n = 0
        val t0 = System.currentTimeMillis()

        // UI更新の間引き
        var lastUi = 0L
        var dirty = false

        thread {
            llama.generate(SYSTEM_PROMPT, prompt, MAX_TOKENS,
                object : LlamaBridge.TokenCallback {
                    override fun onToken(piece: String) {
                        synchronized(sb) { sb.append(piece) }
                        n++
                        val now = System.currentTimeMillis()
                        if (now - lastUi >= UI_INTERVAL_MS) {
                            lastUi = now
                            dirty = false
                            val text = synchronized(sb) { sb.toString() }
                            ui.post { output.text = strip(text) }
                        } else {
                            dirty = true
                        }
                    }
                })

            val sec = (System.currentTimeMillis() - t0) / 1000.0
            val text = synchronized(sb) { sb.toString() }
            ui.post {
                if (dirty) output.text = strip(text)
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
        llama.free()
        super.onDestroy()
    }
}
