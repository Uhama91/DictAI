#include <jni.h>
#include "llama.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <climits>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>
#if defined(__ANDROID__) && defined(__aarch64__)
#include <asm/hwcap.h>
#include <sys/auxv.h>
#endif

namespace {
using Clock = std::chrono::steady_clock;
int64_t now_ms() { return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now().time_since_epoch()).count(); }
struct Session {
    llama_model *model = nullptr;
    llama_context *context = nullptr;
    std::mutex inference;
    std::atomic<int64_t> cancelled_through{0}, generation{0}, deadline{INT64_MAX};
    std::atomic<bool> closed{false};
    ~Session() { if (context) llama_free(context); if (model) llama_model_free(model); }
};
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
jlong next_handle = 1;
std::once_flag backend_once;

std::shared_ptr<Session> acquire(jlong handle) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto found = sessions.find(handle);
    return found == sessions.end() ? nullptr : found->second;
}
bool abort_inference(void *data) {
    auto &s = *static_cast<Session *>(data);
    return s.closed.load() || s.cancelled_through.load() >= s.generation.load() || now_ms() >= s.deadline.load();
}
std::string read_bytes(JNIEnv *env, jbyteArray value) {
    if (!value) return {};
    const auto size = env->GetArrayLength(value);
    std::string result(static_cast<size_t>(size), '\0');
    if (size) env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte *>(result.data()));
    return result;
}
jbyteArray write_bytes(JNIEnv *env, const std::string &value, size_t size) {
    if (size > static_cast<size_t>(INT_MAX)) return nullptr;
    auto bytes = env->NewByteArray(static_cast<jsize>(size));
    if (bytes && size) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(value.data()));
    return bytes;
}
// Token pieces can end inside an accent/emoji. Never expose an incomplete sequence to Java.
std::optional<size_t> utf8_prefix(const std::string &s) {
    size_t i = 0;
    while (i < s.size()) {
        const auto c = static_cast<unsigned char>(s[i]);
        const size_t n = c < 0x80 ? 1 : c >= 0xc2 && c <= 0xdf ? 2 : c >= 0xe0 && c <= 0xef ? 3 : c >= 0xf0 && c <= 0xf4 ? 4 : 0;
        if (n == 0) return std::nullopt;
        if (i + n > s.size()) return i;
        for (size_t j = 1; j < n; ++j) if ((static_cast<unsigned char>(s[i + j]) & 0xc0) != 0x80) return std::nullopt;
        if (n >= 3) {
            const auto b = static_cast<unsigned char>(s[i + 1]);
            if ((c == 0xe0 && b < 0xa0) || (c == 0xed && b >= 0xa0) || (c == 0xf0 && b < 0x90) || (c == 0xf4 && b >= 0x90)) return std::nullopt;
        }
        i += n;
    }
    return i;
}
bool emit(JNIEnv *env, jobject sink, jmethodID method, const std::string &output, size_t size) {
    auto bytes = write_bytes(env, output, size);
    if (!bytes) return false;
    env->CallVoidMethod(sink, method, bytes);
    env->DeleteLocalRef(bytes);
    return !env->ExceptionCheck();
}
void quiet_log(ggml_log_level, const char *, void *) {}
#ifndef DICTAI_ARM82_VARIANT
jboolean native_supports_arm82(JNIEnv *, jobject) {
#if defined(__ANDROID__) && defined(__aarch64__)
    const unsigned long required = HWCAP_ASIMDDP | HWCAP_ASIMDHP | HWCAP_FPHP;
    return (getauxval(AT_HWCAP) & required) == required ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}
#endif

jlong native_open(JNIEnv *env, jobject, jbyteArray path_bytes, jint context_size, jint threads) {
    try {
        if (context_size < 512 || context_size > 8192 || threads < 1 || threads > 8) return 0;
        const auto path = read_bytes(env, path_bytes);
        if (env->ExceptionCheck() || path.empty() || path.find('\0') != std::string::npos) return 0;
        std::call_once(backend_once, [] { llama_log_set(quiet_log, nullptr); llama_backend_init(); });
        auto s = std::make_shared<Session>();
        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
        s->model = llama_model_load_from_file(path.c_str(), model_params);
        if (!s->model) return 0;
        auto context_params = llama_context_default_params();
        context_params.n_ctx = static_cast<uint32_t>(context_size);
        context_params.n_batch = 128;
        context_params.n_ubatch = 128;
        context_params.n_seq_max = 1;
        context_params.n_threads = threads;
        context_params.n_threads_batch = threads;
        context_params.no_perf = true;
        context_params.offload_kqv = false;
        context_params.op_offload = false;
        s->context = llama_init_from_model(s->model, context_params);
        if (!s->context) return 0;
        std::lock_guard<std::mutex> lock(registry_mutex);
        if (next_handle == INT64_MAX) return 0;
        const jlong handle = next_handle++;
        sessions.emplace(handle, std::move(s));
        return handle;
    } catch (...) { return 0; }
}

jbyteArray native_generate(JNIEnv *env, jobject, jlong handle, jlong generation, jbyteArray prompt_bytes, jbyteArray grammar_bytes,
                           jint max_tokens, jlong timeout_ms, jobject sink) {
    try {
        auto s = acquire(handle);
        if (!s || generation <= 0 || max_tokens <= 0 || max_tokens > 8192 || timeout_ms <= 0 || !sink) return nullptr;
        const int64_t deadline = now_ms() + std::min<int64_t>(timeout_ms, 120000);
        std::lock_guard<std::mutex> lock(s->inference);
        s->generation.store(generation);
        s->deadline.store(deadline);
        struct Reset {
            Session &s;
            ~Reset() { llama_set_abort_callback(s.context, nullptr, nullptr); s.generation.store(0); s.deadline.store(INT64_MAX); }
        } reset{*s};
        if (abort_inference(s.get())) return nullptr;
        llama_set_abort_callback(s->context, abort_inference, s.get());
        llama_memory_clear(llama_get_memory(s->context), true);
        const auto prompt = read_bytes(env, prompt_bytes);
        if (env->ExceptionCheck() || prompt.empty() || prompt.size() > 65536) return nullptr;
        const auto *vocab = llama_model_get_vocab(s->model);
        int count = llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, false, true);
        if (count == INT32_MIN || count >= 0) return nullptr;
        count = -count;
        if (static_cast<uint32_t>(count) >= llama_n_ctx(s->context)) return nullptr;
        std::vector<llama_token> tokens(static_cast<size_t>(count));
        count = llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()), tokens.data(), count, false, true);
        if (count <= 0) return nullptr;
        using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
        Sampler sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        if (!sampler) return nullptr;
        auto add = [&](llama_sampler *child) {
            Sampler owned(child, llama_sampler_free);
            if (!owned) return false;
            llama_sampler_chain_add(sampler.get(), owned.get());
            owned.release();
            return true;
        };
        Sampler penalties(llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.05f, 0.0f, 0.0f), llama_sampler_free);
        if (!penalties) return nullptr;
        for (const auto token : tokens) llama_sampler_accept(penalties.get(), token);
        if (!add(penalties.release())) return nullptr;
        if (grammar_bytes) {
            const auto grammar = read_bytes(env, grammar_bytes);
            const auto valid = utf8_prefix(grammar);
            if (env->ExceptionCheck() || grammar.empty() || grammar.size() > 65536 ||
                grammar.find('\0') != std::string::npos || !valid || *valid != grammar.size()) return nullptr;
            if (abort_inference(s.get())) return nullptr;
            if (!add(llama_sampler_init_grammar(vocab, grammar.c_str(), "root"))) return nullptr;
        }
        if (!add(llama_sampler_init_top_k(50)) || !add(llama_sampler_init_temp(0.1f)) ||
            !add(llama_sampler_init_dist(1234))) return nullptr;
        for (int offset = 0; offset < count; offset += 128) {
            if (abort_inference(s.get())) return nullptr;
            auto batch = llama_batch_get_one(tokens.data() + offset, std::min(128, count - offset));
            if (llama_decode(s->context, batch) != 0) return nullptr;
        }
        auto sink_class = env->GetObjectClass(sink);
        if (!sink_class) return nullptr;
        auto on_bytes = env->GetMethodID(sink_class, "onBytes", "([B)V");
        env->DeleteLocalRef(sink_class);
        if (!on_bytes) return nullptr;
        std::string output;
        size_t emitted_size = 0;
        int64_t last_emit = now_ms();
        const int output_limit = std::min(max_tokens, static_cast<int>(llama_n_ctx(s->context)) - count);
        for (int generated = 0; generated <= output_limit; ++generated) {
            if (abort_inference(s.get())) return nullptr;
            llama_token token = llama_sampler_sample(sampler.get(), s->context, -1);
            if (llama_vocab_is_eog(vocab, token)) {
                const auto valid = utf8_prefix(output);
                if (!valid || *valid != output.size() || output.empty() || abort_inference(s.get())) return nullptr;
                if (output.size() != emitted_size && !emit(env, sink, on_bytes, output, output.size())) return nullptr;
                if (abort_inference(s.get())) return nullptr;
                return write_bytes(env, output, output.size());
            }
            if (generated == output_limit) return nullptr;
            char piece[256];
            int size = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            if (size == INT32_MIN) return nullptr;
            if (size < 0) {
                std::vector<char> large_piece(static_cast<size_t>(-size));
                size = llama_token_to_piece(vocab, token, large_piece.data(), static_cast<int32_t>(large_piece.size()), 0, false);
                if (size < 0) return nullptr;
                output.append(large_piece.data(), static_cast<size_t>(size));
            } else { output.append(piece, static_cast<size_t>(size)); }
            if (output.size() > 65536) return nullptr;
            if (emitted_size == 0 || now_ms() - last_emit >= 60) {
                const auto valid = utf8_prefix(output);
                if (!valid) return nullptr;
                if (*valid > emitted_size) {
                    if (!emit(env, sink, on_bytes, output, *valid)) return nullptr;
                    emitted_size = *valid;
                    last_emit = now_ms();
                }
            }
            if (abort_inference(s.get())) return nullptr;
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(s->context, batch) != 0) return nullptr;
        }
        return nullptr;
    } catch (...) { return nullptr; }
}
void native_cancel(JNIEnv *, jobject, jlong handle, jlong generation) {
    auto s = acquire(handle);
    if (!s || generation <= 0) return;
    int64_t current = s->cancelled_through.load();
    while (current < generation && !s->cancelled_through.compare_exchange_weak(current, generation)) {}
}
void native_close(JNIEnv *, jobject, jlong handle) {
    std::shared_ptr<Session> s;
    {
        std::lock_guard<std::mutex> lock(registry_mutex);
        auto found = sessions.find(handle);
        if (found == sessions.end()) return;
        s = found->second;
        sessions.erase(found);
    }
    s->closed.store(true);
}
const JNINativeMethod methods[] = {
#ifndef DICTAI_ARM82_VARIANT
    {const_cast<char *>("supportsArm82"), const_cast<char *>("()Z"), reinterpret_cast<void *>(native_supports_arm82)},
#endif
    {const_cast<char *>("open"), const_cast<char *>("([BII)J"), reinterpret_cast<void *>(native_open)},
    {const_cast<char *>("generate"), const_cast<char *>("(JJ[B[BIJLcom/kafkasl/phonewhisper/LocalFormatChunkSink;)[B"), reinterpret_cast<void *>(native_generate)},
    {const_cast<char *>("cancel"), const_cast<char *>("(JJ)V"), reinterpret_cast<void *>(native_cancel)},
    {const_cast<char *>("close"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(native_close)},
};
}
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
#ifdef DICTAI_ARM82_VARIANT
    auto cls = env->FindClass("com/kafkasl/phonewhisper/LocalFormatArm82Bindings");
#else
    auto cls = env->FindClass("com/kafkasl/phonewhisper/LocalFormatBindings");
#endif
    if (!cls) return JNI_ERR;
    const auto result = env->RegisterNatives(cls, methods, static_cast<jint>(sizeof(methods) / sizeof(methods[0])));
    env->DeleteLocalRef(cls);
    return result == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
