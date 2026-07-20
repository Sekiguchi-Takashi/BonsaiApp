// llama_jni.cpp v0.3
//
// v0.3 の変更:
//   - use_mmap=true / use_mlock=false を明示（低RAM端末で必須）
//   - KVキャッシュを q8_0 化（type_k / type_v）
//   - llama_chat_apply_template によるチャット整形
//   - 生成の中断（stop フラグ）
//   - n_ctx を Kotlin 側から指定可能に
//
// 【重要】llama.h は破壊的変更が入りやすい。ビルドが通ったタグを
// build.yml の LLAMA_REF にピン留めすること。
// コンパイルエラーが出た場合、直すのはほぼ確実にこのファイルだけ。

#include <jni.h>
#include <android/log.h>
#include <atomic>
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
    std::atomic<bool> stop{false};
};

std::string piece_of(const llama_vocab * vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
    if (n < 0) return {};
    return std::string(buf, n);
}

// GGUF 埋め込みのチャットテンプレートを適用する。
// 取得できなければ ChatML でフォールバック。
std::string apply_chat_template(const llama_model * model,
                                const std::string & system,
                                const std::string & user) {
    const char * tmpl = llama_model_chat_template(model, nullptr);

    if (tmpl == nullptr) {
        LOGI("no embedded chat template, falling back to ChatML");
        std::string s;
        if (!system.empty())
            s += "<|im_start|>system\n" + system + "<|im_end|>\n";
        s += "<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
        return s;
    }

    std::vector<llama_chat_message> msgs;
    if (!system.empty()) msgs.push_back({"system", system.c_str()});
    msgs.push_back({"user", user.c_str()});

    std::vector<char> buf(system.size() + user.size() + 2048);
    // 第4引数 true = 末尾に assistant 開始タグを付ける
    int32_t n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                          true, buf.data(), (int32_t) buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                      true, buf.data(), (int32_t) buf.size());
    }
    if (n < 0) {
        LOGE("chat template apply failed, using raw prompt");
        return user;
    }
    return std::string(buf.data(), n);
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
    mparams.n_gpu_layers = 0;
    // 低RAM端末の生命線。重みをファイルから随時ページインさせ、
    // 常駐RSSを抑える。mlock は絶対に有効化しないこと。
    mparams.use_mmap  = true;
    mparams.use_mlock = false;

    llama_model * model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jPath, path);

    if (!model) {
        LOGE("model load failed");
        return 0;
    }

    auto cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t) nCtx;
    cparams.n_batch         = 256;      // 512 だとプリフィル時のピークRAMが厳しい
    cparams.n_ubatch        = 256;
    cparams.n_threads       = nThreads;
    cparams.n_threads_batch = nThreads;
    // KVキャッシュを 8bit 化。f16 比でほぼ半分。品質劣化はごく軽微。
    cparams.type_k = GGML_TYPE_Q8_0;
    cparams.type_v = GGML_TYPE_Q8_0;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("context init failed (likely OOM)");
        llama_model_free(model);
        return 0;
    }

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Bonsai 公式推奨: temp 0.5 / top-p 0.9 / top-k 20
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.90f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.5f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    auto * s = new Session();
    s->model = model;
    s->ctx   = ctx;
    s->smpl  = smpl;

    LOGI("loaded, n_ctx=%d threads=%d kv=q8_0 mmap=on", nCtx, nThreads);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_appathy_bonsai_LlamaBridge_nativeGenerate(
        JNIEnv * env, jobject, jlong handle,
        jstring jSystem, jstring jPrompt, jint maxTokens, jobject callback) {

    auto * s = reinterpret_cast<Session *>(handle);
    if (!s) return;

    s->stop.store(false);

    const llama_vocab * vocab = llama_model_get_vocab(s->model);

    const char * cSystem = env->GetStringUTFChars(jSystem, nullptr);
    const char * cPrompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string formatted = apply_chat_template(s->model, cSystem, cPrompt);
    env->ReleaseStringUTFChars(jSystem, cSystem);
    env->ReleaseStringUTFChars(jPrompt, cPrompt);

    // テンプレートが特殊トークンを含むので add_special=false / parse_special=true
    int n_needed = -llama_tokenize(vocab, formatted.c_str(), (int32_t) formatted.size(),
                                   nullptr, 0, false, true);
    if (n_needed <= 0) {
        LOGE("tokenize failed");
        return;
    }
    std::vector<llama_token> tokens(n_needed);
    llama_tokenize(vocab, formatted.c_str(), (int32_t) formatted.size(),
                   tokens.data(), n_needed, false, true);

    LOGI("prompt tokens = %d", n_needed);

    // 前回の生成が残っていると文脈が壊れるので毎回リセット
    llama_memory_clear(llama_get_memory(s->ctx), true);

    jclass cbClass  = env->GetObjectClass(callback);
    jmethodID onTok = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    llama_token cur = 0;   // batch が指す先の生存を保証する

    for (int i = 0; i < maxTokens; i++) {
        if (s->stop.load()) {
            LOGI("stopped by user at %d", i);
            break;
        }

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
Java_com_appathy_bonsai_LlamaBridge_nativeStop(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s) s->stop.store(true);
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
