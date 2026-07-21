// llama_jni.cpp v0.3
//
// v1.2 の変更:
//   - プロンプトを n_batch 単位に分割してプリフィルする（RAGで落ちる問題の修正）
//   - n_ctx を超えるプロンプトを安全に切り詰める
//
// v0.6 の変更:
//   - messages[] 配列に対応（OpenAI互換サーバー用のマルチターン）
//   - サンプラをリクエスト単位で構築（temperature 等をAPI経由で指定可能に）
//
// v0.5 の変更:
//   - 簡体字トークンを logit bias で禁止（言語ドリフト対策）
//   - システムプロンプトを Kotlin 側から指定
//
// v0.4 の変更:
//   - UTF-8 マルチバイト境界のバッファリング（日本語で落ちる致命バグの修正）
//   - トークンを jbyteArray で受け渡し（NewStringUTF の Modified UTF-8 制約を回避）
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
#include <cmath>
#include <set>
#include <cstdint>
#include <algorithm>
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
    int n_vocab = 0;
    int n_batch = 256;   // llama_decode に一度に渡せる上限
    std::vector<llama_logit_bias> biases;   // sampler より長生きさせる
    std::atomic<bool> stop{false};
};

std::string piece_of(const llama_vocab * vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
    if (n < 0) return {};
    return std::string(buf, n);
}

// 文字列末尾にある「不完全なUTF-8シーケンス」のバイト数を返す。
// BPE は日本語1文字を複数トークンに分割することがあり、断片のまま
// Java 側へ渡すと JVM が abort する。完成したぶんだけ送るために使う。
size_t incomplete_utf8_len(const std::string & s) {
    const size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; back++) {
        const unsigned char c = (unsigned char) s[n - back];
        if ((c & 0xC0) == 0x80) continue;            // 継続バイト。さらに遡る
        size_t need;
        if      ((c & 0x80) == 0x00) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        else return back;                            // 不正バイト。切り捨てる
        return (back < need) ? back : 0;
    }
    return 0;
}

// バイト列を jbyteArray でコールバックへ渡す。
// NewStringUTF は Modified UTF-8 しか受け付けず、絵文字(4バイト)でも
// 落ちるため、デコードは Kotlin 側の String(bytes, UTF_8) に任せる。
void emit(JNIEnv * env, jobject cb, jmethodID mid, const std::string & text) {
    if (text.empty()) return;
    jbyteArray arr = env->NewByteArray((jsize) text.size());
    if (!arr) return;
    env->SetByteArrayRegion(arr, 0, (jsize) text.size(),
                            reinterpret_cast<const jbyte *>(text.data()));
    env->CallVoidMethod(cb, mid, arr);
    env->DeleteLocalRef(arr);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}


// ---- 簡体字の抑制 ----------------------------------------------------
//
// 1bit 量子化モデルは言語ドリフトを起こしやすく、日本語プロンプトでも
// 中国語が混入する。プロンプト側の指示だけでは抑えきれないため、
// 「日本語では使わない簡体字」を含むトークンを logit bias で禁止する。
//
// 注意: 学・国・会・体・数 など日本の新字体と同形の字は含めていない。
// これらを禁止すると日本語自体が壊れる。

static const char * kSimplifiedOnly =
    u8"东个丽举么义乌乐乔习乡书买乱亏亚产仅从仑仓仪们价众优伟传伤伦你佣侠侣侧侨俩俭俯倦债倾储兰关兴农冲决况净凉减几卖哪处备复够头夹夺奋妆宁实宽宾对寻导尔尘尝层属岁币帅师带帮庆库应张弹归当录彻径忆怀态总恋恳惊惧惯战户扑执扩扫扬担拟挂挥换据摄摆敌无时显晒术机权极构枪标树桥检楼欢欧汇汉灭灯炉烦烧热爱爷牵犹独狮猪玛环现琼电疗皱监盖盘睁码础确离种积称稳穷窃窜竖笔笼筑简类粮紧纠纤约级纪纬纯纱纲纳纵纷纸纹纺纽线练组绅细织终绊绍绎经绑绒结绕绘给绚络绝绞统绢绣继续维绵综缓编缘缝缩缴见警计订认讨让训议讯记讲讳讶许论讼设访诀证评识诈诉诊词译试诗诚话诞诠询该详诬语误说诵请诸诺读课谁调谅谈谊谋谎谓谜谢谣谦谨谬谱贝财责贤败货质贩贪贫购贮贯贵贷贸费贺贼贾资赋赌赎赏赐赔赚赛赞赠赢赶起趋车轧轨轩转轮软轰轻载较辅辆辈辉辐输辖辙边达过运还这进远连适选那针钉钓钙钟钢钥钦钩钱钻铁铃铅铜铝银铺链销锁锅锋锐错锦键镇镜长门闪闭问闯闲间闷闻阀阁阅阐阔阳阴阶际陆陇陈陕页题风飞饥饭饮饰饱饲饼馅馆馈馒马驰驱驳驻驾骂骄骆验骑骗骤骨鸟龙，";

// UTF-8 文字列をコードポイント集合へ
std::set<uint32_t> decode_utf8_set(const char * s) {
    std::set<uint32_t> out;
    const unsigned char * p = (const unsigned char *) s;
    while (*p) {
        uint32_t cp; int len;
        if      ((*p & 0x80) == 0x00) { cp = *p;         len = 1; }
        else if ((*p & 0xE0) == 0xC0) { cp = *p & 0x1F;  len = 2; }
        else if ((*p & 0xF0) == 0xE0) { cp = *p & 0x0F;  len = 3; }
        else if ((*p & 0xF8) == 0xF0) { cp = *p & 0x07;  len = 4; }
        else { p++; continue; }
        for (int i = 1; i < len; i++) cp = (cp << 6) | (p[i] & 0x3F);
        out.insert(cp);
        p += len;
    }
    return out;
}

bool contains_any_cp(const std::string & s, const std::set<uint32_t> & banned) {
    const unsigned char * p = (const unsigned char *) s.c_str();
    const unsigned char * end = p + s.size();
    while (p < end) {
        uint32_t cp; int len;
        if      ((*p & 0x80) == 0x00) { cp = *p;         len = 1; }
        else if ((*p & 0xE0) == 0xC0) { cp = *p & 0x1F;  len = 2; }
        else if ((*p & 0xF0) == 0xE0) { cp = *p & 0x0F;  len = 3; }
        else if ((*p & 0xF8) == 0xF0) { cp = *p & 0x07;  len = 4; }
        else { p++; continue; }
        if (p + len > end) break;
        for (int i = 1; i < len; i++) cp = (cp << 6) | (p[i] & 0x3F);
        if (banned.count(cp)) return true;
        p += len;
    }
    return false;
}

// GGUF 埋め込みのチャットテンプレートを適用する。
// 取得できなければ ChatML でフォールバック。
std::string apply_chat_template(const llama_model * model,
                                const std::vector<std::string> & roles,
                                const std::vector<std::string> & contents) {
    const char * tmpl = llama_model_chat_template(model, nullptr);

    if (tmpl == nullptr) {
        LOGI("no embedded chat template, falling back to ChatML");
        std::string s;
        for (size_t i = 0; i < roles.size(); i++) {
            s += "<|im_start|>" + roles[i] + "\n" + contents[i] + "<|im_end|>\n";
        }
        s += "<|im_start|>assistant\n";
        return s;
    }

    std::vector<llama_chat_message> msgs;
    msgs.reserve(roles.size());
    for (size_t i = 0; i < roles.size(); i++) {
        msgs.push_back({ roles[i].c_str(), contents[i].c_str() });
    }

    size_t total = 0;
    for (size_t i = 0; i < roles.size(); i++) total += roles[i].size() + contents[i].size();

    std::vector<char> buf(total + 2048);
    // 第4引数 true = 末尾に assistant 開始タグを付ける
    int32_t n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                          true, buf.data(), (int32_t) buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                      true, buf.data(), (int32_t) buf.size());
    }
    if (n < 0) {
        LOGE("chat template apply failed, using raw last message");
        return contents.empty() ? std::string() : contents.back();
    }
    return std::string(buf.data(), n);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_appathy_bonsai_LlamaBridge_nativeLoad(
        JNIEnv * env, jobject, jstring jPath, jint nCtx, jint nThreads,
        jboolean banSimplified) {

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

    auto * s = new Session();
    s->model = model;
    s->ctx   = ctx;

    // 簡体字トークンの禁止。語彙全走査は数十msで済む。
    // バイアス自体は Session に保持し、サンプラはリクエストごとに組み直す。
    if (banSimplified) {
        const llama_vocab * vocab = llama_model_get_vocab(model);
        const std::set<uint32_t> banned = decode_utf8_set(kSimplifiedOnly);
        const int n_vocab = llama_vocab_n_tokens(vocab);
        for (int t = 0; t < n_vocab; t++) {
            if (contains_any_cp(piece_of(vocab, t), banned)) {
                s->biases.push_back({ (llama_token) t, -INFINITY });
            }
        }
        LOGI("banned %zu / %d tokens (simplified chinese)", s->biases.size(), n_vocab);
    }
    s->n_vocab = llama_vocab_n_tokens(llama_model_get_vocab(model));
    s->n_batch = (int) cparams.n_batch;

    LOGI("loaded, n_ctx=%d threads=%d kv=q8_0 mmap=on", nCtx, nThreads);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_appathy_bonsai_LlamaBridge_nativeGenerate(
        JNIEnv * env, jobject, jlong handle,
        jobjectArray jRoles, jobjectArray jContents,
        jint maxTokens, jfloat temp, jfloat topP, jint topK, jint seed,
        jobject callback) {

    auto * s = reinterpret_cast<Session *>(handle);
    if (!s) return;

    s->stop.store(false);

    const llama_vocab * vocab = llama_model_get_vocab(s->model);

    // ---- messages[] を取り出す ----
    const jsize n_msg = env->GetArrayLength(jRoles);
    std::vector<std::string> roles, contents;
    roles.reserve(n_msg); contents.reserve(n_msg);
    for (jsize i = 0; i < n_msg; i++) {
        auto jr = (jstring) env->GetObjectArrayElement(jRoles, i);
        auto jc = (jstring) env->GetObjectArrayElement(jContents, i);
        const char * r = env->GetStringUTFChars(jr, nullptr);
        const char * c = env->GetStringUTFChars(jc, nullptr);
        roles.emplace_back(r);
        contents.emplace_back(c);
        env->ReleaseStringUTFChars(jr, r);
        env->ReleaseStringUTFChars(jc, c);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
    }

    std::string formatted = apply_chat_template(s->model, roles, contents);

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

    LOGI("messages=%d prompt_tokens=%d n_ctx=%d temp=%.2f",
         (int) n_msg, n_needed, (int) llama_n_ctx(s->ctx), (double) temp);

    // ---- サンプラをリクエスト単位で構築 ----
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!s->biases.empty()) {
        llama_sampler_chain_add(smpl,
            llama_sampler_init_logit_bias(s->n_vocab,
                                          (int32_t) s->biases.size(),
                                          s->biases.data()));
    }
    if (topK > 0)   llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    if (topP < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(
        seed < 0 ? LLAMA_DEFAULT_SEED : (uint32_t) seed));

    // 前回の生成が残っていると文脈が壊れるので毎回リセット
    llama_memory_clear(llama_get_memory(s->ctx), true);

    jclass cbClass  = env->GetObjectClass(callback);
    jmethodID onTok = env->GetMethodID(cbClass, "onToken", "([B)V");
    std::string pending;   // 未完成のUTF-8シーケンスを溜めておく

    // ---- プロンプトが長すぎる場合は末尾を優先して切り詰める ----
    const int n_ctx_cur = (int) llama_n_ctx(s->ctx);
    const int max_prompt = n_ctx_cur - maxTokens - 8;
    if (max_prompt > 0 && (int) tokens.size() > max_prompt) {
        LOGE("prompt %d tokens > budget %d, truncating head",
             (int) tokens.size(), max_prompt);
        tokens.erase(tokens.begin(), tokens.end() - max_prompt);
    }

    // ---- プリフィル: n_batch を超えないよう分割して食わせる ----
    // ここを1回で渡すと llama.cpp 側のアサートで abort する（RAG有効時に発生）
    bool prefill_ok = true;
    for (size_t off = 0; off < tokens.size(); off += (size_t) s->n_batch) {
        if (s->stop.load()) { prefill_ok = false; break; }
        const int n = (int) std::min((size_t) s->n_batch, tokens.size() - off);
        llama_batch pb = llama_batch_get_one(tokens.data() + off, n);
        if (llama_decode(s->ctx, pb) != 0) {
            LOGE("prefill failed at offset %zu", off);
            prefill_ok = false;
            break;
        }
    }
    if (!prefill_ok) {
        llama_sampler_free(smpl);
        return;
    }

    llama_token cur = 0;   // batch が指す先の生存を保証する
    llama_batch batch = llama_batch_get_one(&cur, 1);
    bool first = true;

    for (int i = 0; i < maxTokens; i++) {
        if (s->stop.load()) {
            LOGI("stopped by user at %d", i);
            break;
        }

        if (!first) {
            if (llama_decode(s->ctx, batch) != 0) {
                LOGE("llama_decode failed at %d", i);
                break;
            }
        }
        first = false;

        llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;

        llama_sampler_accept(smpl, id);

        pending += piece_of(vocab, id);

        // 完成しているぶんだけ送り、末尾の断片は次トークンまで持ち越す
        const size_t inc = incomplete_utf8_len(pending);
        if (pending.size() > inc) {
            emit(env, callback, onTok, pending.substr(0, pending.size() - inc));
            pending = pending.substr(pending.size() - inc);
        }

        cur   = id;
        batch = llama_batch_get_one(&cur, 1);
    }

    // EOG や停止で抜けた際、完成済みの残りがあれば送る
    if (!pending.empty() && incomplete_utf8_len(pending) == 0) {
        emit(env, callback, onTok, pending);
    }

    llama_sampler_free(smpl);
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
    if (s->ctx)   llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

} // extern "C"
