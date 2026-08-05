#include <jni.h>

#include <atomic>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>

#include "transcribe.h"

namespace {

constexpr char kBindingsClass[] = "com/kafkasl/phonewhisper/TranscribeCppNative$JniBindings";
constexpr char kNativeExceptionClass[] = "com/kafkasl/phonewhisper/TranscribeCppNativeException";

struct NativeSession {
    std::mutex mutex;
    transcribe_session * session = nullptr;
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

void native_begin(JNIEnv * env, jobject, jlong handle) {
    try {
        const auto native_session = acquire_session(env, handle);
        if (native_session == nullptr) return;
        std::lock_guard<std::mutex> lock(native_session->mutex);
        if (!ensure_open(env, native_session)) return;

        transcribe_run_params run_params;
        transcribe_run_params_init(&run_params);
        run_params.task = TRANSCRIBE_TASK_TRANSCRIBE;
        run_params.language = "fr-FR";

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
    {const_cast<char *>("begin"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(native_begin)},
    {const_cast<char *>("feed"), const_cast<char *>("(J[F)[Ljava/lang/String;"), reinterpret_cast<void *>(native_feed)},
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
