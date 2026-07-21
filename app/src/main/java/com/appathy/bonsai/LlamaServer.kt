package com.appathy.bonsai

import android.util.Log
import org.json.JSONArray
import com.appathy.bonsai.mail.Pipeline
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * OpenAI 互換の最小 HTTP サーバー。
 *
 * 外部ライブラリはゼロ。ServerSocket と、Android 標準の org.json だけで書いてある
 * （org.json はプラットフォーム同梱なので依存には数えない）。
 *
 * 対応エンドポイント:
 *   GET  /v1/models
 *   POST /v1/chat/completions   （stream: true で SSE）
 *   POST /v1/completions        （prompt を user メッセージ1件に変換）
 *   GET  /health
 *
 * 既定で 127.0.0.1 のみにバインドする。同一端末の他アプリからは到達でき、
 * 同じ Wi-Fi の別端末からは到達できない。LAN に公開したい場合は
 * [bindAll] を true にすること（その場合は認証が無い点に注意）。
 */
class LlamaServer(
    private val port: Int = 8080,
    private val bindAll: Boolean = false,
    private val ctxRef: android.content.Context
) {

    companion object {
        private const val TAG = "LlamaServer"
        private const val MODEL_ID = "bonsai"
        private const val MAX_BODY = 4 * 1024 * 1024
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile var running = false
        private set

    val endpoint: String
        get() = "http://${if (bindAll) "0.0.0.0" else "127.0.0.1"}:$port/v1"

    fun start() {
        if (running) return
        running = true
        thread(name = "llama-server") {
            try {
                val addr = if (bindAll) null else InetAddress.getByName("127.0.0.1")
                val ss = ServerSocket(port, 16, addr)
                server = ss
                Log.i(TAG, "listening on $endpoint")
                while (running) {
                    val sock = try { ss.accept() } catch (e: Exception) { break }
                    thread(name = "llama-conn") { handle(sock) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "server error", e)
            } finally {
                running = false
                Log.i(TAG, "stopped")
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    // ---------------------------------------------------------------- HTTP

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 30_000
                val ins = s.getInputStream()
                val out = BufferedOutputStream(s.getOutputStream())

                val requestLine = readLine(ins) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')

                var contentLength = 0
                while (true) {
                    val line = readLine(ins) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0 && line.substring(0, i).trim()
                            .equals("Content-Length", true)) {
                        contentLength = line.substring(i + 1).trim().toIntOrNull() ?: 0
                    }
                }
                if (contentLength > MAX_BODY) {
                    sendJson(out, 413, JSONObject().put("error", "body too large"))
                    return
                }

                val body = if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = ins.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                } else ""

                route(method, path, body, out)
            } catch (e: Exception) {
                Log.e(TAG, "handler error", e)
            }
        }
    }

    private fun route(method: String, path: String, body: String, out: BufferedOutputStream) {
        when {
            method == "OPTIONS" -> sendCors(out)

            path == "/health" ->
                sendJson(out, 200, JSONObject()
                    .put("status", if (Engine.isLoaded) "ok" else "no_model")
                    .put("busy", Engine.busy))

            path == "/v1/models" || path == "/models" ->
                sendJson(out, 200, JSONObject()
                    .put("object", "list")
                    .put("data", JSONArray().put(
                        JSONObject()
                            .put("id", MODEL_ID)
                            .put("object", "model")
                            .put("created", System.currentTimeMillis() / 1000)
                            .put("owned_by", "local"))))

            method == "POST" && (path == "/v1/chat/completions" || path == "/chat/completions") ->
                chat(body, out)

            method == "POST" && (path == "/v1/completions" || path == "/completions") ->
                completions(body, out)

            else -> sendJson(out, 404, errorObj("unknown endpoint: $path", "not_found"))
        }
    }

    // ------------------------------------------------------------ endpoints

    private fun completions(body: String, out: BufferedOutputStream) {
        val req = try { JSONObject(body) } catch (e: Exception) {
            sendJson(out, 400, errorObj("invalid json", "invalid_request_error")); return
        }
        // prompt を user メッセージ1件に変換して chat 経路へ流す
        val prompt = req.optString("prompt", "")
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
        req.put("messages", msgs)
        chat(req.toString(), out)
    }

    private fun chat(body: String, out: BufferedOutputStream) {
        if (!Engine.isLoaded) {
            sendJson(out, 503, errorObj("model not loaded", "server_error")); return
        }

        val req = try { JSONObject(body) } catch (e: Exception) {
            sendJson(out, 400, errorObj("invalid json", "invalid_request_error")); return
        }

        val arr = req.optJSONArray("messages")
        if (arr == null || arr.length() == 0) {
            sendJson(out, 400, errorObj("messages is required", "invalid_request_error")); return
        }

        val messages: MutableList<LlamaBridge.Msg> = ArrayList(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            // content は文字列 or パーツ配列。後者はテキストだけ拾う。
            val content = when (val c = m.opt("content")) {
                is JSONArray -> buildString {
                    for (j in 0 until c.length()) {
                        c.optJSONObject(j)?.let { if (it.optString("type") == "text")
                            append(it.optString("text")) }
                    }
                }
                else -> c?.toString() ?: ""
            }
            messages.add(LlamaBridge.Msg(role, content))
        }

        // "rag": true を付けると資料検索を挟む（OpenAI仕様の拡張）
        val useRag = req.optBoolean("rag", false)
        if (useRag) {
            val q = messages.lastOrNull { it.role == "user" }?.content ?: ""
            val (context, _) = Pipeline(ctxRef).retrieve(q)
            val idx = messages.indexOfLast { it.role == "user" }
            if (idx >= 0) {
                messages[idx] = LlamaBridge.Msg("user",
                    "【参考資料】\n" + context + "\n\n【質問】\n" + q)
            }
        }

        val params = LlamaBridge.Params(
            maxTokens   = req.optInt("max_tokens", 512).coerceIn(1, 4096),
            temperature = req.optDouble("temperature", 0.35).toFloat(),
            topP        = req.optDouble("top_p", 0.90).toFloat(),
            topK        = req.optInt("top_k", 20),
            seed        = req.optInt("seed", -1)
        )

        val id = "chatcmpl-" + System.nanoTime().toString(16)
        val created = System.currentTimeMillis() / 1000

        if (req.optBoolean("stream", false)) {
            streamChat(id, created, messages, params, out)
        } else {
            val sb = StringBuilder()
            Engine.generate(messages, params, object : LlamaBridge.TokenCallback {
                override fun onToken(piece: String) { sb.append(piece) }
            })
            val text = clean(sb.toString())
            sendJson(out, 200, JSONObject()
                .put("id", id)
                .put("object", "chat.completion")
                .put("created", created)
                .put("model", MODEL_ID)
                .put("choices", JSONArray().put(JSONObject()
                    .put("index", 0)
                    .put("message", JSONObject()
                        .put("role", "assistant")
                        .put("content", text))
                    .put("finish_reason", "stop")))
                .put("usage", JSONObject()
                    .put("prompt_tokens", 0)
                    .put("completion_tokens", 0)
                    .put("total_tokens", 0)))
        }
    }

    private fun streamChat(
        id: String, created: Long,
        messages: List<LlamaBridge.Msg>, params: LlamaBridge.Params,
        out: BufferedOutputStream
    ) {
        writeHeader(out, 200, "text/event-stream", extra =
            "Cache-Control: no-cache\r\nConnection: keep-alive\r\n")

        fun chunk(delta: JSONObject, finish: String?): String {
            val o = JSONObject()
                .put("id", id)
                .put("object", "chat.completion.chunk")
                .put("created", created)
                .put("model", MODEL_ID)
                .put("choices", JSONArray().put(JSONObject()
                    .put("index", 0)
                    .put("delta", delta)
                    .put("finish_reason", finish ?: JSONObject.NULL)))
            return "data: $o\n\n"
        }

        try {
            out.write(chunk(JSONObject().put("role", "assistant"), null)
                .toByteArray(Charsets.UTF_8))
            out.flush()

            // <think> 除去のため、タグを跨ぐ可能性のある間はバッファする
            val pending = StringBuilder()

            Engine.generate(messages, params, object : LlamaBridge.TokenCallback {
                override fun onToken(piece: String) {
                    pending.append(piece)
                    val emit = flushSafe(pending)
                    if (emit.isNotEmpty()) {
                        out.write(chunk(JSONObject().put("content", emit), null)
                            .toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
            })

            val rest = clean(pending.toString())
            if (rest.isNotEmpty()) {
                out.write(chunk(JSONObject().put("content", rest), null)
                    .toByteArray(Charsets.UTF_8))
            }
            out.write(chunk(JSONObject(), "stop").toByteArray(Charsets.UTF_8))
            out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "stream aborted", e)
        }
    }

    // -------------------------------------------------------------- helpers

    /** <think> ブロックを含まないと確定した先頭部分だけを取り出す */
    private fun flushSafe(sb: StringBuilder): String {
        val s = sb.toString()
        val open = s.indexOf("<think>")
        if (open >= 0) {
            val close = s.indexOf("</think>", open)
            if (close < 0) return ""              // 閉じ待ち
            sb.delete(open, close + 8)
            return flushSafe(sb)
        }
        // "<think>" の途中まで来ている可能性がある末尾は保持
        val keep = (1..7).firstOrNull { s.endsWith("<think>".substring(0, it)) } ?: 0
        if (s.length - keep <= 0) return ""
        val emit = s.substring(0, s.length - keep)
        sb.delete(0, s.length - keep)
        return emit
    }

    private fun clean(raw: String): String =
        Regex("(?s)<think>.*?</think>").replace(raw, "").trimStart()

    private fun errorObj(msg: String, type: String) = JSONObject()
        .put("error", JSONObject().put("message", msg).put("type", type))

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = ins.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().removeSuffix("\r")
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun writeHeader(
        out: BufferedOutputStream, code: Int, contentType: String,
        length: Int = -1, extra: String = ""
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n")
        sb.append("Content-Type: $contentType; charset=utf-8\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        if (length >= 0) sb.append("Content-Length: $length\r\n")
        sb.append(extra)
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sendCors(out: BufferedOutputStream) {
        writeHeader(out, 200, "text/plain", 0)
        out.flush()
    }

    private fun sendJson(out: BufferedOutputStream, code: Int, obj: JSONObject) {
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        writeHeader(out, code, "application/json", bytes.size)
        out.write(bytes)
        out.flush()
    }
}
