// SPDX-License-Identifier: Apache-2.0
#include "meeting_native_logic.h"

#include <algorithm>

namespace dictai::meeting {

std::size_t
validate_pcm16_length(std::size_t capacity, std::int64_t length) {
    if (length < 0 || static_cast<std::uint64_t>(length) > capacity ||
        static_cast<std::uint64_t>(length) > kMaxPcmBytes || (length & 1) != 0) {
        throw std::invalid_argument("PCM16 length is outside the accepted bounds");
    }
    return static_cast<std::size_t>(length) / sizeof(std::int16_t);
}

std::vector<float>
pcm16le_to_float(const std::uint8_t* bytes, std::size_t capacity, std::int64_t length) {
    const std::size_t sample_count = validate_pcm16_length(capacity, length);
    if (sample_count > 0 && bytes == nullptr)
        throw std::invalid_argument("PCM16 buffer is missing");

    std::vector<float> samples;
    samples.reserve(sample_count);
    for (std::size_t i = 0; i < sample_count; ++i) {
        const std::uint32_t low = bytes[i * 2];
        const std::uint32_t high = bytes[i * 2 + 1];
        const std::int32_t bits = static_cast<std::int32_t>(low | (high << 8));
        const std::int32_t signed_sample = bits >= 0x8000 ? bits - 0x10000 : bits;
        samples.push_back(static_cast<float>(signed_sample) / 32768.0f);
    }
    return samples;
}

UpdateSequencer::UpdateSequencer(std::size_t max_pending_finals)
    : max_pending_finals_(max_pending_finals) {
    if (max_pending_finals_ == 0)
        throw std::invalid_argument("Pending final limit must be positive");
}

Update
UpdateSequencer::interim(Update value) {
    value.utterance_id = active_utterance_id_;
    value.revision = ++active_revision_;
    value.is_final = false;
    return value;
}

std::optional<Update>
UpdateSequencer::final(Update value) {
    if (max_pending_finals_ <= pending_.size() &&
        (!value.transcript.empty() || !value.words.empty())) {
        throw std::length_error("Pending meeting final limit reached");
    }

    value.utterance_id = active_utterance_id_;
    value.revision = ++active_revision_;
    value.is_final = true;
    ++active_utterance_id_;
    active_revision_ = 0;

    if (value.transcript.empty() && value.words.empty()) return value;
    pending_.push_back(PendingFinal{value, {}});
    return value;
}

std::int64_t
UpdateSequencer::final_end_ms(const Update& value) {
    if (value.words.empty()) return value.audio_processed_ms;
    return std::max_element(value.words.begin(), value.words.end(),
                            [](const Word& left, const Word& right) {
                                return left.end_ms < right.end_ms;
                            })->end_ms;
}

std::vector<Update>
UpdateSequencer::refresh_final(std::int64_t utterance_id, Update value,
                               std::int64_t stable_through_ms, bool force_stable) {
    auto it = std::find_if(pending_.begin(), pending_.end(), [&](const PendingFinal& pending) {
        return pending.latest.utterance_id == utterance_id;
    });
    if (it == pending_.end()) return {};

    const bool stable = force_stable || stable_through_ms >= final_end_ms(value);
    const bool changed = it->latest.transcript != value.transcript || it->latest.words != value.words;
    if (!changed && !stable) return {};

    value.utterance_id = utterance_id;
    value.revision = it->latest.revision + 1;
    value.is_final = true;
    value.stable_speaker_through_ms = stable_through_ms;
    it->latest = value;
    std::vector<Update> emitted{value};

    if (stable) {
        const bool becomes_latest_stable =
            !latest_stable_final_ ||
            it->latest.utterance_id >= latest_stable_final_->utterance_id;
        Update stabilized = it->latest;
        const std::string punctuation = std::move(it->late_punctuation);
        pending_.erase(it);
        if (!punctuation.empty()) {
            append_punctuation(stabilized, punctuation);
            ++stabilized.revision;
            emitted.push_back(stabilized);
        }
        if (becomes_latest_stable) latest_stable_final_ = std::move(stabilized);
    }
    return emitted;
}

std::vector<Update>
UpdateSequencer::late_punctuation(const std::string& punctuation) {
    if (punctuation.empty()) return {};

    auto newest_pending = std::max_element(
        pending_.begin(), pending_.end(), [](const PendingFinal& left, const PendingFinal& right) {
            return left.latest.utterance_id < right.latest.utterance_id;
        });
    if (newest_pending != pending_.end() &&
        (!latest_stable_final_ || newest_pending->latest.utterance_id >
                                      latest_stable_final_->utterance_id)) {
        newest_pending->late_punctuation += punctuation;
        return {};
    }
    if (!latest_stable_final_) return {};

    append_punctuation(*latest_stable_final_, punctuation);
    ++latest_stable_final_->revision;
    return {*latest_stable_final_};
}

std::vector<Update>
UpdateSequencer::finish_pending(std::int64_t stable_through_ms) {
    std::vector<Update> emitted;
    while (!pending_.empty()) {
        const auto utterance_id = pending_.front().latest.utterance_id;
        Update latest = pending_.front().latest;
        auto changes = refresh_final(utterance_id, std::move(latest), stable_through_ms, true);
        emitted.insert(emitted.end(), changes.begin(), changes.end());
    }
    return emitted;
}

bool
UpdateSequencer::has_pending(std::int64_t utterance_id) const {
    return std::any_of(pending_.begin(), pending_.end(), [&](const PendingFinal& pending) {
        return pending.latest.utterance_id == utterance_id;
    });
}

void
UpdateSequencer::append_punctuation(Update& value, const std::string& punctuation) {
    value.transcript += punctuation;
    if (!value.words.empty()) value.words.back().text += punctuation;
}

}  // namespace dictai::meeting
