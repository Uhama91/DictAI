package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingSpeakerAttributionTest {
    @Test
    fun tolerates_a_single_silent_frame_inside_a_dominant_turn() {
        val frames = List(30) { index ->
            if (index == 10) floatArrayOf(0.0f, 0.0f) else floatArrayOf(0.8f, 0.1f)
        }

        assertEquals(1, MeetingSpeakerAttribution.channelFor(0L, 300L, window(frames)))
    }

    @Test
    fun a_word_ending_at_a_speaker_boundary_uses_only_its_own_frames() {
        val frames = List(8) { index ->
            if (index < 2) floatArrayOf(0.9f, 0.1f) else floatArrayOf(0.1f, 0.9f)
        }

        assertEquals(1, MeetingSpeakerAttribution.channelFor(0L, 20L, window(frames)))
    }

    @Test
    fun an_even_alternation_between_speakers_remains_unknown() {
        val frames = List(30) { index ->
            if (index < 15) floatArrayOf(0.9f, 0.1f) else floatArrayOf(0.1f, 0.9f)
        }

        assertEquals(0, MeetingSpeakerAttribution.channelFor(0L, 300L, window(frames)))
    }

    @Test
    fun sustained_overlap_between_two_active_voices_remains_unknown() {
        val frames = List(30) { index ->
            if (index < 15) floatArrayOf(0.8f, 0.8f) else floatArrayOf(0.8f, 0.1f)
        }

        assertEquals(0, MeetingSpeakerAttribution.channelFor(0L, 300L, window(frames)))
    }

    @Test
    fun a_rival_active_for_thirty_percent_of_the_word_remains_unknown() {
        val frames = List(30) { index ->
            if (index < 21) floatArrayOf(0.8f, 0.1f) else floatArrayOf(0.1f, 0.8f)
        }

        assertEquals(0, MeetingSpeakerAttribution.channelFor(0L, 300L, window(frames)))
    }

    @Test
    fun partial_boundary_frames_are_weighted_by_their_exact_overlap_duration() {
        val frames = listOf(
            floatArrayOf(0.9f, 0.1f),
            floatArrayOf(0.9f, 0.1f),
            floatArrayOf(0.1f, 0.49f),
        )

        assertEquals(1, MeetingSpeakerAttribution.channelFor(5L, 25L, window(frames)))
    }

    @Test
    fun silence_and_invalid_probabilities_remain_unknown() {
        val silence = listOf(floatArrayOf(0.0f, 0.0f), floatArrayOf(0.0f, 0.0f))
        val nan = listOf(floatArrayOf(0.8f, 0.1f), floatArrayOf(Float.NaN, 0.1f))
        val outOfRange = listOf(floatArrayOf(1.01f, 0.1f), floatArrayOf(0.8f, 0.1f))

        assertEquals(0, MeetingSpeakerAttribution.channelFor(0L, 20L, window(silence)))
        assertEquals(0, MeetingSpeakerAttribution.channelFor(0L, 20L, window(nan)))
        assertEquals(0, MeetingSpeakerAttribution.channelFor(0L, 20L, window(outOfRange)))
    }

    @Test
    fun requires_full_coverage_inside_both_the_retained_window_and_stable_prefix() {
        val frames = List(3) { floatArrayOf(0.8f, 0.1f) }

        assertEquals(0, MeetingSpeakerAttribution.channelFor(0L, 10L, window(frames, firstFrame = 1L)))
        assertEquals(0, MeetingSpeakerAttribution.channelFor(
            0L,
            11L,
            window(frames, stableFrameCount = 1L),
        ))
        assertEquals(0, MeetingSpeakerAttribution.channelFor(
            0L,
            11L,
            window(frames.take(1)),
        ))
        assertEquals(0, MeetingSpeakerAttribution.channelFor(0L, 10L, window(emptyList())))
    }

    @Test
    fun frame_ranges_beyond_exact_double_precision_remain_unknown_without_indexing_past_the_window() {
        val frames = List(2) { floatArrayOf(0.8f, 0.1f) }
        val veryLargeWindow = window(
            frames,
            firstFrame = Long.MAX_VALUE - 2L,
            stableFrameCount = Long.MAX_VALUE,
            secondsPerFrame = 0.001,
        )

        assertEquals(0, MeetingSpeakerAttribution.channelFor(Long.MAX_VALUE - 3L, Long.MAX_VALUE - 1L, veryLargeWindow))
    }

    private fun window(
        frames: List<FloatArray>,
        firstFrame: Long = 0L,
        stableFrameCount: Long = firstFrame + frames.size,
        secondsPerFrame: Double = 0.01,
    ) = DiarizationFrameWindow(
        firstFrameIndex = firstFrame,
        secondsPerFrame = secondsPerFrame,
        probabilities = frames.flatMap { it.asIterable() }.toFloatArray(),
        speakerCount = frames.firstOrNull()?.size ?: 1,
        stableFrameCount = stableFrameCount,
        totalFrameCount = maxOf(stableFrameCount, firstFrame + frames.size),
    )
}
