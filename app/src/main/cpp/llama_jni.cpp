// llama_jni.cpp — 最小 JNI ブリッジ
//
// 【重要】このコードは llama.cpp の 2025年前後の C API（sampler chain 系）を前提に
// 書いてある。llama.h は破壊的変更が入りやすいので、ビルドが通ったタグを
// build.yml の LLAMA_REF に必ずピン留めすること。
// コンパイルエラーが出た場合、直すのはほぼ確実にこのファイルだけ。

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "BonsaiJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model   * model = nullptr;
    llama_context * ctx   = nullptr;
    llama_sampler * smpl  = nullptr;
};

std::string piece_of(const llama_vocab * vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
    if (n < 0) return {};
    return std::string(buf, n);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_appathy_bonsai_LlamaBridge_nativeLoad(
        JNIEnv * env, jobject, jstring jPath, jint nCtx, jint nThreads) {

    static bool backend_ready = false;
    if (!backend_ready) {
        llama_backend_init();
        backend_ready = true;
    }

    const char * path = env->GetStringUTFChars(jPath, nullptr);

    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;              // CPU のみ。GPU は後日の課題

    llama_model * model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jPath, path);

    if (!model) {
        LOGE("model load failed");
        return 0;
    }

    auto cparams = llama_context_default_params();
    cparams.n_ctx       = (uint32_t) nCtx;
    cparams.n_batch     = 512;
    cparams.n_threads   = nThreads;
    cparams.n_threads_batch = nThreads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("context init failed");
        llama_model_free(model);
        return 0;
    }

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Bonsai 公式推奨: temp 0.5 / top-p 0.9 / top-k 20
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.90f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.5f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    auto * s = new Session{model, ctx, smpl};
    LOGI("loaded, n_ctx=%d threads=%d", nCtx, nThreads);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_appathy_bonsai_LlamaBridge_nativeGenerate(
        JNIEnv * env, jobject, jlong handle, jstring jPrompt, jint maxTokens,
        jobject callback) {

    auto * s = reinterpret_cast<Session *>(handle);
    if (!s) return;

    const llama_vocab * vocab = llama_model_get_vocab(s->model);

    const char * prompt = env->GetStringUTFChars(jPrompt, nullptr);
    const int prompt_len = (int) strlen(prompt);

    // トークン化（必要バッファ長は負値で返る）
    int n_needed = -llama_tokenize(vocab, prompt, prompt_len, nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_needed);
    llama_tokenize(vocab, prompt, prompt_len, tokens.data(), n_needed, true, true);
    env->ReleaseStringUTFChars(jPrompt, prompt);

    jclass cbClass  = env->GetObjectClass(callback);
    jmethodID onTok = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    llama_token cur = 0;   // batch が指す先の生存を保証する

    for (int i = 0; i < maxTokens; i++) {
        if (llama_decode(s->ctx, batch) != 0) {
            LOGE("llama_decode failed at %d", i);
            break;
        }

        llama_token id = llama_sampler_sample(s->smpl, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;

        llama_sampler_accept(s->smpl, id);

        std::string piece = piece_of(vocab, id);
        jstring jPiece = env->NewStringUTF(piece.c_str());
        env->CallVoidMethod(callback, onTok, jPiece);
        env->DeleteLocalRef(jPiece);

        cur   = id;
        batch = llama_batch_get_one(&cur, 1);
    }
}

JNIEXPORT void JNICALL
Java_com_appathy_bonsai_LlamaBridge_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (!s) return;
    if (s->smpl)  llama_sampler_free(s->smpl);
    if (s->ctx)   llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

} // extern "C"
