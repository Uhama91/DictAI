// SPDX-License-Identifier: Apache-2.0
// Android CPU plumbing probe for the pinned NeMo-Speech.cpp streaming API.
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <dlfcn.h>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <map>
#include <optional>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "audio_file.h"
#include "recognizer.h"

namespace {
using Clock = std::chrono::steady_clock;
namespace asr = nemo_speech::asr;
namespace audio = nemo_speech::audio;
constexpr int kSampleRate = 16000;
constexpr size_t kChunkSamples = kSampleRate * 160 / 1000;

int64_t
elapsed_ms(Clock::time_point start, Clock::time_point end = Clock::now()) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
}

int64_t
rss_kb(const std::string& key) {
    std::ifstream status("/proc/self/status");
    std::string line;
    while (std::getline(status, line)) {
        if (line.rfind(key, 0) == 0) {
            std::istringstream value(line.substr(key.size()));
            int64_t kb = 0;
            value >> kb;
            return kb;
        }
    }
    return -1;
}

void
log_memory(const char* stage) {
    std::printf("PROCESS_MEMORY stage=%s rss_kb=%lld peak_rss_kb=%lld\n", stage,
                static_cast<long long>(rss_kb("VmRSS:")),
                static_cast<long long>(rss_kb("VmHWM:")));
}

std::string
speaker_sequence_text(const std::vector<int>& sequence) {
    std::ostringstream out;
    for (size_t i = 0; i < sequence.size(); ++i) {
        if (i)
            out << ',';
        out << sequence[i];
    }
    return out.str();
}

bool
has_speaker_return(const std::vector<int>& sequence) {
    // A-B-A is a return; longer sequences such as A-B-C-A also qualify.
    for (size_t i = 2; i < sequence.size(); ++i)
        for (size_t j = 0; j + 2 <= i; ++j)
            if (sequence[i] == sequence[j])
                return true;
    return false;
}

struct PassMetrics {
    int64_t push_ms = 0;
    int64_t next_ms = 0;
    int64_t finish_ms = 0;
    int64_t max_input_lag_ms = 0;
    size_t result_count = 0;
    size_t word_count = 0;
};

void
log_word(
    const char* stage, const char* phase, bool is_final, const asr::Word* word,
    int64_t monotonic_ms, int64_t pushed_ms, int64_t total_ms, float audio_processed_s,
    double stable_s) {
    std::ostringstream encoded_word;
    if (word)
        encoded_word << std::quoted(word->word);
    else
        encoded_word << "\"\"";
    const std::string text = encoded_word.str();
    std::printf(
        "RESULT stage=%s phase=%s final=%d mono_ms=%lld pushed_ms=%lld total_ms=%lld "
        "audio_processed_ms=%lld stable_speaker_ms=%lld start_ms=%d end_ms=%d "
        "speaker_tag=%d word=%s\n",
        stage, phase, is_final ? 1 : 0, static_cast<long long>(monotonic_ms),
        static_cast<long long>(pushed_ms), static_cast<long long>(total_ms),
        static_cast<long long>(audio_processed_s * 1000.0f),
        static_cast<long long>(stable_s * 1000.0), word ? word->start_time : 0,
        word ? word->end_time : 0, word ? word->speaker_tag : 0, text.c_str());
}

void
emit_result(
    const char* stage, const char* phase, const asr::Result& result, int64_t monotonic_ms,
    int64_t pushed_ms, int64_t total_ms, double stable_s, PassMetrics& metrics) {
    ++metrics.result_count;
    if (result.alternatives.empty() || result.alternatives.front().words.empty()) {
        log_word(stage, phase, result.is_final, nullptr, monotonic_ms, pushed_ms, total_ms,
                 result.audio_processed, stable_s);
        return;
    }
    const auto& words = result.alternatives.front().words;
    for (const auto& word : words) {
        ++metrics.word_count;
        log_word(stage, phase, result.is_final, &word, monotonic_ms, pushed_ms, total_ms,
                 result.audio_processed, stable_s);
    }
}

asr::RecognizerConfig
make_config(const std::string& asr_path, const std::string& diar_path = {}) {
    asr::RecognizerConfig config;
    config.backend.gpu = -1;
    config.model.path = asr_path;
    config.batching.enabled = false;
    config.endpointing.enable = true;
    config.endpointing.vad_based = false;
    config.endpointing.stop_history_eou_ms = 800.0f;
    if (!diar_path.empty()) {
        config.diar.model_path = diar_path;
        config.diar.preset = "v3-streaming";
    }
    return config;
}

PassMetrics
run_pass(
    asr::Recognizer& recognizer, const audio::AudioFile& input, const char* stage,
    bool diarized, bool realtime) {
    asr::AsrRequestOptions options;
    options.enable_word_time_offsets = true;
    options.enable_speaker_diarization = diarized;
    options.max_speaker_count = 8;
    options.enable_automatic_punctuation = true;
    auto stream = recognizer.streaming_recognize(options, "");
    stream->set_interim_words(true);

    const int64_t total_ms = static_cast<int64_t>(input.samples.size()) * 1000 / kSampleRate;
    const auto pass_start = Clock::now();
    PassMetrics metrics;
    std::vector<asr::Result> pending_finals;
    std::map<int, int> stable_word_tags;
    int64_t max_input_lag_ms = 0;
    size_t pushed_samples = 0;
    std::printf("RUN_BEGIN stage=%s cadence=%s sample_rate=%d chunk_ms=160 total_ms=%lld\n",
                stage, realtime ? "realtime" : "burst", kSampleRate,
                static_cast<long long>(total_ms));

    auto emit_stable_finals = [&]() {
        const double stable_time = stream->stable_speaker_time();
        for (auto it = pending_finals.begin(); it != pending_finals.end();) {
            stream->refresh_speaker_tags(*it);
            const auto& words = it->alternatives.empty() ? std::vector<asr::Word>{}
                                                         : it->alternatives.front().words;
            double end_time = 0.0;
            for (const auto& word : words)
                end_time = std::max(end_time, word.end_time / 1000.0);
            if (end_time <= stable_time) {
                emit_result(stage, "final_stable", *it, elapsed_ms(pass_start),
                            static_cast<int64_t>(pushed_samples) * 1000 / kSampleRate,
                            total_ms, stable_time, metrics);
                for (const auto& word : words)
                    if (word.speaker_tag > 0)
                        stable_word_tags[word.start_time] = word.speaker_tag;
                it = pending_finals.erase(it);
            } else {
                ++it;
            }
        }
    };

    size_t offset = 0;
    while (offset < input.samples.size()) {
        const size_t count = std::min(kChunkSamples, input.samples.size() - offset);
        if (realtime) {
            const auto due = pass_start + std::chrono::microseconds(
                static_cast<int64_t>(offset + count) * 1000000 / kSampleRate);
            std::this_thread::sleep_until(due);
            const int64_t lag = std::max<int64_t>(0, elapsed_ms(due));
            max_input_lag_ms = std::max(max_input_lag_ms, lag);
        }
        const auto push_start = Clock::now();
        stream->push(input.samples.data() + offset, count, kSampleRate);
        metrics.push_ms += elapsed_ms(push_start);
        pushed_samples += count;
        const int64_t pushed_ms = static_cast<int64_t>(pushed_samples) * 1000 / kSampleRate;
        while (true) {
            const auto decode_start = Clock::now();
            std::optional<asr::Result> result = stream->next();
            metrics.next_ms += elapsed_ms(decode_start);
            if (!result)
                break;
            if (diarized)
                stream->refresh_speaker_tags(*result);
            if (result->is_final && diarized) {
                asr::Result provisional = *result;
                if (!provisional.alternatives.empty())
                    for (auto& word : provisional.alternatives.front().words)
                        word.speaker_tag = 0;
                emit_result(stage, "final_provisional", provisional, elapsed_ms(pass_start),
                            pushed_ms, total_ms, stream->stable_speaker_time(), metrics);
                pending_finals.push_back(std::move(*result));
            } else {
                emit_result(stage, result->is_final ? "final" : "interim", *result,
                            elapsed_ms(pass_start), pushed_ms, total_ms,
                            stream->stable_speaker_time(), metrics);
            }
            if (diarized)
                emit_stable_finals();
        }
        if (diarized)
            emit_stable_finals();
        offset += count;
    }

    if (diarized) {
        std::vector<int> sequence;
        for (const auto& [start_ms, tag] : stable_word_tags) {
            (void)start_ms;
            if (sequence.empty() || sequence.back() != tag)
                sequence.push_back(tag);
        }
        std::printf("SPEAKER_SEQUENCE stage=%s sequence=%s returned=%d\n", stage,
                    speaker_sequence_text(sequence).c_str(), has_speaker_return(sequence) ? 1 : 0);
    }
    std::printf("FINISH_BEGIN stage=%s pushed_ms=%lld total_ms=%lld\n", stage,
                static_cast<long long>(total_ms), static_cast<long long>(total_ms));
    const auto finish_start = Clock::now();
    asr::Result final_result = stream->finish();
    metrics.finish_ms += elapsed_ms(finish_start);
    if (diarized) {
        stream->refresh_speaker_tags(final_result);
        if (!final_result.alternatives.empty())
            pending_finals.push_back(std::move(final_result));
        for (auto& pending : pending_finals) {
            stream->refresh_speaker_tags(pending);
            emit_result(stage, "final_after_finish", pending, elapsed_ms(pass_start), total_ms,
                        total_ms, total_ms / 1000.0, metrics);
        }
    } else {
        emit_result(stage, "final_after_finish", final_result, elapsed_ms(pass_start), total_ms,
                    total_ms, 0.0, metrics);
    }

    const int64_t wall_ms = elapsed_ms(pass_start);
    const double audio_s = input.samples.size() / static_cast<double>(kSampleRate);
    const double rtf = wall_ms / (audio_s * 1000.0);
    std::printf(
        "RUN_SUMMARY stage=%s wall_ms=%lld push_ms=%lld next_ms=%lld finish_ms=%lld "
        "engine_work_ms=%lld audio_s=%.3f rtf=%.3f max_input_lag_ms=%lld "
        "results=%zu words=%zu\n",
        stage, static_cast<long long>(wall_ms), static_cast<long long>(metrics.push_ms),
        static_cast<long long>(metrics.next_ms), static_cast<long long>(metrics.finish_ms),
        static_cast<long long>(metrics.push_ms + metrics.next_ms + metrics.finish_ms), audio_s,
        rtf, static_cast<long long>(max_input_lag_ms), metrics.result_count, metrics.word_count);
    log_memory(stage);
    return metrics;
}

struct PendingBenchmarkFinal {
    std::int64_t utterance_id = 0;
    asr::Result result;
    std::vector<asr::Word> last_words;
    std::set<std::string> stable_words_reported;
};

struct BenchmarkState {
    int pass_id = 0;
    const char* variant = "asr";
    std::int64_t active_utterance_id = 1;
    bool active_has_provisional = false;
    std::map<std::int64_t, int> revisions;
    std::vector<PendingBenchmarkFinal> pending_finals;
    PassMetrics metrics;
};

const std::vector<asr::Word>&
result_words(const asr::Result& result) {
    static const std::vector<asr::Word> empty;
    if (result.alternatives.empty())
        return empty;
    return result.alternatives.front().words;
}

bool
same_benchmark_words(const std::vector<asr::Word>& left, const std::vector<asr::Word>& right) {
    if (left.size() != right.size())
        return false;
    for (size_t i = 0; i < left.size(); ++i) {
        if (left[i].word != right[i].word || left[i].start_time != right[i].start_time ||
            left[i].end_time != right[i].end_time || left[i].speaker_tag != right[i].speaker_tag)
            return false;
    }
    return true;
}

bool
same_benchmark_word_identity(const std::vector<asr::Word>& left,
                             const std::vector<asr::Word>& right) {
    if (left.size() != right.size())
        return false;
    for (size_t i = 0; i < left.size(); ++i) {
        if (left[i].word != right[i].word || left[i].start_time != right[i].start_time ||
            left[i].end_time != right[i].end_time)
            return false;
    }
    return true;
}

void
emit_benchmark_result(BenchmarkState& state, const char* phase, std::int64_t utterance_id,
                      const asr::Result& result, std::int64_t mono_ms, double stable_s) {
    const auto& words = result_words(result);
    const int revision = ++state.revisions[utterance_id];
    std::printf(
        "RESULT pass=%d phase=%s revision=%d utterance_id=%lld mono_ms=%lld "
        "stable_speaker_ms=%lld word_count=%zu\n",
        state.pass_id, phase, revision, static_cast<long long>(utterance_id),
        static_cast<long long>(mono_ms), static_cast<long long>(stable_s * 1000.0), words.size());
    for (size_t index = 0; index < words.size(); ++index) {
        std::ostringstream encoded_word;
        encoded_word << std::quoted(words[index].word);
        std::printf(
            "WORD pass=%d revision=%d utterance_id=%lld word_index=%zu start_ms=%d end_ms=%d "
            "speaker_tag=%d word=%s\n",
            state.pass_id, revision, static_cast<long long>(utterance_id), index,
            words[index].start_time, words[index].end_time, words[index].speaker_tag,
            encoded_word.str().c_str());
        ++state.metrics.word_count;
    }
    ++state.metrics.result_count;
}

std::string
benchmark_word_identity(const asr::Word& word) {
    std::ostringstream key;
    key << word.start_time << '\x1f' << word.end_time << '\x1f' << word.word;
    return key.str();
}

std::chrono::microseconds
audio_deadline_offset(size_t sample_end) {
    return std::chrono::microseconds(
        static_cast<int64_t>(sample_end) * 1000000 / kSampleRate);
}

int
run_benchmark_pass(int pass_id, const std::string& asr_path, const std::string& diar_path,
                   const audio::AudioFile& input) {
    static const char* kVariants[] = {"asr", "both", "both", "asr", "asr", "both"};
    if (pass_id < 1 || pass_id > 6) {
        std::fprintf(stderr, "FAIL: benchmark pass must be between 1 and 6\n");
        return 2;
    }
    const bool diarized = std::string(kVariants[pass_id - 1]) == "both";
    const char* variant = kVariants[pass_id - 1];
    asr::RecognizerConfig config = make_config(asr_path, diarized ? diar_path : std::string{});
    config.log_status = false;

    BenchmarkState state;
    state.pass_id = pass_id;
    state.variant = variant;
    std::printf(
        "BENCH_CONFIG pass=%d variant=%s language=fr gpu=-1 log_status=0 punctuation=1 "
        "max_speakers=8 interim=1 endpoint=1 token_silence_ms=800 force_endpoint_ms=30000 "
        "cadence=realtime chunk_ms=160\n",
        pass_id, variant);
    const auto load_start = Clock::now();
    asr::Recognizer recognizer(std::move(config));
    const auto load_ms = elapsed_ms(load_start);
    std::printf("BENCH_MODEL_LOAD pass=%d variant=%s elapsed_ms=%lld\n", pass_id, variant,
                static_cast<long long>(load_ms));
    int model_speakers = 0;
    if (diarized) {
        model_speakers = recognizer.diar_model() ? recognizer.diar_model()->cfg().num_speakers : 0;
        if (model_speakers != 8) {
            std::fprintf(stderr, "FAIL: benchmark expected an eight-speaker diarization model\n");
            return 4;
        }
    }
    const auto warm_start = Clock::now();
    recognizer.warmup();
    const auto warmup_ms = elapsed_ms(warm_start);
    std::printf("BENCH_WARMUP pass=%d variant=%s elapsed_ms=%lld speakers=%d\n", pass_id,
                variant, static_cast<long long>(warmup_ms), model_speakers);

    asr::AsrRequestOptions options;
    options.enable_word_time_offsets = true;
    options.enable_speaker_diarization = diarized;
    options.max_speaker_count = 8;
    options.enable_automatic_punctuation = true;
    auto stream = recognizer.streaming_recognize(options, "fr", false);
    stream->set_interim_words(true);

    const std::int64_t total_ms =
        static_cast<std::int64_t>(input.samples.size()) * 1000 / kSampleRate;
    const auto flow_start = Clock::now();
    const size_t first_chunk_samples = std::min(kChunkSamples, input.samples.size());
    const auto first_due = flow_start + audio_deadline_offset(first_chunk_samples);
    const auto last_due = flow_start + audio_deadline_offset(input.samples.size());
    const auto first_due_us = std::chrono::duration_cast<std::chrono::microseconds>(
        first_due - flow_start).count();
    const auto last_due_us = std::chrono::duration_cast<std::chrono::microseconds>(
        last_due - flow_start).count();
    const auto expected_first_due_us =
        static_cast<int64_t>(first_chunk_samples) * 1000000 / kSampleRate;
    const auto expected_last_due_us =
        static_cast<int64_t>(input.samples.size()) * 1000000 / kSampleRate;
    const bool timing_ok = first_due_us == expected_first_due_us &&
                           last_due_us == expected_last_due_us;
    std::printf(
        "BENCH_TIMING_CHECK pass=%d origin=audio_capture_origin status=%s "
        "first_due_us=%lld expected_first_due_us=%lld "
        "last_due_us=%lld expected_last_due_us=%lld\n",
        pass_id, timing_ok ? "ok" : "failed", static_cast<long long>(first_due_us),
        static_cast<long long>(expected_first_due_us), static_cast<long long>(last_due_us),
        static_cast<long long>(expected_last_due_us));
    if (!timing_ok) {
        std::fprintf(stderr, "FAIL: benchmark audio deadline arithmetic mismatch\n");
        return 5;
    }
    state.metrics.max_input_lag_ms = 0;
    std::int64_t pushed_samples = 0;
    std::int64_t last_endpoint_audio_ms = 0;
    bool force_pending = false;
    std::vector<int> stable_speaker_sequence;
    std::map<int, int> stable_tag_by_start;
    std::printf("BENCH_PASS_BEGIN pass=%d variant=%s stream_clock=audio_capture_origin "
                "total_ms=%lld\n", pass_id, variant, static_cast<long long>(total_ms));

    auto refresh_pending = [&]() {
        if (!diarized)
            return;
        const double stable_s = stream->stable_speaker_time();
        const auto mono_ms = elapsed_ms(flow_start);
        for (auto& pending : state.pending_finals) {
            stream->refresh_speaker_tags(pending.result);
            const auto& words = result_words(pending.result);
            bool newly_stable = false;
            for (const auto& word : words) {
                if (word.speaker_tag > 0 && word.end_time / 1000.0 <= stable_s) {
                    const std::string key = benchmark_word_identity(word);
                    if (pending.stable_words_reported.insert(key).second) {
                        newly_stable = true;
                        stable_tag_by_start[word.start_time] = word.speaker_tag;
                    }
                }
            }
            if (newly_stable || !same_benchmark_words(pending.last_words, words)) {
                emit_benchmark_result(state, "final_refresh", pending.utterance_id,
                                      pending.result, mono_ms, stable_s);
                pending.last_words = words;
            }
        }
    };

    auto drain = [&]() {
        while (true) {
            const auto decode_start = Clock::now();
            std::optional<asr::Result> result = stream->next();
            state.metrics.next_ms += elapsed_ms(decode_start);
            if (!result)
                break;
            if (diarized)
                stream->refresh_speaker_tags(*result);
            const auto mono_ms = elapsed_ms(flow_start);
            const double stable_s = stream->stable_speaker_time();
            const auto& words = result_words(*result);
            if (result->is_final) {
                const std::int64_t utterance_id = state.active_utterance_id++;
                emit_benchmark_result(state, "final_initial", utterance_id, *result,
                                      mono_ms, stable_s);
                if (!words.empty()) {
                    PendingBenchmarkFinal pending;
                    pending.utterance_id = utterance_id;
                    pending.result = *result;
                    pending.last_words = words;
                    state.pending_finals.push_back(std::move(pending));
                }
                state.active_has_provisional = false;
                last_endpoint_audio_ms = std::max<std::int64_t>(
                    last_endpoint_audio_ms,
                    static_cast<std::int64_t>(result->audio_processed * 1000.0f));
                force_pending = false;
            } else {
                emit_benchmark_result(state, "interim", state.active_utterance_id,
                                      *result, mono_ms, stable_s);
                if (!words.empty())
                    state.active_has_provisional = true;
            }
            refresh_pending();
        }
    };

    for (size_t offset = 0; offset < input.samples.size();) {
        const size_t count = std::min(kChunkSamples, input.samples.size() - offset);
        const auto due = flow_start + audio_deadline_offset(offset + count);
        std::this_thread::sleep_until(due);
        const auto lag_ms = std::max<std::int64_t>(0, elapsed_ms(due));
        state.metrics.max_input_lag_ms = std::max(state.metrics.max_input_lag_ms, lag_ms);
        const auto push_start = Clock::now();
        stream->push(input.samples.data() + offset, count, kSampleRate);
        state.metrics.push_ms += elapsed_ms(push_start);
        pushed_samples += static_cast<std::int64_t>(count);
        drain();
        const std::int64_t pushed_ms = pushed_samples * 1000 / kSampleRate;
        if (!force_pending && pushed_ms - last_endpoint_audio_ms >= 30000) {
            stream->force_endpoint();
            force_pending = true;
            drain();
        }
        refresh_pending();
        offset += count;
    }

    for (const auto& [start_ms, tag] : stable_tag_by_start) {
        (void)start_ms;
        if (stable_speaker_sequence.empty() || stable_speaker_sequence.back() != tag)
            stable_speaker_sequence.push_back(tag);
    }
    std::printf("BENCH_FINISH_BEGIN pass=%d pushed_ms=%lld total_ms=%lld\n", pass_id,
                static_cast<long long>(pushed_samples * 1000 / kSampleRate),
                static_cast<long long>(total_ms));
    const auto finish_start = Clock::now();
    asr::Result finish_result = stream->finish();
    state.metrics.finish_ms = elapsed_ms(finish_start);
    if (diarized)
        stream->refresh_speaker_tags(finish_result);
    const auto& finish_words = result_words(finish_result);
    bool matched_pending = false;
    if (!finish_words.empty()) {
        for (auto pending = state.pending_finals.rbegin();
             pending != state.pending_finals.rend(); ++pending) {
            if (same_benchmark_word_identity(pending->last_words, finish_words)) {
                pending->result = finish_result;
                matched_pending = true;
                break;
            }
        }
    }
    if (!matched_pending && (finish_result.is_final || state.active_has_provisional) &&
        (!finish_words.empty() || state.active_has_provisional)) {
        const std::int64_t utterance_id = state.active_utterance_id++;
        emit_benchmark_result(state, "final_initial", utterance_id, finish_result,
                              elapsed_ms(flow_start), stream->stable_speaker_time());
        if (!finish_words.empty()) {
            PendingBenchmarkFinal pending;
            pending.utterance_id = utterance_id;
            pending.result = finish_result;
            pending.last_words = finish_words;
            state.pending_finals.push_back(std::move(pending));
        }
    }
    refresh_pending();
    std::printf("BENCH_FINISH_END pass=%d elapsed_ms=%lld\n", pass_id,
                static_cast<long long>(state.metrics.finish_ms));

    const std::int64_t wall_ms = elapsed_ms(flow_start);
    const double audio_s = input.samples.size() / static_cast<double>(kSampleRate);
    const double rtf = wall_ms / (audio_s * 1000.0);
    std::printf(
        "BENCH_PASS_SUMMARY pass=%d variant=%s load_ms=%lld warmup_ms=%lld wall_ms=%lld "
        "push_ms=%lld next_ms=%lld finish_ms=%lld rtf=%.3f max_input_lag_ms=%lld "
        "result_events=%zu word_events=%zu stable_sequence=%s\n",
        pass_id, variant, static_cast<long long>(load_ms), static_cast<long long>(warmup_ms),
        static_cast<long long>(wall_ms), static_cast<long long>(state.metrics.push_ms),
        static_cast<long long>(state.metrics.next_ms), static_cast<long long>(state.metrics.finish_ms),
        rtf, static_cast<long long>(state.metrics.max_input_lag_ms), state.metrics.result_count,
        state.metrics.word_count, speaker_sequence_text(stable_speaker_sequence).c_str());
    std::printf("BENCH_PASS_END pass=%d variant=%s\n", pass_id, variant);
    return 0;
}

int
run_benchmark(int pass_id, const std::string& asr_path, const std::string& diar_path,
              const audio::AudioFile& input) {
    if (pass_id < 1 || pass_id > 6) {
        std::fprintf(stderr, "FAIL: benchmark pass must be between 1 and 6\n");
        return 2;
    }
    return run_benchmark_pass(pass_id, asr_path, diar_path, input);
}

int
run_asr_only(const std::string& asr_path, const audio::AudioFile& input) {
    auto config = make_config(asr_path);
    const auto load_start = Clock::now();
    asr::Recognizer recognizer(std::move(config));
    std::printf("MODEL_LOAD mode=asr-only elapsed_ms=%lld\n",
                static_cast<long long>(elapsed_ms(load_start)));
    log_memory("asr-only-loaded");
    const auto warm_start = Clock::now();
    recognizer.warmup();
    std::printf("WARMUP mode=asr-only elapsed_ms=%lld\n",
                static_cast<long long>(elapsed_ms(warm_start)));
    log_memory("asr-only-warmed");
    run_pass(recognizer, input, "asr-realtime", false, true);
    return 0;
}

int
run_asr_diar(const std::string& asr_path, const std::string& diar_path,
             const audio::AudioFile& input) {
    auto config = make_config(asr_path, diar_path);
    std::printf("CONFIG endpointing=1 token_silence_ms=800 diar_preset=v3-streaming "
                "spkcache=264 fifo=80 chunk=13 update=40 left=0 right=1\n");
    const auto load_start = Clock::now();
    asr::Recognizer recognizer(std::move(config));
    std::printf("MODEL_LOAD mode=asr+diar elapsed_ms=%lld\n",
                static_cast<long long>(elapsed_ms(load_start)));
    const int speakers = recognizer.diar_model() ? recognizer.diar_model()->cfg().num_speakers : 0;
    std::printf("MODEL_CAPACITY stage=diar speakers=%d\n", speakers);
    log_memory("asr+diar-loaded");
    if (speakers != 8) {
        std::fprintf(stderr, "FAIL: expected actual eight-speaker model, got %d\n", speakers);
        return 4;
    }
    const auto warm_start = Clock::now();
    recognizer.warmup();
    std::printf("WARMUP mode=asr+diar elapsed_ms=%lld\n",
                static_cast<long long>(elapsed_ms(warm_start)));
    log_memory("asr+diar-warmed");
    run_pass(recognizer, input, "diar-realtime", true, true);
    run_pass(recognizer, input, "diar-burst", true, false);
    return 0;
}

}  // namespace

int
main(int argc, char** argv) {
    std::setvbuf(stdout, nullptr, _IOLBF, 0);
    std::string asr_path;
    std::string diar_path;
    std::string wav_path;
    std::optional<int> benchmark_pass;
    std::vector<std::string> coexist_libraries;
    for (int i = 1; i < argc;) {
        const std::string name = argv[i++];
        if (name == "--coexist-lib") {
            if (i >= argc) {
                std::fprintf(stderr, "missing path after --coexist-lib\n");
                return 2;
            }
            coexist_libraries.emplace_back(argv[i++]);
            continue;
        }
        if (i >= argc) {
            std::fprintf(stderr, "missing value after %s\n", name.c_str());
            return 2;
        }
        const std::string value = argv[i++];
        if (name == "--asr")
            asr_path = value;
        else if (name == "--diar")
            diar_path = value;
        else if (name == "--wav")
            wav_path = value;
        else if (name == "--benchmark-pass") {
            try {
                size_t consumed = 0;
                const int parsed = std::stoi(value, &consumed);
                if (consumed != value.size() || parsed < 1 || parsed > 6) {
                    std::fprintf(stderr, "invalid benchmark pass: %s\n", value.c_str());
                    return 2;
                }
                benchmark_pass = parsed;
            } catch (const std::exception&) {
                std::fprintf(stderr, "invalid benchmark pass: %s\n", value.c_str());
                return 2;
            }
        }
        else {
            std::fprintf(stderr, "unknown argument: %s\n", name.c_str());
            return 2;
        }
    }
    if (asr_path.empty() || diar_path.empty() || wav_path.empty()) {
        std::fprintf(stderr,
                     "usage: %s --asr <asr.gguf> --diar <diar.gguf> --wav <audio.wav> "
                     "[--coexist-lib <existing-runtime.so> ...]\n",
                     argv[0]);
        return 2;
    }
    try {
        std::vector<void*> coexist_handles;
        for (const auto& library : coexist_libraries) {
            void* handle = dlopen(library.c_str(), RTLD_NOW | RTLD_LOCAL);
            if (!handle) {
                std::fprintf(stderr, "RUNTIME_COEXIST status=failed path=%s error=%s\n",
                             library.c_str(), dlerror());
                return 4;
            }
            coexist_handles.push_back(handle);
            std::printf("RUNTIME_COEXIST status=loaded path=%s\n", library.c_str());
        }
        std::printf("RUNTIME_COEXIST_SUMMARY libraries=%zu status=ok\n",
                    coexist_handles.size());

        audio::AudioFile input = audio::load_wav_file(wav_path);
        if (input.sample_rate != kSampleRate || input.source_channels != 1) {
            std::fprintf(stderr, "FAIL: expected mono 16 kHz fixture, got %d Hz/%d channels\n",
                         input.sample_rate, input.source_channels);
            return 3;
        }
        std::printf("AUDIO sample_rate=%d channels=%d samples=%zu duration_ms=%lld\n",
                    input.sample_rate, input.source_channels, input.samples.size(),
                    static_cast<long long>(input.samples.size()) * 1000 / kSampleRate);
        log_memory("audio-loaded");
        if (benchmark_pass)
            return run_benchmark(*benchmark_pass, asr_path, diar_path, input);
        const int asr_result = run_asr_only(asr_path, input);
        if (asr_result != 0)
            return asr_result;
        return run_asr_diar(asr_path, diar_path, input);
    } catch (const std::exception& error) {
        std::fprintf(stderr, "FAIL: %s\n", error.what());
        return 10;
    }
}
