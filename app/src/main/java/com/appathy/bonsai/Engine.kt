package com.appathy.bonsai

import java.io.File

/**
 * モデルはプロセス内に1つだけ。Activity と Service（HTTPサーバー）が共有する。
 *
 * llama_context は同時実行できないので、推論は必ず [lock] で直列化すること。
 * 8Bだと1リクエスト数十秒かかるため、待たされる側は素直に待つ設計にしている。
 */
object Engine {

    val bridge = LlamaBridge()

    /** 推論の排他。Activity 側の生成と HTTP リクエストが衝突しないようにする */
    val lock = Any()

    @Volatile var busy = false
        private set

    fun modelFile(filesDir: File) = File(filesDir, "model.gguf")

    val isLoaded: Boolean get() = bridge.isLoaded

    /**
     * 排他付きの生成。すでに実行中なら待たされる。
     */
    fun generate(
        messages: List<LlamaBridge.Msg>,
        params: LlamaBridge.Params,
        cb: LlamaBridge.TokenCallback
    ) {
        synchronized(lock) {
            busy = true
            try {
                bridge.generate(messages, params, cb)
            } finally {
                busy = false
            }
        }
    }

    fun stop() = bridge.stop()

    /** 誰も使っていなければモデルを解放してRAMを返す */
    fun releaseIfIdle() {
        synchronized(lock) {
            if (!busy) bridge.free()
        }
    }
}
