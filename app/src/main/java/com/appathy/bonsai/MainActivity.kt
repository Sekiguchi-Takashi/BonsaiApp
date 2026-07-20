package com.appathy.bonsai

import android.app.Activity
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
 * v0.2
 * Android 11+ のスコープドストレージにより /sdcard/Android/data/ が外部から
 * 触れないため、SAF（ACTION_OPEN_DOCUMENT）でモデルを取り込み、
 * アプリ内部ストレージ（filesDir）へコピーして使う。
 * 権限宣言は一切不要。
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_PICK = 1001
    }

    private val llama = LlamaBridge()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var pickBtn: Button
    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var runBtn: Button

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
            setOnClickListener { generate() }
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
                        val buf = ByteArray(1 shl 20)   // 1MB
                        var total = 0L
                        var lastPost = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            // 8MBごとにUI更新（毎回postすると重い）
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
                    status.text = "取込完了。読込します…"
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
        status.text = "モデル読込中… (${f.length() / 1024 / 1024} MB)"
        thread {
            val t0 = System.currentTimeMillis()
            val ok = llama.load(f.absolutePath, nCtx = 2048, nThreads = threadCount())
            val ms = System.currentTimeMillis() - t0
            ui.post {
                status.text = if (ok) "読込完了 ${ms}ms / threads=${threadCount()}"
                              else "読込失敗（logcat を確認）"
                runBtn.isEnabled = ok
            }
        }
    }

    private fun threadCount() =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    // ---------- 生成 ----------

    private fun generate() {
        val prompt = input.text.toString()
        runBtn.isEnabled = false
        pickBtn.isEnabled = false
        output.text = ""
        val sb = StringBuilder()
        var n = 0
        val t0 = System.currentTimeMillis()

        thread {
            llama.generate(prompt, maxTokens = 256, cb = object : LlamaBridge.TokenCallback {
                override fun onToken(piece: String) {
                    sb.append(piece); n++
                    ui.post { output.text = sb.toString() }
                }
            })
            val sec = (System.currentTimeMillis() - t0) / 1000.0
            ui.post {
                status.text = "%d tok / %.1fs = %.1f tok/s".format(n, sec, n / sec)
                runBtn.isEnabled = true
                pickBtn.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        llama.free()
        super.onDestroy()
    }
}
