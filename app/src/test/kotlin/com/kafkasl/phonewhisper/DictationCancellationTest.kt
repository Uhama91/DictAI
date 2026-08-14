package com.kafkasl.phonewhisper

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationCancellationTest {
    @Test
    fun cancellation_prevents_a_late_publication() {
        val cancellation = DictationCancellationCoordinator()
        var published = false

        assertTrue(cancellation.cancel())
        assertFalse(cancellation.publishIfActive { published = true })
        assertFalse(published)
    }

    @Test
    fun cancellation_runs_each_registered_action_once_and_is_idempotent() {
        val cancellation = DictationCancellationCoordinator()
        val calls = AtomicInteger(0)

        cancellation.onCancel { calls.incrementAndGet() }

        assertTrue(cancellation.cancel())
        assertFalse(cancellation.cancel())
        cancellation.onCancel { calls.incrementAndGet() }

        assertEquals(2, calls.get())
        assertTrue(cancellation.isCancelled)
    }
}

class DictationPreviewPublicationGateTest {
    @Test
    fun non_recording_preview_is_rejected_even_for_the_current_run() {
        var published = false

        val accepted = DictationPreviewPublicationGate.publishIfAllowed(
            isCurrentRun = true,
            isRecording = false,
            cancellation = DictationCancellationCoordinator(),
        ) { published = true }

        assertFalse(accepted)
        assertFalse(published)
    }

    @Test
    fun cancelled_preview_is_rejected_even_if_the_run_still_looks_recording() {
        val cancellation = DictationCancellationCoordinator()
        cancellation.cancel()
        var published = false

        val accepted = DictationPreviewPublicationGate.publishIfAllowed(
            isCurrentRun = true,
            isRecording = true,
            cancellation = cancellation,
        ) { published = true }

        assertFalse(accepted)
        assertFalse(published)
    }
}

class DictationDoubleTapTest {
    @Test
    fun second_tap_inside_the_existing_window_is_double_and_consumes_the_sequence() {
        val detector = DoubleTapDetector(windowMs = 280)

        assertEquals(DoubleTapDetector.Tap.SINGLE, detector.registerTap(1_000))
        assertEquals(DoubleTapDetector.Tap.DOUBLE, detector.registerTap(1_279))
        assertEquals(DoubleTapDetector.Tap.SINGLE, detector.registerTap(1_280))
    }

    @Test
    fun tap_at_the_window_boundary_is_a_new_single_tap_that_rearms_the_detector() {
        val detector = DoubleTapDetector(windowMs = 280)

        assertEquals(DoubleTapDetector.Tap.SINGLE, detector.registerTap(2_000))
        assertEquals(DoubleTapDetector.Tap.SINGLE, detector.registerTap(2_280))
        assertEquals(DoubleTapDetector.Tap.DOUBLE, detector.registerTap(2_559))
    }
}
