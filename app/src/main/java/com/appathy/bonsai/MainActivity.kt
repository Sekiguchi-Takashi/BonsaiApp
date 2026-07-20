package com.appathy.bonsai

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import java.io.File
import kotlin.concurrent.thread

/**
 * XMLなしのプログラマティックUI。
 * モデルは getExternalFilesDir(null)/model.gguf を読む。
 */
class MainActivity : Activity() {

    private val llama = LlamaBridge()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var runBtn: Button

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
        }
        root.addView(ScrollView(this).apply { addView(output) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)
        loadModel()
    }

    private fun modelFile() = File(getExternalFilesDir(null), "model.gguf")

    private fun loadModel() {
        val f = modelFile()
        if (!f.exists()) {
            status.text = "モデル未配置:\n${f.absolutePath}"
            return
        }
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

    private fun generate() {
        val prompt = input.text.toString()
        runBtn.isEnabled = false
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
            }
        }
    }

    override fun onDestroy() {
        llama.free()
        super.onDestroy()
    }
}
