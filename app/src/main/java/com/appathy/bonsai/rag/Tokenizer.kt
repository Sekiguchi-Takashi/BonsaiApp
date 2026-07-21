package com.appathy.bonsai.rag

/**
 * 形態素解析なしで日本語を扱うトークナイザ。
 *
 * 日本語は文字bigram、英数字は単語単位に切る。
 * MeCab等の辞書を持たずに済むので依存ゼロを維持できる。
 * 「東京都」→ ["東京","京都"] のように2文字ずつ重ねて切るため、
 * 未知語や複合語にも自然に対応できるのが利点。
 */
object Tokenizer {

    private const val MAX_TOKENS = 4096

    private fun isCjk(c: Char): Boolean {
        val v = c.code
        return (v in 0x3040..0x309F) ||      // ひらがな
               (v in 0x30A0..0x30FF) ||      // カタカナ
               (v in 0x4E00..0x9FFF) ||      // CJK統合漢字
               (v in 0x3400..0x4DBF) ||      // CJK拡張A
               (v in 0xFF66..0xFF9D) ||      // 半角カナ
               c == '々' || c == '〆' || c == 'ヶ'
    }

    private fun isWord(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
        c == '_' || (c in 'ａ'..'ｚ') || (c in 'Ａ'..'Ｚ')

    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val s = text.lowercase()
        var i = 0
        val n = s.length

        while (i < n && out.size < MAX_TOKENS) {
            val c = s[i]
            when {
                isWord(c) -> {
                    val start = i
                    while (i < n && isWord(s[i])) i++
                    out.add(s.substring(start, i))
                }
                isCjk(c) -> {
                    val start = i
                    while (i < n && isCjk(s[i])) i++
                    val run = s.substring(start, i)
                    if (run.length == 1) {
                        out.add(run)
                    } else {
                        for (j in 0 until run.length - 1) out.add(run.substring(j, j + 2))
                    }
                }
                else -> i++
            }
        }
        return out
    }

    /** 語 -> 出現回数 */
    fun termFreq(text: String): Map<String, Int> {
        val tf = HashMap<String, Int>()
        for (t in tokenize(text)) tf[t] = (tf[t] ?: 0) + 1
        return tf
    }
}
