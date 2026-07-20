package com.appathy.bonsai

/**
 * llama.cpp への薄いラッパ。
 *
 * v0.4: ネイティブからは ByteArray でトークンを受け取り、Kotlin側で
 * UTF-8 デコードする。NewStringUTF は Modified UTF-8 しか扱えず、
 * 日本語の分割トークンや絵文字で JVM が abort するため。
 */
class LlamaBridge {

    interface TokenCallback {
        fun onToken(piece: String)
    }

    /** ネイティブが直接呼ぶ受け口。メソッド名とシグネチャは C++ 側と一致させること */
    private class NativeSink(private val cb: TokenCallback) {
        @Suppress("unused")   // JNI から反射的に呼ばれる
        fun onToken(bytes: ByteArray) {
            cb.onToken(String(bytes, Charsets.UTF_8))
        }
    }

    private var handle: Long = 0L

    val isLoaded: Boolean get() = handle != 0L

    /**
     * @param banSimplified 簡体字を含むトークンを logit bias で禁止する。
     *        語彙全走査のぶん読込が数十ms伸びるが、言語ドリフトにはこれが一番効く。
     */
    fun load(
        modelPath: String,
        nCtx: Int = 1024,
        nThreads: Int = 4,
        banSimplified: Boolean = true
    ): Boolean {
        if (handle != 0L) free()
        handle = nativeLoad(modelPath, nCtx, nThreads, banSimplified)
        return handle != 0L
    }

    fun generate(
        system: String,
        prompt: String,
        maxTokens: Int = 256,
        cb: TokenCallback
    ) {
        if (handle == 0L) return
        nativeGenerate(handle, system, prompt, maxTokens, NativeSink(cb))
    }

    fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    fun free() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    private external fun nativeLoad(
        path: String, nCtx: Int, nThreads: Int, banSimplified: Boolean
    ): Long
    private external fun nativeGenerate(
        h: Long, system: String, prompt: String, maxTokens: Int, sink: Any
    )
    private external fun nativeStop(h: Long)
    private external fun nativeFree(h: Long)

    companion object {
        init { System.loadLibrary("bonsai_jni") }
    }
}
