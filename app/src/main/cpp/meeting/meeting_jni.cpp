// SPDX-License-Identifier: Apache-2.0
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <deque>
#include <iterator>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include <unistd.h>

#include "meeting_native_logic.h"
#include "recognizer.h"

namespace {
namespace asr = nemo_speech::asr;
using dictai::meeting::Update;
using dictai::meeting::UpdateSequencer;
using dictai::meeting::Word;

constexpr int kSampleRate = 16000;
constexpr std::int64_t kForcedEndpointMs = 30000;
constexpr std::size_t kMaxPendingFinals = dictai::meeting::kMaxPendingFinals;
constexpr char kBindingsClass[] =
    "com/kafkasl/phonewhisper/meeting/LoadedMeetingNativeCalls";

enum class NativeOperation { kOpen, kAccept, kFinish, kClose };

class ScopedStringChars {
   public:
    ScopedStringChars(JNIEnv* env, jstring value) : env_(env), value_(value) {
        if (value_ != nullptr) chars_ = env_->GetStringChars(value_, nullptr);
    }
    ~ScopedStringChars() {
        if (chars_ != nullptr) env_->ReleaseStringChars(value_, chars_);
    }
    ScopedStringChars(const ScopedStringChars&) = delete;
    ScopedStringChars& operator=(const ScopedStringChars&) = delete;

    const jchar* get() const { return chars_; }

   private:
    JNIEnv* env_;
    jstring value_;
    const jchar* chars_ = nullptr;
};

std::int64_t
seconds_to_ms(float seconds) {
    if (!std::isfinite(seconds) || seconds <= 0.0f) return 0;
    const double milliseconds = static_cast<double>(seconds) * 1000.0;
    if (milliseconds >= static_cast<double>(std::numeric_limits<std::int64_t>::max()))
        return std::numeric_limits<std::int64_t>::max();
    return static_cast<std::int64_t>(std::llround(milliseconds));
}

std::string
java_string_to_utf8(JNIEnv* env, jstring value) {
    if (value == nullptr) throw std::invalid_argument("Meeting argument is missing");
    const jsize length = env->GetStringLength(value);
    ScopedStringChars pinned_chars(env, value);
    const jchar* chars = pinned_chars.get();
    if (chars == nullptr) throw std::runtime_error("Meeting argument could not be read");

    std::string result;
    result.reserve(static_cast<std::size_t>(length));
    auto append_code_point = [&result](std::uint32_t cp) {
        if (cp <= 0x7f) {
            result.push_back(static_cast<char>(cp));
        } else if (cp <= 0x7ff) {
            result.push_back(static_cast<char>(0xc0 | (cp >> 6)));
            result.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
        } else if (cp <= 0xffff) {
            result.push_back(static_cast<char>(0xe0 | (cp >> 12)));
            result.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
            result.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
        } else {
            result.push_back(static_cast<char>(0xf0 | (cp >> 18)));
            result.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3f)));
            result.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
            result.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
        }
    };

    for (jsize i = 0; i < length; ++i) {
        std::uint32_t cp = chars[i];
        if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < length &&
            chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
            cp = 0x10000 + ((cp - 0xd800) << 10) + (chars[++i] - 0xdc00);
        } else if (cp >= 0xd800 && cp <= 0xdfff) {
            cp = 0xfffd;
        }
        append_code_point(cp);
    }
    return result;
}

jstring
utf8_to_java_string(JNIEnv* env, const std::string& value) {
    std::vector<jchar> chars;
    chars.reserve(value.size());
    auto append_code_point = [&chars](std::uint32_t cp) {
        if (cp <= 0xffff) {
            chars.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            chars.push_back(static_cast<jchar>(0xd800 + (cp >> 10)));
            chars.push_back(static_cast<jchar>(0xdc00 + (cp & 0x3ff)));
        }
    };

    std::size_t i = 0;
    while (i < value.size()) {
        const auto first = static_cast<std::uint8_t>(value[i]);
        std::uint32_t cp = 0xfffd;
        std::size_t width = 1;
        if (first <= 0x7f) {
            cp = first;
        } else if (first >= 0xc2 && first <= 0xdf && i + 1 < value.size()) {
            const auto b1 = static_cast<std::uint8_t>(value[i + 1]);
            if ((b1 & 0xc0) == 0x80) {
                cp = ((first & 0x1f) << 6) | (b1 & 0x3f);
                width = 2;
            }
        } else if (first >= 0xe0 && first <= 0xef && i + 2 < value.size()) {
            const auto b1 = static_cast<std::uint8_t>(value[i + 1]);
            const auto b2 = static_cast<std::uint8_t>(value[i + 2]);
            if ((b1 & 0xc0) == 0x80 && (b2 & 0xc0) == 0x80 &&
                !(first == 0xe0 && b1 < 0xa0) && !(first == 0xed && b1 >= 0xa0)) {
                cp = ((first & 0x0f) << 12) | ((b1 & 0x3f) << 6) | (b2 & 0x3f);
                width = 3;
            }
        } else if (first >= 0xf0 && first <= 0xf4 && i + 3 < value.size()) {
            const auto b1 = static_cast<std::uint8_t>(value[i + 1]);
            const auto b2 = static_cast<std::uint8_t>(value[i + 2]);
            const auto b3 = static_cast<std::uint8_t>(value[i + 3]);
            if ((b1 & 0xc0) == 0x80 && (b2 & 0xc0) == 0x80 && (b3 & 0xc0) == 0x80 &&
                !(first == 0xf0 && b1 < 0x90) && !(first == 0xf4 && b1 > 0x8f)) {
                cp = ((first & 0x07) << 18) | ((b1 & 0x3f) << 12) | ((b2 & 0x3f) << 6) |
                     (b3 & 0x3f);
                width = 4;
            }
        }
        append_code_point(cp);
        i += width;
    }

    if (chars.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max()))
        throw std::length_error("Meeting transcript is too large");
    static const jchar empty = 0;
    return env->NewString(chars.empty() ? &empty : chars.data(), static_cast<jsize>(chars.size()));
}

void
throw_java(JNIEnv* env, const char* class_name, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass exception_class = env->FindClass(class_name);
    if (exception_class == nullptr) return;
    env->ThrowNew(exception_class, message);
    env->DeleteLocalRef(exception_class);
}

void
throw_native_error(JNIEnv* env, const std::exception& error, NativeOperation operation) {
    const bool invalid_argument = dynamic_cast<const std::invalid_argument*>(&error) != nullptr;
    if (invalid_argument) {
        const char* message = operation == NativeOperation::kOpen
                                  ? "Meeting model paths or language are invalid"
                                  : operation == NativeOperation::kAccept
                                        ? "Meeting handle or PCM buffer is invalid"
                                        : "Meeting handle is invalid";
        throw_java(env, "java/lang/IllegalArgumentException", message);
        return;
    }
    const char* message = operation == NativeOperation::kOpen
                              ? "Meeting models could not be loaded"
                              : operation == NativeOperation::kAccept
                                    ? "Meeting audio could not be processed"
                                    : operation == NativeOperation::kFinish
                                          ? "Meeting session could not be finished"
                                          : "Meeting session could not be closed";
    throw_java(env, "java/lang/IllegalStateException", message);
}

Update
copy_result(const asr::Result& result, std::int64_t stable_through_ms) {
    Update update;
    update.transcript = result.alternatives.empty() ? std::string()
                                                   : result.alternatives.front().transcript;
    update.is_final = result.is_final;
    update.stable_speaker_through_ms = stable_through_ms;
    update.audio_processed_ms = seconds_to_ms(result.audio_processed);
    if (!result.alternatives.empty()) {
        const auto& source_words = result.alternatives.front().words;
        update.words.reserve(source_words.size());
        for (const auto& word : source_words) {
            update.words.push_back(Word{word.word, word.start_time, word.end_time, word.speaker_tag});
        }
    }
    return update;
}

jobject
make_update_list(JNIEnv* env, const std::vector<Update>& updates) {
    jclass list_class = env->FindClass("java/util/ArrayList");
    if (list_class == nullptr) return nullptr;
    const jmethodID list_ctor = env->GetMethodID(list_class, "<init>", "()V");
    if (list_ctor == nullptr) {
        env->DeleteLocalRef(list_class);
        return nullptr;
    }
    const jmethodID list_add = env->GetMethodID(list_class, "add", "(Ljava/lang/Object;)Z");
    if (list_add == nullptr) {
        env->DeleteLocalRef(list_class);
        return nullptr;
    }
    jobject list = env->NewObject(list_class, list_ctor);
    if (list == nullptr) {
        env->DeleteLocalRef(list_class);
        return nullptr;
    }

    jclass word_class = env->FindClass("com/kafkasl/phonewhisper/meeting/MeetingWord");
    if (word_class == nullptr) {
        env->DeleteLocalRef(list_class);
        env->DeleteLocalRef(list);
        return nullptr;
    }
    jclass update_class = env->FindClass("com/kafkasl/phonewhisper/meeting/MeetingNativeUpdate");
    if (update_class == nullptr) {
        env->DeleteLocalRef(word_class);
        env->DeleteLocalRef(list_class);
        env->DeleteLocalRef(list);
        return nullptr;
    }
    const jmethodID word_ctor = env->GetMethodID(word_class, "<init>", "(Ljava/lang/String;JJI)V");
    if (word_ctor == nullptr) {
        env->DeleteLocalRef(word_class);
        env->DeleteLocalRef(update_class);
        env->DeleteLocalRef(list_class);
        env->DeleteLocalRef(list);
        return nullptr;
    }
    const jmethodID update_ctor = env->GetMethodID(
        update_class, "<init>", "(JJLjava/util/List;Ljava/lang/String;ZJJ)V");
    if (update_ctor == nullptr) {
        env->DeleteLocalRef(word_class);
        env->DeleteLocalRef(update_class);
        env->DeleteLocalRef(list_class);
        env->DeleteLocalRef(list);
        return nullptr;
    }

    for (const auto& update : updates) {
        jobject words = env->NewObject(list_class, list_ctor);
        if (words == nullptr) break;
        for (const auto& word : update.words) {
            jstring word_text = utf8_to_java_string(env, word.text);
            if (word_text == nullptr) break;
            jobject word_object = env->NewObject(
                word_class, word_ctor, word_text, static_cast<jlong>(word.start_ms),
                static_cast<jlong>(word.end_ms), static_cast<jint>(word.channel));
            env->DeleteLocalRef(word_text);
            if (word_object == nullptr) break;
            env->CallBooleanMethod(words, list_add, word_object);
            env->DeleteLocalRef(word_object);
            if (env->ExceptionCheck()) break;
        }
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(words);
            break;
        }
        jstring transcript = utf8_to_java_string(env, update.transcript);
        if (transcript == nullptr) {
            env->DeleteLocalRef(words);
            break;
        }
        jobject update_object = env->NewObject(
            update_class, update_ctor, static_cast<jlong>(update.utterance_id),
            static_cast<jlong>(update.revision), words, transcript,
            static_cast<jboolean>(update.is_final ? JNI_TRUE : JNI_FALSE),
            static_cast<jlong>(update.stable_speaker_through_ms),
            static_cast<jlong>(update.audio_processed_ms));
        env->DeleteLocalRef(transcript);
        env->DeleteLocalRef(words);
        if (update_object == nullptr) break;
        env->CallBooleanMethod(list, list_add, update_object);
        env->DeleteLocalRef(update_object);
        if (env->ExceptionCheck()) break;
    }

    env->DeleteLocalRef(word_class);
    env->DeleteLocalRef(update_class);
    env->DeleteLocalRef(list_class);
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(list);
        return nullptr;
    }
    return list;
}

class MeetingSession {
   public:
    static std::shared_ptr<MeetingSession> open(const std::string& asr_path,
                                                const std::string& diar_path,
                                                const std::string& language) {
        if (asr_path.empty() || diar_path.empty() || language.empty())
            throw std::invalid_argument("Meeting model paths and language are required");
        if (::access(asr_path.c_str(), R_OK) != 0 || ::access(diar_path.c_str(), R_OK) != 0)
            throw std::invalid_argument("Meeting model files are unavailable");

        asr::RecognizerConfig config;
        config.backend.gpu = -1;
        config.model.path = asr_path;
        config.batching.enabled = false;
        config.log_status = false;
        config.endpointing.enable = true;
        config.endpointing.vad_based = false;
        config.endpointing.stop_history_eou_ms = 800.0f;
        config.diar.model_path = diar_path;
        config.diar.preset = "v3-streaming";

        auto session = std::shared_ptr<MeetingSession>(new MeetingSession());
        session->recognizer_ = std::make_unique<asr::Recognizer>(std::move(config));
        session->recognizer_->warmup();
        const auto* diar_model = session->recognizer_->diar_model();
        if (diar_model == nullptr || diar_model->cfg().num_speakers != 8)
            throw std::runtime_error("Diarization model does not provide eight speakers");

        asr::AsrRequestOptions options;
        options.enable_word_time_offsets = true;
        options.enable_automatic_punctuation = true;
        options.enable_speaker_diarization = true;
        options.max_speaker_count = 8;
        session->stream_ = session->recognizer_->streaming_recognize(options, language, false);
        session->stream_->set_interim_words(true);
        return session;
    }

    std::vector<Update> accept(const std::vector<float>& samples) {
        std::lock_guard<std::mutex> lock(operation_mutex_);
        require_open_for_audio();
        if (samples.empty()) return {};
        try {
            stream_->push(samples.data(), samples.size(), kSampleRate);
            pushed_samples_ += samples.size();
            std::vector<Update> emitted = drain_results();
            if (!force_pending_ && pushed_audio_ms() - last_endpoint_audio_ms_ >= kForcedEndpointMs) {
                stream_->force_endpoint();
                force_pending_ = true;
                append(emitted, drain_results());
            }
            append(emitted, refresh_pending_finals(false));
            return emitted;
        } catch (...) {
            failed_ = true;
            throw;
        }
    }

    std::vector<Update> finish() {
        std::lock_guard<std::mutex> lock(operation_mutex_);
        require_open_for_audio();
        try {
            asr::Result result = stream_->finish();
            std::vector<Update> emitted = consume_result(std::move(result));
            append(emitted, refresh_pending_finals(false));
            append(emitted, sequencer_.finish_pending(stable_speaker_through_ms()));
            pending_asr_finals_.clear();
            finished_ = true;
            return emitted;
        } catch (...) {
            failed_ = true;
            throw;
        }
    }

    void close() {
        std::lock_guard<std::mutex> lock(operation_mutex_);
        if (closed_) return;
        closed_ = true;
        stream_.reset();
        recognizer_.reset();
        pending_asr_finals_.clear();
    }

   private:
    struct PendingAsrFinal {
        std::int64_t utterance_id;
        asr::Result result;
    };

    MeetingSession() = default;

    static void append(std::vector<Update>& into, std::vector<Update> from) {
        into.insert(into.end(), std::make_move_iterator(from.begin()),
                    std::make_move_iterator(from.end()));
    }

    std::int64_t stable_speaker_through_ms() const {
        return seconds_to_ms(static_cast<float>(stream_->stable_speaker_time()));
    }

    std::int64_t pushed_audio_ms() const {
        return static_cast<std::int64_t>(pushed_samples_) * 1000 / kSampleRate;
    }

    void require_open_for_audio() const {
        if (closed_) throw std::logic_error("Meeting handle is closed");
        if (finished_) throw std::logic_error("Meeting session is already finished");
        if (failed_) throw std::logic_error("Meeting session failed");
        if (!stream_ || !recognizer_) throw std::logic_error("Meeting session is unavailable");
    }

    std::vector<Update> drain_results() {
        std::vector<Update> emitted;
        while (auto result = stream_->next()) append(emitted, consume_result(std::move(*result)));
        return emitted;
    }

    std::vector<Update> consume_result(asr::Result result) {
        std::vector<Update> emitted;
        if (!result.late_punctuation.empty())
            append(emitted, sequencer_.late_punctuation(result.late_punctuation));

        stream_->refresh_speaker_tags(result);
        Update update = copy_result(result, stable_speaker_through_ms());
        if (result.is_final) {
            const auto immediate = sequencer_.final(update);
            if (immediate) {
                emitted.push_back(*immediate);
                if (sequencer_.has_pending(immediate->utterance_id)) {
                    if (pending_asr_finals_.size() >= kMaxPendingFinals)
                        throw std::length_error("Pending meeting final limit reached");
                    pending_asr_finals_.push_back(
                        PendingAsrFinal{immediate->utterance_id, std::move(result)});
                }
            }
            const auto boundary_ms = update.audio_processed_ms;
            last_endpoint_audio_ms_ = std::max(last_endpoint_audio_ms_, boundary_ms);
            force_pending_ = false;
        } else {
            emitted.push_back(sequencer_.interim(std::move(update)));
        }
        append(emitted, refresh_pending_finals(false));
        return emitted;
    }

    std::vector<Update> refresh_pending_finals(bool force_stable) {
        std::vector<Update> emitted;
        const std::int64_t stable_ms = stable_speaker_through_ms();
        for (auto it = pending_asr_finals_.begin(); it != pending_asr_finals_.end();) {
            stream_->refresh_speaker_tags(it->result);
            Update current = copy_result(it->result, stable_ms);
            append(emitted, sequencer_.refresh_final(it->utterance_id, std::move(current), stable_ms,
                                                     force_stable));
            if (!sequencer_.has_pending(it->utterance_id)) {
                it = pending_asr_finals_.erase(it);
            } else {
                ++it;
            }
        }
        return emitted;
    }

    std::mutex operation_mutex_;
    std::unique_ptr<asr::Recognizer> recognizer_;
    std::unique_ptr<asr::RecognitionStream> stream_;
    UpdateSequencer sequencer_{kMaxPendingFinals};
    std::deque<PendingAsrFinal> pending_asr_finals_;
    std::uint64_t pushed_samples_ = 0;
    std::int64_t last_endpoint_audio_ms_ = 0;
    bool force_pending_ = false;
    bool finished_ = false;
    bool failed_ = false;
    bool closed_ = false;
};

std::mutex registry_mutex;
std::map<jlong, std::shared_ptr<MeetingSession>> sessions;
jlong next_handle = 1;

jlong
register_session(std::shared_ptr<MeetingSession> session) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    if (next_handle <= 0 || next_handle == std::numeric_limits<jlong>::max())
        throw std::overflow_error("Meeting handle registry is full");
    const jlong handle = next_handle++;
    sessions.emplace(handle, std::move(session));
    return handle;
}

std::shared_ptr<MeetingSession>
find_session(jlong handle) {
    if (handle <= 0) throw std::invalid_argument("Meeting handle is invalid");
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto it = sessions.find(handle);
    if (it == sessions.end()) throw std::invalid_argument("Meeting handle is invalid");
    return it->second;
}

jlong JNICALL
native_open(JNIEnv* env, jobject, jstring asr_path, jstring diar_path, jstring language) {
    try {
        auto session = MeetingSession::open(java_string_to_utf8(env, asr_path),
                                            java_string_to_utf8(env, diar_path),
                                            java_string_to_utf8(env, language));
        return register_session(std::move(session));
    } catch (const std::exception& error) {
        throw_native_error(env, error, NativeOperation::kOpen);
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Meeting engine could not be opened");
    }
    return 0;
}

jobject JNICALL
native_accept_pcm16(JNIEnv* env, jobject, jlong handle, jbyteArray buffer, jint length) {
    try {
        auto session = find_session(handle);
        if (buffer == nullptr) throw std::invalid_argument("PCM16 buffer is missing");
        const jsize capacity = env->GetArrayLength(buffer);
        const auto sample_count = dictai::meeting::validate_pcm16_length(
            static_cast<std::size_t>(capacity), static_cast<std::int64_t>(length));
        std::vector<jbyte> pcm_bytes(static_cast<std::size_t>(length));
        if (length > 0) env->GetByteArrayRegion(buffer, 0, length, pcm_bytes.data());
        if (env->ExceptionCheck()) return nullptr;
        auto samples = dictai::meeting::pcm16le_to_float(
            reinterpret_cast<const std::uint8_t*>(pcm_bytes.data()),
            static_cast<std::size_t>(capacity), static_cast<std::int64_t>(length));
        if (samples.size() != sample_count)
            throw std::logic_error("PCM16 conversion produced an invalid sample count");
        return make_update_list(env, session->accept(samples));
    } catch (const std::exception& error) {
        throw_native_error(env, error, NativeOperation::kAccept);
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Meeting audio could not be processed");
    }
    return nullptr;
}

jobject JNICALL
native_finish(JNIEnv* env, jobject, jlong handle) {
    try {
        return make_update_list(env, find_session(handle)->finish());
    } catch (const std::exception& error) {
        throw_native_error(env, error, NativeOperation::kFinish);
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Meeting session could not be finished");
    }
    return nullptr;
}

void JNICALL
native_close(JNIEnv* env, jobject, jlong handle) {
    try {
        if (handle <= 0) return;
        std::shared_ptr<MeetingSession> session;
        {
            std::lock_guard<std::mutex> lock(registry_mutex);
            const auto it = sessions.find(handle);
            if (it == sessions.end()) return;
            session = std::move(it->second);
            sessions.erase(it);
        }
        session->close();
    } catch (const std::exception& error) {
        throw_native_error(env, error, NativeOperation::kClose);
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Meeting session could not be closed");
    }
}

JNINativeMethod native_methods[] = {
    {const_cast<char*>("nativeOpen"),
     const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)J"),
     reinterpret_cast<void*>(native_open)},
    {const_cast<char*>("nativeAcceptPcm16"), const_cast<char*>("(J[BI)Ljava/util/List;"),
     reinterpret_cast<void*>(native_accept_pcm16)},
    {const_cast<char*>("nativeFinish"), const_cast<char*>("(J)Ljava/util/List;"),
     reinterpret_cast<void*>(native_finish)},
    {const_cast<char*>("nativeClose"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(native_close)},
};
}  // namespace

extern "C" __attribute__((visibility("default"))) JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass bindings = env->FindClass(kBindingsClass);
    if (bindings == nullptr) return JNI_ERR;
    const jint result = env->RegisterNatives(
        bindings, native_methods, static_cast<jint>(sizeof(native_methods) / sizeof(native_methods[0])));
    env->DeleteLocalRef(bindings);
    return result == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
