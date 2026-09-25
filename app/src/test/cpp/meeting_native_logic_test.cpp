#include <cmath>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

#include "meeting_native_logic.h"

namespace meeting = dictai::meeting;

void expect(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

meeting::Update update(const std::string& transcript, bool final = false) {
    meeting::Update value;
    value.transcript = transcript;
    value.is_final = final;
    value.audio_processed_ms = 160;
    if (!transcript.empty()) value.words.push_back({transcript, 40, 100, 0});
    return value;
}

meeting::Update update_ending_at(const std::string& transcript, std::int64_t end_ms) {
    auto value = update(transcript, true);
    value.words[0].start_ms = end_ms - 60;
    value.words[0].end_ms = end_ms;
    value.audio_processed_ms = end_ms;
    return value;
}

int main() {
    const std::vector<uint8_t> pcm = {0x00, 0x80, 0xff, 0x7f, 0x00, 0x00};
    expect(meeting::validate_pcm16_length(pcm.size(), 6) == 3, "sample count");
    const auto samples = meeting::pcm16le_to_float(pcm.data(), pcm.size(), 6);
    expect(samples.size() == 3, "converted sample count");
    expect(samples[0] == -1.0f, "negative full scale");
    expect(std::abs(samples[1] - 32767.0f / 32768.0f) < 1e-7f, "positive full scale");
    expect(samples[2] == 0.0f, "zero sample");

    const std::vector<std::int64_t> invalid_lengths = {
        -2, 1, 7, static_cast<std::int64_t>(meeting::kMaxPcmBytes + 2)};
    for (const auto invalid : invalid_lengths) {
        bool rejected = false;
        try {
            meeting::validate_pcm16_length(pcm.size(), invalid);
        } catch (const std::invalid_argument&) {
            rejected = true;
        }
        expect(rejected, "invalid PCM length rejected");
    }

    meeting::UpdateSequencer sequencer;
    const auto interim = sequencer.interim(update("Bonjour"));
    expect(interim.utterance_id == 1 && interim.revision == 1 && !interim.is_final,
           "first interim identity");
    const auto final = sequencer.final(update("Bonjour", true));
    expect(final.has_value(), "final is emitted immediately");
    expect(final->utterance_id == 1 && final->revision == 2 && final->is_final,
           "final continues utterance revisions");
    expect(sequencer.pending_count() == 1, "unstable final is retained");

    auto retagged = *final;
    retagged.words[0].channel = 3;
    auto changes = sequencer.refresh_final(1, retagged, 60);
    expect(changes.size() == 1 && changes[0].revision == 3 && changes[0].words[0].channel == 3,
           "speaker retag revises the same final");
    expect(sequencer.pending_count() == 1, "final remains pending until stable frontier");

    changes = sequencer.refresh_final(1, retagged, 100);
    expect(changes.size() == 1 && changes[0].revision == 4 &&
               changes[0].stable_speaker_through_ms == 100,
           "stable frontier releases a final with a revision");
    expect(sequencer.pending_count() == 0, "stable final is released");

    changes = sequencer.late_punctuation(".");
    expect(changes.size() == 1 && changes[0].utterance_id == 1 && changes[0].revision == 5,
           "late punctuation revises the old stable utterance");
    expect(changes[0].transcript == "Bonjour." && changes[0].words[0].text == "Bonjour.",
           "late punctuation is attached to the old final");
    const auto next = sequencer.interim(update("Ensuite"));
    expect(next.utterance_id == 2 && next.revision == 1 && next.transcript == "Ensuite",
           "late punctuation never leaks to the next utterance");
    expect(next.words[0].start_ms == 40 && next.words[0].end_ms == 100,
           "word times remain on the global audio timeline");

    meeting::UpdateSequencer queued_punctuation;
    queued_punctuation.final(update("Terminé", true));
    expect(queued_punctuation.late_punctuation("!").empty(),
           "punctuation waits while the final is unstable");
    const auto queued_final = queued_punctuation.final(update("Suivant", true));
    expect(queued_final.has_value(), "next final is retained independently");
    changes = queued_punctuation.refresh_final(1, update("Terminé", true), 100);
    expect(changes.size() == 2 && changes[0].transcript == "Terminé" &&
               changes[1].transcript == "Terminé!" && changes[1].utterance_id == 1,
           "queued punctuation is applied when its old final becomes stable");

    meeting::UpdateSequencer bounded(1);
    bounded.final(update("one", true));
    bool overflow_rejected = false;
    try {
        bounded.final(update("two", true));
    } catch (const std::length_error&) {
        overflow_rejected = true;
    }
    expect(overflow_rejected && bounded.pending_count() == 1,
           "pending finals have an explicit hard bound");

    meeting::UpdateSequencer newer_stable_first;
    const auto earlier_value = update_ending_at("Earlier", 500);
    const auto earlier_final = newer_stable_first.final(earlier_value);
    const auto later_value = update_ending_at("Later", 300);
    const auto later_final = newer_stable_first.final(later_value);
    expect(earlier_final && later_final && earlier_final->utterance_id == 1 &&
               later_final->utterance_id == 2,
           "overlapping finals have distinct increasing IDs");
    changes = newer_stable_first.refresh_final(2, *later_final, 350);
    expect(changes.size() == 1 && changes[0].utterance_id == 2,
           "newer overlapping final can stabilize first");
    changes = newer_stable_first.late_punctuation("!");
    expect(changes.size() == 1 && changes[0].utterance_id == 2 &&
               changes[0].transcript == "Later!",
           "punctuation targets newer stable final over older pending final");
    changes = newer_stable_first.refresh_final(1, *earlier_final, 500);
    expect(changes.size() == 1 && changes[0].utterance_id == 1,
           "older overlapping final can stabilize later");
    changes = newer_stable_first.late_punctuation("?");
    expect(changes.size() == 1 && changes[0].utterance_id == 2 &&
               changes[0].transcript == "Later!?",
           "stabilizing an older final does not replace the newer punctuation target");

    meeting::UpdateSequencer newer_pending_first;
    const auto pending_earlier = newer_pending_first.final(update_ending_at("Earlier", 500));
    const auto pending_later = newer_pending_first.final(update_ending_at("Later", 300));
    expect(pending_earlier && pending_later, "both overlapping finals remain available");
    expect(newer_pending_first.late_punctuation("!").empty(),
           "punctuation waits on the newest pending final");
    changes = newer_pending_first.refresh_final(2, *pending_later, 350);
    expect(changes.size() == 2 && changes[0].utterance_id == 2 &&
               changes[1].utterance_id == 2 && changes[1].transcript == "Later!",
           "queued punctuation follows the newest final when it stabilizes first");
    changes = newer_pending_first.refresh_final(1, *pending_earlier, 500);
    expect(changes.size() == 1 && changes[0].utterance_id == 1,
           "older final can stabilize after queued punctuation was applied");
    changes = newer_pending_first.late_punctuation("?");
    expect(changes.size() == 1 && changes[0].utterance_id == 2 &&
               changes[0].transcript == "Later!?",
           "newer stable final remains the punctuation target after older release");

    std::cout << "meeting_native_logic_test: PASS\n";
}
