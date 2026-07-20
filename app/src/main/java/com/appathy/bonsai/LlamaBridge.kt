package com.appathy.bonsai

/**
 * llama.cpp への薄いラッパ。
 * 呼び出しは必ずワーカースレッドから（推論はブロッキング）。
 */
class LlamaBridge {

    interface TokenCallback {
        fun onToken(piece: String)
    }

    private var handle: Long = 0L

    val isLoaded: Boolean get() = handle != 0L

    fun load(modelPath: String, nCtx: Int = 2048, nThreads: Int = 4): Boolean {
        if (handle != 0L) free()
        handle = nativeLoad(modelPath, nCtx, nThreads)
        return handle != 0L
    }

    fun generate(prompt: String, maxTokens: Int = 256, cb: TokenCallback) {
        if (handle == 0L) return
        nativeGenerate(handle, prompt, maxTokens, cb)
    }

    fun free() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeGenerate(h: Long, prompt: String, maxTokens: Int, cb: TokenCallback)
    private external fun nativeFree(h: Long)

    companion object {
        init { System.loadLibrary("bonsai_jni") }
    }
}
