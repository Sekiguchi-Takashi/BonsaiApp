package com.appathy.bonsai.rag

/**
 * ファイル先頭の `---` で囲まれた frontmatter を解析する。
 *
 * 配布キットが生成する資料は先頭にメタデータを持つ:
 *
 *   ---
 *   type: app_doc
 *   app_name: KakeiApp
 *   ---
 *   （本文）
 *
 * YAML の完全実装はしない。`key: value` の1行1項目だけ扱えれば十分。
 * frontmatter が無いファイル（従来の素の .md/.txt）もそのまま通す。
 */
object Frontmatter {

    /**
     * @return メタデータのMapと、frontmatterを除いた本文のペア。
     *         frontmatter が無ければ Map は空、本文は入力そのまま。
     */
    fun split(text: String): Pair<Map<String, String>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return Pair(emptyMap(), text)

        val end = normalized.indexOf("\n---", 4)
        if (end < 0) return Pair(emptyMap(), text)

        val block = normalized.substring(4, end)
        val body = normalized.substring(end + 4).trimStart('\n')

        val meta = LinkedHashMap<String, String>()
        for (line in block.split("\n")) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val i = t.indexOf(':')
            if (i <= 0) continue
            val k = t.substring(0, i).trim().lowercase()
            val v = t.substring(i + 1).trim().trim('"', '\'')
            if (k.isNotEmpty()) meta[k] = v
        }
        return Pair(meta, body)
    }
}
