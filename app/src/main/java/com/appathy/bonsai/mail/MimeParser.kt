package com.appathy.bonsai.mail

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * 最小限の MIME パーサ。外部ライブラリは使わない。
 *
 * 日本語メールで実際に必要になる範囲だけ実装している:
 *   - RFC2047 のヘッダデコード（=?UTF-8?B?...?= / =?ISO-2022-JP?B?...?=）
 *   - multipart から text/plain を選ぶ（無ければ text/html をタグ除去）
 *   - base64 / quoted-printable のデコード
 *   - UTF-8 / ISO-2022-JP / Shift_JIS の文字コード判定
 */
object MimeParser {

    data class Mail(
        val from: String,
        val subject: String,
        val date: String,
        val messageId: String,
        val body: String
    )

    fun parse(raw: String): Mail {
        val (headerBlock, bodyBlock) = splitOnce(raw)
        val headers = parseHeaders(headerBlock)

        val body = extractBody(
            headers["content-type"] ?: "text/plain",
            headers["content-transfer-encoding"] ?: "7bit",
            bodyBlock
        )

        return Mail(
            from = decodeHeader(headers["from"] ?: ""),
            subject = decodeHeader(headers["subject"] ?: "(件名なし)"),
            date = headers["date"] ?: "",
            messageId = headers["message-id"] ?: "",
            body = body.trim()
        )
    }

    // ------------------------------------------------------------- headers

    private fun splitOnce(raw: String): Pair<String, String> {
        val n = raw.replace("\r\n", "\n")
        val i = n.indexOf("\n\n")
        return if (i < 0) Pair(n, "") else Pair(n.substring(0, i), n.substring(i + 2))
    }

    private fun parseHeaders(block: String): Map<String, String> {
        val out = HashMap<String, String>()
        // 折り返し行（先頭が空白）を前の行に連結する
        val unfolded = block.replace(Regex("\n[ \t]+"), " ")
        for (line in unfolded.split("\n")) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            val k = line.substring(0, i).trim().lowercase()
            val v = line.substring(i + 1).trim()
            if (k !in out) out[k] = v
        }
        return out
    }

    /** RFC2047: =?charset?B|Q?text?= を復号する */
    fun decodeHeader(s: String): String {
        if (!s.contains("=?")) return s
        val re = Regex("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=")
        var out = s
        for (m in re.findAll(s)) {
            val charset = m.groupValues[1]
            val enc = m.groupValues[2].uppercase()
            val text = m.groupValues[3]
            val decoded = try {
                val bytes = if (enc == "B") Base64.decode(text, Base64.DEFAULT)
                            else decodeQp(text.replace('_', ' '))
                String(bytes, charsetOf(charset))
            } catch (e: Exception) { text }
            out = out.replace(m.value, decoded)
        }
        // エンコード語の間の空白は仕様上詰めてよい
        return out.replace(Regex("\\?=\\s+=\\?"), "").trim()
    }

    private fun charsetOf(name: String): Charset = try {
        Charset.forName(name.trim())
    } catch (e: Exception) {
        Charsets.UTF_8
    }

    // ---------------------------------------------------------------- body

    private fun extractBody(contentType: String, encoding: String, body: String): String {
        val ct = contentType.lowercase()

        if (ct.startsWith("multipart/")) {
            val boundary = Regex("boundary=\"?([^\";]+)\"?")
                .find(contentType)?.groupValues?.get(1) ?: return body
            val parts = splitParts(body, boundary)

            // text/plain を優先、無ければ text/html
            var html: String? = null
            for (p in parts) {
                val (h, b) = splitOnce(p)
                val heads = parseHeaders(h)
                val pct = (heads["content-type"] ?: "text/plain").lowercase()
                val penc = heads["content-transfer-encoding"] ?: "7bit"

                if (pct.startsWith("multipart/")) {
                    val inner = extractBody(heads["content-type"]!!, penc, b)
                    if (inner.isNotBlank()) return inner
                } else if (pct.startsWith("text/plain")) {
                    return decodeText(b, penc, pct)
                } else if (pct.startsWith("text/html") && html == null) {
                    html = decodeText(b, penc, pct)
                }
            }
            return html?.let { stripHtml(it) } ?: ""
        }

        val text = decodeText(body, encoding, ct)
        return if (ct.startsWith("text/html")) stripHtml(text) else text
    }

    private fun splitParts(body: String, boundary: String): List<String> {
        val marker = "--$boundary"
        return body.split(marker)
            .drop(1)
            .filter { !it.trimStart().startsWith("--") }
            .map { it.trimStart('\n') }
    }

    private fun decodeText(s: String, encoding: String, contentType: String): String {
        val cs = Regex("charset=\"?([^\";\\s]+)\"?")
            .find(contentType)?.groupValues?.get(1) ?: "UTF-8"
        val bytes = when (encoding.trim().lowercase()) {
            "base64" -> try { Base64.decode(s, Base64.DEFAULT) } catch (e: Exception) {
                s.toByteArray(Charsets.UTF_8) }
            "quoted-printable" -> decodeQp(s)
            else -> s.toByteArray(Charsets.ISO_8859_1)
        }
        return String(bytes, charsetOf(cs))
    }

    private fun decodeQp(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        val t = s.replace("=\r\n", "").replace("=\n", "")   // ソフト改行
        while (i < t.length) {
            val c = t[i]
            if (c == '=' && i + 2 < t.length) {
                val hex = t.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (v != null) { out.write(v); i += 3; continue }
            }
            out.write(c.code)
            i++
        }
        return out.toByteArray()
    }

    private fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p>"), "\n\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&amp;", "&").replace("&quot;", "\"")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
