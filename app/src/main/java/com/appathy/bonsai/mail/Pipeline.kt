package com.appathy.bonsai.mail

import android.content.Context
import android.util.Log
import com.appathy.bonsai.Engine
import com.appathy.bonsai.LlamaBridge
import com.appathy.bonsai.rag.RagDb

/**
 * フェーズ3の中核。
 *
 *   受信メール → BM25検索 → プロンプト組立 → Bonsai推論 → キューへ書き戻し
 *
 * n_ctx が 2048 しかないので、**文脈の予算管理が要**になる。
 * 資料を詰め込みすぎると質問本文が押し出され、回答が破綻する。
 */
class Pipeline(ctx: Context) {

    companion object {
        private const val TAG = "Pipeline"

        /** 検索で拾うチャンク数 */
        const val TOP_K = 3

        /**
         * 参考資料に使う最大文字数。
         * n_ctx=2048 のうち、質問文とシステム指示と回答生成分を差し引いた残り。
         * 日本語はおよそ1文字1トークン前後なので、ここを上げすぎると溢れる。
         */
        const val MAX_CONTEXT_CHARS = 900

        /** 質問として使うメール本文の最大文字数 */
        const val MAX_QUESTION_CHARS = 500

        const val MAX_ANSWER_TOKENS = 400

        private const val SYSTEM = """あなたは日本語で回答する業務アシスタントです。
以下のルールを厳守してください。
1. 回答は必ず日本語だけで書く。中国語・簡体字・英語は使わない。
2. 【参考資料】に書かれている内容を根拠に回答する。
3. 参考資料に答えが無い場合は、推測せず「参考資料に該当する記載がありません」と書く。
4. 簡潔に、要点を先に書く。"""
    }

    private val rag = RagDb(ctx.applicationContext)

    data class Outcome(val answer: String, val sources: List<String>, val ms: Long)

    /** 検索して、予算内に収めた参考資料テキストと出典名を返す */
    fun retrieve(query: String): Pair<String, List<String>> {
        val hits = try {
            rag.search(query, TOP_K)
        } catch (e: Exception) {
            Log.e(TAG, "search failed", e); emptyList()
        }

        val used = ArrayList<String>()
        val sources = ArrayList<String>()
        var budget = MAX_CONTEXT_CHARS
        for (h in hits) {
            if (budget <= 100) break
            val piece = h.text.take(budget)
            used.add("--- ${h.name} ---\n$piece")
            if (h.name !in sources) sources.add(h.name)
            budget -= piece.length
        }

        val context = if (used.isEmpty())
            "（該当する資料は見つかりませんでした）"
        else used.joinToString("\n\n")

        return Pair(context, sources)
    }

    /**
     * 汎用の RAG 応答。トップ画面からもメール処理からも使う。
     * @param searchQuery 検索に使う文字列
     * @param userBlock   LLM に渡す user メッセージ本体
     * @param onToken     ストリーミング表示が要る場合に渡す
     */
    fun answer(
        searchQuery: String,
        userBlock: (context: String) -> String,
        onToken: ((String) -> Unit)? = null
    ): Outcome {
        val t0 = System.currentTimeMillis()
        val (context, sources) = retrieve(searchQuery)

        val sb = StringBuilder()
        Engine.generate(
            listOf(
                LlamaBridge.Msg("system", SYSTEM),
                LlamaBridge.Msg("user", userBlock(context))
            ),
            LlamaBridge.Params(maxTokens = MAX_ANSWER_TOKENS, temperature = 0.3f),
            object : LlamaBridge.TokenCallback {
                override fun onToken(piece: String) {
                    sb.append(piece)
                    onToken?.invoke(piece)
                }
            }
        )

        return Outcome(clean(sb.toString()), sources, System.currentTimeMillis() - t0)
    }

    /**
     * 検索クエリを作る。
     * 件名は情報密度が高いので重視し、本文は先頭を使う
     * （メールは冒頭に用件が来ることが多く、末尾は署名で埋まるため）。
     */
    private fun buildQuery(item: MailQueue.Item): String =
        (item.subject + " " + item.subject + " " + item.body.take(300)).trim()

    fun process(item: MailQueue.Item): Outcome {
        val out = answer(
            searchQuery = buildQuery(item),
            userBlock = { context ->
                buildString {
                    append("【参考資料】\n").append(context).append("\n\n")
                    append("【受信メール】\n")
                    append("差出人: ").append(item.sender.take(80)).append('\n')
                    append("件名: ").append(item.subject.take(120)).append('\n')
                    append("本文:\n").append(item.body.take(MAX_QUESTION_CHARS)).append("\n\n")
                    append("上記メールへの回答案を作成してください。")
                }
            }
        )
        val withSources = if (out.sources.isEmpty()) out.answer
            else out.answer + "\n\n参照: " + out.sources.joinToString(", ")
        Log.i(TAG, "processed uid=${item.uid} in ${out.ms}ms, sources=${out.sources.size}")
        return out.copy(answer = withSources)
    }

    /** 表示用の後処理。think ブロックと Markdown 記法を落とす */
    private fun clean(raw: String): String {
        var s = Regex("(?s)<think>.*?</think>").replace(raw, "")
        val i = s.indexOf("<think>")
        if (i >= 0) s = s.substring(0, i)
        s = Regex("\\*\\*(.+?)\\*\\*").replace(s, "$1")
        s = Regex("(?m)^#{1,6}\\s*").replace(s, "")
        s = Regex("\n{3,}").replace(s, "\n\n")
        return s.trim()
    }
}
