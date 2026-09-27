#include <jni.h>

#include <atomic>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

#include "transcribe.h"

namespace {

constexpr char kBindingsClass[] = "com/kafkasl/phonewhisper/TranscribeCppNative$JniBindings";
constexpr char kNativeExceptionClass[] = "com/kafkasl/phonewhisper/TranscribeCppNativeException";
constexpr char kHandyTokenWindowClass[] = "com/kafkasl/phonewhisper/meeting/HandyTokenWindow";
constexpr std::size_t kMaxPcmBytes = 320000; // 10 seconds at mono 16 kHz PCM16.
constexpr jint kMaxTokenWindow = 8192;

struct NativeSession {
    std::mutex mutex;
    transcribe_session * session = nullptr;
    std::string language;
    bool closed = false;

    ~NativeSession() {
        if (session != nullptr) {
            transcribe_session_free(session);
        }
    }
};

std::mutex g_sessions_mutex;
std::unordered_map<jlong, std::shared_ptr<NativeSession>> g_sessions;
std::atomic<jlong> g_next_handle{1};

void throw_exception(JNIEnv * env, const char * class_name, const std::string & message) {
    jclass exception_class = env->FindClass(class_name);
    if (exception_class == nullptr) {
        env->ExceptionClear();
        exception_class = env->FindClass("java/lang/RuntimeException");
    }
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
        env->DeleteLocalRef(exception_class);
    }
}

void throw_native_error(JNIEnv * env, const char * operation, transcribe_status status) {
    throw_exception(
        env,
        kNativeExceptionClass,
        std::string(operation) + " failed: " + transcribe_status_string(status) +
            " (status=" + std::to_string(static_cast<int>(status)) + ")"
    );
}

void throw_closed_handle(JNIEnv * env) {
    throw_exception(env, "java/lang/IllegalStateException", "transcribe.cpp handle is closed or invalid");
}

std::shared_ptr<NativeSession> acquire_session(JNIEnv * env, jlong handle) {
    if (handle <= 0) {
        throw_closed_handle(env);
        return nullptr;
    }

    std::lock_guard<std::mutex> registry_lock(g_sessions_mutex);
    const auto found = g_sessions.find(handle);
    if (found == g_sessions.end()) {
        throw_closed_handle(env);
        return nullptr;
    }
    return found->second;
}

bool ensure_open(JNIEnv * env, const std::shared_ptr<NativeSession> & native_session) {
    if (native_session->closed || native_session->session == nullptr) {
        throw_closed_handle(env);
        return false;
    }
    return true;
}

std::string copy_text(const char * text, uint64_t size, JNIEnv * env) {
    if (text == nullptr || size == 0) {
        return {};
    }
    if (size > std::numeric_limits<size_t>::max()) {
        throw_exception(env, "java/lang/OutOfMemoryError", "transcribe.cpp text is too large to copy");
        return {};
    }
    return std::string(text, static_cast<size_t>(size));
}

jobjectArray copy_stream_text(JNIEnv * env, const transcribe_session * session) {
    transcribe_stream_text snapshot;
    transcribe_stream_text_init(&snapshot);
    const transcribe_status status = transcribe_stream_get_text(session, &snapshot);
    if (status != TRANSCRIBE_OK) {
        throw_native_error(env, "transcribe_stream_get_text", status);
        return nullptr;
    }

    // Every borrowed pointer is copied before any later transcribe.cpp call.
    const std::string full = copy_text(snapshot.full_text, snapshot.full_text_bytes, env);
    if (env->ExceptionCheck()) return nullptr;
    const std::string committed = copy_text(snapshot.committed_text, snapshot.committed_text_bytes, env);
    if (env->ExceptionCheck()) return nullptr;
    const std::string tentative = copy_text(snapshot.tentative_text, snapshot.tentative_text_bytes, env);
    if (env->ExceptionCheck()) return nullptr;

    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(3, string_class, nullptr);
    env->DeleteLocalRef(string_class);
    if (result == nullptr) return nullptr;

    const std::string values[] = {full, committed, tentative};
    for (jsize index = 0; index < 3; ++index) {
        jstring value = env->NewStringUTF(values[index].c_str());
        if (value == nullptr) return nullptr;
        env->SetObjectArrayElement(result, index, value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) return nullptr;
    }
    return result;
}

jlong native_open(JNIEnv * env, jobject, jstring model_path) {
    try {
        if (model_path == nullptr) {
            throw_exception(env, "java/lang/IllegalArgumentException", "modelPath must not be null");
            return 0;
        }
        const char * path = env->GetStringUTFChars(model_path, nullptr);
        if (path == nullptr) return 0;
        const std::string path_copy(path);
        env->ReleaseStringUTFChars(model_path, path);
        if (path_copy.empty()) {
            throw_exception(env, "java/lang/IllegalArgumentException", "modelPath must not be empty");
            return 0;
        }

        transcribe_status status = transcribe_init_backends_default();
        if (status != TRANSCRIBE_OK) {
            throw_native_error(env, "transcribe_init_backends_default", status);
            return 0;
        }

        transcribe_model_load_params model_params;
        transcribe_model_load_params_init(&model_params);
        model_params.backend = TRANSCRIBE_BACKEND_CPU;

        transcribe_session_params session_params;
        transcribe_session_params_init(&session_params);
        session_params.n_threads = 4;

        transcribe_session * raw_session = nullptr;
        status = transcribe_open(path_copy.c_str(), &model_params, &session_params, &raw_session);
        if (status != TRANSCRIBE_OK) {
            throw_native_error(env, "transcribe_open", status);
            return 0;
        }

        std::unique_ptr<transcribe_session, decltype(&transcribe_session_free)> session(
            raw_session,
            transcribe_session_free
        );

        const jlong handle = g_next_handle.fetch_add(1);
        if (handle <= 0) {
            throw_exception(env, "java/lang/IllegalStateException", "transcribe.cpp handle space exhausted");
            return 0;
        }
        auto native_session = std::make_shared<NativeSession>();
        native_session->session = session.release();
        {
            std::lock_guard<std::mutex> registry_lock(g_sessions_mutex);
            g_sessions.emplace(handle, std::move(native_session));
        }
        return handle;
    } catch (const std::exception & error) {
        throw_exception(env, "java/lang/RuntimeException", error.what());
        return 0;
    } catch (...) {
        throw_exception(env, "java/lang/RuntimeException", "Unexpected native transcribe.cpp failure");
        return 0;
    }
}

void native_begin(JNIEnv * env, jobject, jlong handle, jstring language) {
    try {
        if (language == nullptr) {
            throw_exception(env, "java/lang/IllegalArgumentException", "language must not be null");
            return;
        }
        const char * language_chars = env->GetStringUTFChars(language, nullptr);
        if (language_chars == nullptr) return;
        const std::string language_copy(language_chars);
        env->ReleaseStringUTFChars(language, language_chars);
        if (language_copy != "fr-FR" && language_copy != "en-US") {
            throw_exception(env, "java/lang/IllegalArgumentException", "Unsupported transcription language");
            return;
        }
        const auto native_session = acquire_session(env, handle);
        if (native_session == nullptr) return;
        std::lock_guard<std::mutex> lock(native_session->mutex);
        if (!ensure_open(env, native_session)) return;

        transcribe_run_params run_params;
        transcribe_run_params_init(&run_params);
        run_params.task = TRANSCRIBE_TASK_TRANSCRIBE;
        native_session->language = language_copy;
        run_params.language = native_session->language.c_str();

        transcribe_stream_params stream_params;
        transcribe_stream_params_init(&stream_params);
        const transcribe_status status = transcribe_stream_begin(
            native_session->session,
            &run_params,
            &stream_params
        );
        if (status != TRANSCRIBE_OK) throw_native_error(env, "transcribe_stream_begin", status);
    } catch (const std::exception & error) {
        throw_exception(env, "java/lang/RuntimeException", error.what());
    } catch (...) {
        throw_exception(env, "java/lang/RuntimeException", "Unexpected native transcribe.cpp failure");
    }
}

jobjectArray native_feed(JNIEnv * env, jobject, jlong handle, jfloatArray samples) {
    try {
        if (samples == nullptr || env->GetArrayLength(samples) <= 0) {
            throw_exception(env, "java/lang/IllegalArgumentException", "samples must not be null or empty");
            return nullptr;
        }
        const auto native_session = acquire_session(env, handle);
        if (native_session == nullptr) return nullptr;
        std::lock_guard<std::mutex> lock(native_session->mutex);
        if (!ensure_open(env, native_session)) return nullptr;

        jfloat * pcm = env->GetFloatArrayElements(samples, nullptr);
        if (pcm == nullptr) return nullptr;
        transcribe_stream_update update;
        transcribe_stream_update_init(&update);
        const transcribe_status status = transcribe_stream_feed(
            native_session->session,
            pcm,
            env->GetArrayLength(samples),
            &update
        );
        if (status != TRANSCRIBE_OK) {
            env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
            throw_native_error(env, "transcribe_stream_feed", status);
            return nullptr;
        }

        jobjectArray text = copy_stream_text(env, native_session->session);
        env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
        return text;
    } catch (const std::exception & error) {
        throw_exception(env, "java/lang/RuntimeException", error.what());
        return nullptr;
    } catch (...) {
        throw_exception(env, "java/lang/RuntimeException", "Unexpected native transcribe.cpp failure");
        return nullptr;
    }
}

void native_accept_pcm16(JNIEnv * env, jobject, jlong handle, jbyteArray bytes, jint length_bytes) {
    try {
        if (bytes == nullptr) {
            throw_exception(env, "java/lang/IllegalArgumentException", "PCM16 buffer must not be null");
            return;
        }
        const jsize capacity = env->GetArrayLength(bytes);
        if (length_bytes <= 0 || (length_bytes % 2) != 0 || length_bytes > capacity ||
            static_cast<std::size_t>(length_bytes) > kMaxPcmBytes) {
            throw_exception(env, "java/lang/IllegalArgumentException", "PCM16 byte length is invalid or exceeds 10 seconds");
            return;
        }

        const auto native_session = acquire_session(env, handle);
        if (native_session == nullptr) return;
        std::lock_guard<std::mutex> lock(native_session->mutex);
        if (!ensure_open(env, native_session)) return;

        const std::size_t sample_count = static_cast<std::size_t>(length_bytes) / 2;
        std::vector<float> pcm(sample_count);
        jbyte * raw = env->GetByteArrayElements(bytes, nullptr);
        if (raw == nullptr) return;
        for (std::size_t i = 0; i < sample_count; ++i) {
            const auto low = static_cast<std::uint8_t>(raw[i * 2]);
            const auto high = static_cast<std::uint8_t>(raw[i * 2 + 1]);
            const auto sample_bits = static_cast<std::uint16_t>(low | (static_cast<std::uint16_t>(high) << 8));
            const auto sample = static_cast<std::int16_t>(sample_bits);
            pcm[i] = static_cast<float>(sample) / 32768.0f;
        }
        env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);

        transcribe_stream_update update;
        transcribe_stream_update_init(&update);
        const transcribe_status status = transcribe_stream_feed(
            native_session->session,
            pcm.data(),
            pcm.size(),
            &update
        );
        if (status != TRANSCRIBE_OK) throw_native_error(env, "transcribe_stream_feed", status);
    } catch (const std::bad_alloc &) {
        throw_exception(env, "java/lang/OutOfMemoryError", "Could not allocate PCM16 conversion buffer");
    } catch (const std::exception & error) {
        throw_exception(env, "java/lang/RuntimeException", error.what());
    } catch (...) {
        throw_exception(env, "java/lang/RuntimeException", "Unexpected native transcribe.cpp failure");
    }
}

bool validate_token_window(JNIEnv * env, jint first_token_index, jint max_tokens) {
    if (first_token_index < 0 || max_tokens < 0 || max_tokens > kMaxTokenWindow) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Token window bounds are invalid");
        return false;
    }
    return true;
}

jobject copy_token_snapshot(JNIEnv * env, transcribe_session * session,
                            jint requested_first, jint max_tokens, bool finalize) {
    if (!validate_token_window(env, requested_first, max_tokens)) return nullptr;
    if (finalize) {
        transcribe_stream_update update;
        transcribe_stream_update_init(&update);
        const transcribe_status status = transcribe_stream_finalize(session, &update);
        if (status != TRANSCRIBE_OK) {
            throw_native_error(env, "transcribe_stream_finalize", status);
            return nullptr;
        }
    }

    transcribe_stream_text text;
    transcribe_stream_text_init(&text);
    transcribe_status status = transcribe_stream_get_text(session, &text);
    if (status != TRANSCRIBE_OK) {
        throw_native_error(env, "transcribe_stream_get_text", status);
        return nullptr;
    }
    if (text.full_text_bytes > static_cast<uint64_t>(std::numeric_limits<jsize>::max())) {
        throw_exception(env, "java/lang/OutOfMemoryError", "Handy transcript exceeds JNI array limits");
        return nullptr;
    }
    if (text.full_text_bytes > 0 && text.full_text == nullptr) {
        throw_exception(env, "java/lang/IllegalStateException", "Handy returned a missing transcript buffer");
        return nullptr;
    }
    std::vector<jbyte> full_text(static_cast<std::size_t>(text.full_text_bytes));
    if (!full_text.empty() && text.full_text != nullptr) {
        std::copy_n(reinterpret_cast<const jbyte *>(text.full_text), full_text.size(), full_text.begin());
    }

    const int raw_token_count = transcribe_n_tokens(session);
    if (raw_token_count < 0) {
        throw_exception(env, "java/lang/IllegalStateException", "Handy returned a negative token count");
        return nullptr;
    }
    const jint total_tokens = static_cast<jint>(raw_token_count);
    const jint first_token = std::min(requested_first, total_tokens);
    const int raw_committed_count = transcribe_stream_n_committed_tokens(session);
    const jint committed_tokens = std::clamp(raw_committed_count, 0, raw_token_count);
    const jint copied_count = std::min(max_tokens, total_tokens - first_token);

    std::vector<jbyte> token_bytes;
    std::vector<jint> token_byte_ends;
    std::vector<jlong> token_starts;
    std::vector<jlong> token_ends;
    token_byte_ends.reserve(static_cast<std::size_t>(copied_count));
    token_starts.reserve(static_cast<std::size_t>(copied_count));
    token_ends.reserve(static_cast<std::size_t>(copied_count));
    for (jint offset = 0; offset < copied_count; ++offset) {
        transcribe_token token;
        transcribe_token_init(&token);
        status = transcribe_get_token(session, first_token + offset, &token);
        if (status != TRANSCRIBE_OK) {
            throw_native_error(env, "transcribe_get_token", status);
            return nullptr;
        }
        const std::size_t token_size = token.text == nullptr ? 0 : std::strlen(token.text);
        if (token_size > static_cast<std::size_t>(std::numeric_limits<jsize>::max()) - token_bytes.size()) {
            throw_exception(env, "java/lang/OutOfMemoryError", "Handy token window exceeds JNI array limits");
            return nullptr;
        }
        if (token_size > 0) {
            const auto * token_data = reinterpret_cast<const jbyte *>(token.text);
            token_bytes.insert(token_bytes.end(), token_data, token_data + token_size);
        }
        token_byte_ends.push_back(static_cast<jint>(token_bytes.size()));
        token_starts.push_back(static_cast<jlong>(token.t0_ms));
        token_ends.push_back(static_cast<jlong>(token.t1_ms));
    }

    const jsize full_size = static_cast<jsize>(full_text.size());
    const jsize token_size = static_cast<jsize>(token_bytes.size());
    const jsize row_count = static_cast<jsize>(copied_count);
    jbyteArray full_array = env->NewByteArray(full_size);
    if (full_array == nullptr) return nullptr;
    jbyteArray token_array = env->NewByteArray(token_size);
    if (token_array == nullptr) return nullptr;
    jintArray byte_ends_array = env->NewIntArray(row_count);
    if (byte_ends_array == nullptr) return nullptr;
    jlongArray starts_array = env->NewLongArray(row_count);
    if (starts_array == nullptr) return nullptr;
    jlongArray ends_array = env->NewLongArray(row_count);
    if (ends_array == nullptr) return nullptr;
    if (full_size > 0) env->SetByteArrayRegion(full_array, 0, full_size, full_text.data());
    if (env->ExceptionCheck()) return nullptr;
    if (token_size > 0) env->SetByteArrayRegion(token_array, 0, token_size, token_bytes.data());
    if (env->ExceptionCheck()) return nullptr;
    if (row_count > 0) {
        env->SetIntArrayRegion(byte_ends_array, 0, row_count, token_byte_ends.data());
        if (env->ExceptionCheck()) return nullptr;
        env->SetLongArrayRegion(starts_array, 0, row_count, token_starts.data());
        if (env->ExceptionCheck()) return nullptr;
        env->SetLongArrayRegion(ends_array, 0, row_count, token_ends.data());
        if (env->ExceptionCheck()) return nullptr;
    }

    jclass window_class = env->FindClass(kHandyTokenWindowClass);
    if (window_class == nullptr) return nullptr;
    const jmethodID constructor = env->GetMethodID(window_class, "<init>", "([BIII[B[I[J[J)V");
    if (constructor == nullptr) {
        env->DeleteLocalRef(window_class);
        return nullptr;
    }
    jobject result = env->NewObject(window_class, constructor, full_array, first_token,
                                    total_tokens, committed_tokens, token_array,
                                    byte_ends_array, starts_array, ends_array);
    env->DeleteLocalRef(window_class);
    env->DeleteLocalRef(full_array);
    env->DeleteLocalRef(token_array);
    env->DeleteLocalRef(byte_ends_array);
    env->DeleteLocalRef(starts_array);
    env->DeleteLocalRef(ends_array);
    return result;
}

jobject native_token_snapshot(JNIEnv * env, jobject, jlong handle, jint first_token_index,
                              jint max_tokens) {
    try {
        const auto native_session = acquire_session(env, handle);
        if (native_session == nullptr) return nullptr;
        std::lock_guard<std::mutex> lock(native_session->mutex);
        if (!ensure_open(env, native_session)) return nullptr;
        return copy_token_snapshot(env, native_session->session, first_token_index, max_tokens, false);
    } catch (const std::bad_alloc &) {
        throw_exception(env, "java/lang/OutOfMemoryError", "Could not allocate Handy token snapshot");
        return nullptr;
    } catch (const std::exception & error) {
        throw_exception(env, "java/lang/RuntimeException", error.what());
        return nullptr;
    } catch (...) {
        throw_exception(env, "java/lang/RuntimeException", "Unexpected native transcribe.cpp failure");
        return nullptr;
    }
}

jobject native_finish_token_snapshot(JNIEnv * env, jobject, jlong handle, jint first_token_index,
                                     jint max_tokens) {
    try {
        const auto native_session = acquire_session(env, handle);
        if (native_session == nullptr) return nullptr;
        std::lock_guard<std::mutex> lock(native_session->mutex);
        if (!ensure_open(env, native_session)) return nullptr;
        return copy_token_snapshot(env, native_session->session, first_token_index, max_tokens, true);
    } catch (const std::bad_alloc &) {
        throw_exception(env, "java/lang/OutOfMemoryError", "Could not allocate Handy token snapshot");
        return nullptr;
    } catch (const std::exception & error) {
        throw_exception(env, "java/lang/RuntimeException", error.what());
        return nullptr;
    } catch (...) {
        throw_exception(env, "java/lang/RuntimeException", "Unexpected native transcribe.cpp failure");
        return nullptr;
    }
}

jobjectArray native_get_text(JNIEnv * env, jobject, jlong handle) {
    try {
        const auto native_session = acquire_session(env, handle);
        if (native_session == nullptr) return nullptr;
        std::lock_guard<std::mutex> lock(native_session->mutex);
        if (!ensure_open(env, native_session)) return nullptr;
        return copy_stream_text(env, native_session->session);
    } catch (const std::exception & error) {
        throw_exception(env, "java/lang/RuntimeException", error.what());
        return nullptr;
    } catch (...) {
        throw_exception(env, "java/lang/RuntimeException", "Unexpected native transcribe.cpp failure");
        return nullptr;
    }
}

jobjectArray native_finish(JNIEnv * env, jobject, jlong handle) {
    try {
        const auto native_session = acquire_session(env, handle);
        if (native_session == nullptr) return nullptr;
        std::lock_guard<std::mutex> lock(native_session->mutex);
        if (!ensure_open(env, native_session)) return nullptr;

        transcribe_stream_update update;
        transcribe_stream_update_init(&update);
        const transcribe_status status = transcribe_stream_finalize(native_session->session, &update);
        if (status != TRANSCRIBE_OK) {
            throw_native_error(env, "transcribe_stream_finalize", status);
            return nullptr;
        }
        return copy_stream_text(env, native_session->session);
    } catch (const std::exception & error) {
        throw_exception(env, "java/lang/RuntimeException", error.what());
        return nullptr;
    } catch (...) {
        throw_exception(env, "java/lang/RuntimeException", "Unexpected native transcribe.cpp failure");
        return nullptr;
    }
}

void native_reset(JNIEnv * env, jobject, jlong handle) {
    try {
        const auto native_session = acquire_session(env, handle);
        if (native_session == nullptr) return;
        std::lock_guard<std::mutex> lock(native_session->mutex);
        if (!ensure_open(env, native_session)) return;
        transcribe_stream_reset(native_session->session);
    } catch (const std::exception & error) {
        throw_exception(env, "java/lang/RuntimeException", error.what());
    } catch (...) {
        throw_exception(env, "java/lang/RuntimeException", "Unexpected native transcribe.cpp failure");
    }
}

void native_free(JNIEnv * env, jobject, jlong handle) {
    try {
        if (handle <= 0) {
            throw_closed_handle(env);
            return;
        }

        std::shared_ptr<NativeSession> native_session;
        {
            std::lock_guard<std::mutex> registry_lock(g_sessions_mutex);
            const auto found = g_sessions.find(handle);
            if (found == g_sessions.end()) {
                throw_closed_handle(env);
                return;
            }
            native_session = found->second;
            g_sessions.erase(found);
        }

        std::lock_guard<std::mutex> lock(native_session->mutex);
        if (!ensure_open(env, native_session)) return;
        transcribe_session_free(native_session->session);
        native_session->session = nullptr;
        native_session->closed = true;
    } catch (const std::exception & error) {
        throw_exception(env, "java/lang/RuntimeException", error.what());
    } catch (...) {
        throw_exception(env, "java/lang/RuntimeException", "Unexpected native transcribe.cpp failure");
    }
}

const JNINativeMethod kMethods[] = {
    {const_cast<char *>("open"), const_cast<char *>("(Ljava/lang/String;)J"), reinterpret_cast<void *>(native_open)},
    {const_cast<char *>("begin"), const_cast<char *>("(JLjava/lang/String;)V"), reinterpret_cast<void *>(native_begin)},
    {const_cast<char *>("feed"), const_cast<char *>("(J[F)[Ljava/lang/String;"), reinterpret_cast<void *>(native_feed)},
    {const_cast<char *>("acceptPcm16"), const_cast<char *>("(J[BI)V"), reinterpret_cast<void *>(native_accept_pcm16)},
    {const_cast<char *>("tokenSnapshot"), const_cast<char *>("(JII)Lcom/kafkasl/phonewhisper/meeting/HandyTokenWindow;"), reinterpret_cast<void *>(native_token_snapshot)},
    {const_cast<char *>("finishTokenSnapshot"), const_cast<char *>("(JII)Lcom/kafkasl/phonewhisper/meeting/HandyTokenWindow;"), reinterpret_cast<void *>(native_finish_token_snapshot)},
    {const_cast<char *>("getText"), const_cast<char *>("(J)[Ljava/lang/String;"), reinterpret_cast<void *>(native_get_text)},
    {const_cast<char *>("finish"), const_cast<char *>("(J)[Ljava/lang/String;"), reinterpret_cast<void *>(native_finish)},
    {const_cast<char *>("reset"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(native_reset)},
    {const_cast<char *>("free"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(native_free)},
};

} // namespace

JNIEXPORT jint JNI_OnLoad(JavaVM * vm, void *) {
    JNIEnv * env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }
    jclass bindings_class = env->FindClass(kBindingsClass);
    if (bindings_class == nullptr) return JNI_ERR;
    const jint result = env->RegisterNatives(
        bindings_class,
        kMethods,
        static_cast<jint>(std::size(kMethods))
    );
    env->DeleteLocalRef(bindings_class);
    return result == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
