// SPDX-License-Identifier: Apache-2.0
#include "meeting_diarization_jni.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

#include "diar_pipeline.h"
#include "meeting_native_logic.h"
#include "runtime.h"

namespace {
namespace asr = nemo_speech::asr;
constexpr char kBindingsClass[] =
    "com/kafkasl/phonewhisper/meeting/DiarizationNative$JniBindings";
constexpr char kFrameWindowClass[] =
    "com/kafkasl/phonewhisper/meeting/DiarizationFrameWindow";
constexpr std::size_t kMaxPcmBytes = dictai::meeting::kMaxPcmBytes;
constexpr jint kMaxFrameWindow = 16384;

ggml_runtime::Params backend_params() {
    ggml_runtime::Params params;
    params.use_gpu = false;
    params.gpu_device_idx = 0;
    params.pe_bin_path = const_cast<char*>("");
    params.cpu_threads = 1;
    return params;
}

struct DiarizationSession {
    std::mutex mutex;
    ggml_runtime::BackendManager backend;
    asr::DiarModel model;
    asr::DiarStream stream;
    bool finished = false;
    bool closed = false;

    explicit DiarizationSession(const std::string& path)
        : backend(backend_params()), model(backend, path),
          stream(model, model.resolved_geometry(asr::DiarGeometry{})) {
        if (model.cfg().sample_rate != 16000) {
            throw std::invalid_argument("Diarization model must use 16 kHz audio");
        }
    }
};

std::mutex g_sessions_mutex;
std::unordered_map<jlong, std::shared_ptr<DiarizationSession>> g_sessions;
std::atomic<jlong> g_next_handle{1};

void throw_java(JNIEnv* env, const char* class_name, const std::string& message) {
    if (env->ExceptionCheck()) return;
    jclass exception_class = env->FindClass(class_name);
    if (exception_class == nullptr) return;
    env->ThrowNew(exception_class, message.c_str());
    env->DeleteLocalRef(exception_class);
}

void throw_failure(JNIEnv* env, const std::exception& error, bool invalid_argument = false) {
    throw_java(env,
               invalid_argument ? "java/lang/IllegalArgumentException"
                                : "java/lang/IllegalStateException",
               error.what());
}

std::shared_ptr<DiarizationSession> acquire_session(JNIEnv* env, jlong handle) {
    if (handle <= 0) {
        throw_java(env, "java/lang/IllegalStateException", "Diarization handle is closed or invalid");
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    const auto found = g_sessions.find(handle);
    if (found == g_sessions.end()) {
        throw_java(env, "java/lang/IllegalStateException", "Diarization handle is closed or invalid");
        return nullptr;
    }
    return found->second;
}

bool ensure_open(JNIEnv* env, const std::shared_ptr<DiarizationSession>& session) {
    if (session->closed) {
        throw_java(env, "java/lang/IllegalStateException", "Diarization handle is closed");
        return false;
    }
    return true;
}

std::string java_string_to_utf8(JNIEnv* env, jstring value) {
    if (value == nullptr) throw std::invalid_argument("diarPath must not be null");
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) throw std::runtime_error("diarPath could not be read");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    if (result.empty()) throw std::invalid_argument("diarPath must not be empty");
    return result;
}

jlong native_open(JNIEnv* env, jobject, jstring diar_path) {
    try {
        const std::string path = java_string_to_utf8(env, diar_path);
        auto session = std::make_shared<DiarizationSession>(path);
        const jlong handle = g_next_handle.fetch_add(1);
        if (handle <= 0) throw std::overflow_error("Diarization handle space exhausted");
        {
            std::lock_guard<std::mutex> lock(g_sessions_mutex);
            g_sessions.emplace(handle, std::move(session));
        }
        return handle;
    } catch (const std::invalid_argument& error) {
        throw_failure(env, error, true);
    } catch (const std::exception& error) {
        throw_failure(env, error);
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Diarization model could not be loaded");
    }
    return 0;
}

void native_accept_pcm16(JNIEnv* env, jobject, jlong handle, jbyteArray bytes, jint length_bytes) {
    try {
        if (bytes == nullptr) throw std::invalid_argument("PCM16 buffer must not be null");
        const jsize capacity = env->GetArrayLength(bytes);
        const std::size_t sample_count = dictai::meeting::validate_pcm16_length(
            static_cast<std::size_t>(capacity), static_cast<std::int64_t>(length_bytes));

        const auto session = acquire_session(env, handle);
        if (session == nullptr) return;
        std::lock_guard<std::mutex> lock(session->mutex);
        if (!ensure_open(env, session)) return;
        if (session->finished) throw std::logic_error("Diarization session is already finished");

        jbyte* raw = env->GetByteArrayElements(bytes, nullptr);
        if (raw == nullptr) return;
        std::vector<float> pcm;
        try {
            pcm = dictai::meeting::pcm16le_to_float(
                reinterpret_cast<const std::uint8_t*>(raw), static_cast<std::size_t>(capacity),
                static_cast<std::int64_t>(length_bytes));
            if (pcm.size() != sample_count)
                throw std::logic_error("PCM16 conversion produced an unexpected sample count");
        } catch (...) {
            env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);
            throw;
        }
        env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);
        session->stream.feed_audio(pcm.data(), pcm.size());
    } catch (const std::invalid_argument& error) {
        throw_failure(env, error, true);
    } catch (const std::exception& error) {
        throw_failure(env, error);
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Diarization audio could not be processed");
    }
}

jobject native_snapshot(JNIEnv* env, jobject, jlong handle, jlong requested_first, jint max_frames) {
    try {
        if (requested_first < 0 || max_frames < 0 || max_frames > kMaxFrameWindow)
            throw std::invalid_argument("Diarization frame window bounds are invalid");
        const auto session = acquire_session(env, handle);
        if (session == nullptr) return nullptr;
        std::lock_guard<std::mutex> lock(session->mutex);
        if (!ensure_open(env, session)) return nullptr;

        const std::int64_t total_frames = session->stream.n_frames();
        const std::int64_t retained_base = session->stream.frame_probs_base();
        const std::int64_t first_frame = std::clamp<std::int64_t>(
            std::max<std::int64_t>(requested_first, retained_base), 0, total_frames);
        const std::int64_t available_after_first = total_frames - first_frame;
        const std::int64_t copied_frames = std::min<std::int64_t>(max_frames, available_after_first);
        const std::int64_t end_frame = first_frame + copied_frames;
        const int speakers = session->model.cfg().num_speakers;
        const double seconds_per_frame = session->stream.seconds_per_frame();
        if (speakers <= 0 || !std::isfinite(seconds_per_frame) || seconds_per_frame <= 0.0)
            throw std::runtime_error("Diarization model returned invalid frame geometry");

        const std::size_t first_offset = static_cast<std::size_t>(first_frame - retained_base) *
                                         static_cast<std::size_t>(speakers);
        const std::size_t value_count = static_cast<std::size_t>(end_frame - first_frame) *
                                        static_cast<std::size_t>(speakers);
        const auto& retained = session->stream.frame_probs();
        if (first_offset > retained.size() || value_count > retained.size() - first_offset ||
            value_count > static_cast<std::size_t>(std::numeric_limits<jsize>::max()))
            throw std::runtime_error("Diarization retained frame window is inconsistent");

        jfloatArray probabilities = env->NewFloatArray(static_cast<jsize>(value_count));
        if (probabilities == nullptr) return nullptr;
        if (value_count > 0) {
            env->SetFloatArrayRegion(probabilities, 0, static_cast<jsize>(value_count),
                                     retained.data() + first_offset);
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(probabilities);
                return nullptr;
            }
        }

        jclass window_class = env->FindClass(kFrameWindowClass);
        if (window_class == nullptr) {
            env->DeleteLocalRef(probabilities);
            return nullptr;
        }
        const jmethodID constructor = env->GetMethodID(window_class, "<init>", "(JD[FIJJ)V");
        if (constructor == nullptr) {
            env->DeleteLocalRef(window_class);
            env->DeleteLocalRef(probabilities);
            return nullptr;
        }
        const jlong stable_frames = std::clamp<std::int64_t>(
            session->stream.stable_frames(), 0, total_frames);
        jobject result = env->NewObject(window_class, constructor, static_cast<jlong>(first_frame),
                                        static_cast<jdouble>(seconds_per_frame), probabilities,
                                        static_cast<jint>(speakers), stable_frames,
                                        static_cast<jlong>(total_frames));
        env->DeleteLocalRef(window_class);
        env->DeleteLocalRef(probabilities);
        return result;
    } catch (const std::invalid_argument& error) {
        throw_failure(env, error, true);
    } catch (const std::exception& error) {
        throw_failure(env, error);
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Diarization frames could not be read");
    }
    return nullptr;
}

void native_finish(JNIEnv* env, jobject, jlong handle) {
    try {
        const auto session = acquire_session(env, handle);
        if (session == nullptr) return;
        std::lock_guard<std::mutex> lock(session->mutex);
        if (!ensure_open(env, session)) return;
        if (session->finished) return;
        session->stream.finish();
        session->finished = true;
    } catch (const std::exception& error) {
        throw_failure(env, error);
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Diarization session could not be finished");
    }
}

void native_close(JNIEnv* env, jobject, jlong handle) {
    try {
        if (handle <= 0) return;
        std::shared_ptr<DiarizationSession> session;
        {
            std::lock_guard<std::mutex> lock(g_sessions_mutex);
            const auto found = g_sessions.find(handle);
            if (found == g_sessions.end()) return;
            session = std::move(found->second);
            g_sessions.erase(found);
        }
        std::lock_guard<std::mutex> lock(session->mutex);
        session->closed = true;
    } catch (const std::exception& error) {
        throw_failure(env, error);
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Diarization session could not be closed");
    }
}

JNINativeMethod kMethods[] = {
    {const_cast<char*>("open"), const_cast<char*>("(Ljava/lang/String;)J"),
     reinterpret_cast<void*>(native_open)},
    {const_cast<char*>("acceptPcm16"), const_cast<char*>("(J[BI)V"),
     reinterpret_cast<void*>(native_accept_pcm16)},
    {const_cast<char*>("snapshot"), const_cast<char*>("(JJI)Lcom/kafkasl/phonewhisper/meeting/DiarizationFrameWindow;"),
     reinterpret_cast<void*>(native_snapshot)},
    {const_cast<char*>("finish"), const_cast<char*>("(J)V"), reinterpret_cast<void*>(native_finish)},
    {const_cast<char*>("close"), const_cast<char*>("(J)V"), reinterpret_cast<void*>(native_close)},
};

}  // namespace

namespace dictai::meeting {

jint register_diarization_natives(JNIEnv* env) {
    jclass bindings = env->FindClass(kBindingsClass);
    if (bindings == nullptr) return JNI_ERR;
    const jint result = env->RegisterNatives(
        bindings, kMethods, static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0])));
    env->DeleteLocalRef(bindings);
    return result;
}

}  // namespace dictai::meeting
