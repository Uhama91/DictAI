// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstddef>
#include <cstdint>
#include <deque>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

namespace dictai::meeting {

constexpr std::size_t kMaxPcmBytes = 320000;  // 10 seconds at mono 16 kHz PCM16.
constexpr std::size_t kMaxPendingFinals = 64;

std::size_t validate_pcm16_length(std::size_t capacity, std::int64_t length);
std::vector<float> pcm16le_to_float(const std::uint8_t* bytes, std::size_t capacity,
                                    std::int64_t length);

struct Word {
    std::string text;
    std::int64_t start_ms = 0;
    std::int64_t end_ms = 0;
    int channel = 0;

    bool operator==(const Word& other) const {
        return text == other.text && start_ms == other.start_ms && end_ms == other.end_ms &&
               channel == other.channel;
    }
};

struct Update {
    std::int64_t utterance_id = 0;
    std::int64_t revision = 0;
    std::vector<Word> words;
    std::string transcript;
    bool is_final = false;
    std::int64_t stable_speaker_through_ms = 0;
    std::int64_t audio_processed_ms = 0;
};

// Separates ASR updates from JNI and gives final/retag/punctuation revisions
// deterministic IDs that can be tested without loading model weights.
class UpdateSequencer {
   public:
    explicit UpdateSequencer(std::size_t max_pending_finals = kMaxPendingFinals);

    Update interim(Update value);
    std::optional<Update> final(Update value);
    std::vector<Update> refresh_final(std::int64_t utterance_id, Update value,
                                      std::int64_t stable_through_ms, bool force_stable = false);
    std::vector<Update> late_punctuation(const std::string& punctuation);
    std::vector<Update> finish_pending(std::int64_t stable_through_ms);

    std::size_t pending_count() const { return pending_.size(); }
    bool has_pending(std::int64_t utterance_id) const;

   private:
    struct PendingFinal {
        Update latest;
        std::string late_punctuation;
    };

    static std::int64_t final_end_ms(const Update& value);
    static void append_punctuation(Update& value, const std::string& punctuation);

    std::size_t max_pending_finals_;
    std::int64_t active_utterance_id_ = 1;
    std::int64_t active_revision_ = 0;
    std::deque<PendingFinal> pending_;
    std::optional<Update> latest_stable_final_;
};

}  // namespace dictai::meeting
