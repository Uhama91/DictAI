package com.kafkasl.phonewhisper

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

class DictationTapGestureCoordinatorTest {
    @Test
    fun established_recording_double_tap_cancels_before_any_stop() {
        val coordinator = DictationTapGestureCoordinator(windowMs = 280)

        val first = coordinator.onTap(
            DictationTapGestureCoordinator.SurfaceState.RECORDING,
            atMs = 1_000,
        )
        assertEquals(DictationTapGestureCoordinator.Action.NONE, first.action)
        assertNotNull(first.timeout)

        val second = coordinator.onTap(
            DictationTapGestureCoordinator.SurfaceState.RECORDING,
            atMs = 1_279,
        )
        assertEquals(DictationTapGestureCoordinator.Action.CANCEL_RECORDING, second.action)
    }

    @Test
    fun established_recording_single_tap_stops_only_when_its_timeout_expires() {
        val coordinator = DictationTapGestureCoordinator(windowMs = 280)
        val deferred = coordinator.onTap(
            DictationTapGestureCoordinator.SurfaceState.RECORDING,
            atMs = 2_000,
        )
        val timeout = checkNotNull(deferred.timeout)

        assertEquals(
            DictationTapGestureCoordinator.Action.NONE,
            coordinator.onTimeout(timeout, atMs = 2_279).action,
        )
        assertEquals(
            DictationTapGestureCoordinator.Action.STOP_RECORDING,
            coordinator.onTimeout(timeout, atMs = 2_280).action,
        )
    }

    @Test
    fun idle_double_tap_keeps_legacy_cancel_and_open_app_behavior() {
        val coordinator = DictationTapGestureCoordinator(windowMs = 280)

        assertEquals(
            DictationTapGestureCoordinator.Action.START_RECORDING,
            coordinator.onTap(DictationTapGestureCoordinator.SurfaceState.IDLE, atMs = 3_000).action,
        )
        assertEquals(
            DictationTapGestureCoordinator.Action.CANCEL_RECORDING_AND_OPEN_APP,
            coordinator.onTap(DictationTapGestureCoordinator.SurfaceState.RECORDING, atMs = 3_279).action,
        )
    }

    @Test
    fun processing_double_tap_still_cancels_without_publication() {
        val coordinator = DictationTapGestureCoordinator(windowMs = 280)

        assertEquals(
            DictationTapGestureCoordinator.Action.ARM_PROCESSING_WINDOW,
            coordinator.onTap(
                DictationTapGestureCoordinator.SurfaceState.TRANSCRIBING,
                atMs = 4_000,
            ).action,
        )
        assertEquals(
            DictationTapGestureCoordinator.Action.CANCEL_PROCESSING,
            coordinator.onTap(
                DictationTapGestureCoordinator.SurfaceState.TRANSCRIBING,
                atMs = 4_279,
            ).action,
        )
    }

    @Test
    fun exact_boundary_is_not_a_double_tap_and_rearms_processing_window() {
        val coordinator = DictationTapGestureCoordinator(windowMs = 280)

        assertEquals(
            DictationTapGestureCoordinator.Action.ARM_PROCESSING_WINDOW,
            coordinator.onTap(
                DictationTapGestureCoordinator.SurfaceState.TRANSCRIBING,
                atMs = 5_000,
            ).action,
        )
        assertEquals(
            DictationTapGestureCoordinator.Action.ARM_PROCESSING_WINDOW,
            coordinator.onTap(
                DictationTapGestureCoordinator.SurfaceState.TRANSCRIBING,
                atMs = 5_280,
            ).action,
        )
        assertEquals(
            DictationTapGestureCoordinator.Action.CANCEL_PROCESSING,
            coordinator.onTap(
                DictationTapGestureCoordinator.SurfaceState.TRANSCRIBING,
                atMs = 5_559,
            ).action,
        )
    }

    @Test
    fun stale_recording_timeout_is_a_no_op_after_cancellation() {
        val coordinator = DictationTapGestureCoordinator(windowMs = 280)
        val deferred = coordinator.onTap(
            DictationTapGestureCoordinator.SurfaceState.RECORDING,
            atMs = 6_000,
        )
        val timeout = checkNotNull(deferred.timeout)

        assertEquals(
            DictationTapGestureCoordinator.Action.CANCEL_RECORDING,
            coordinator.onTap(
                DictationTapGestureCoordinator.SurfaceState.RECORDING,
                atMs = 6_100,
            ).action,
        )
        assertEquals(
            DictationTapGestureCoordinator.Action.NONE,
            coordinator.onTimeout(timeout, atMs = 6_280).action,
        )
    }

    @Test
    fun mic_unarmed_prompts_for_setup_before_opening_app() {
        val coordinator = DictationTapGestureCoordinator(windowMs = 280)

        assertEquals(
            DictationTapGestureCoordinator.Action.PROMPT_MIC_SETUP_AND_OPEN_APP,
            coordinator.onTap(
                DictationTapGestureCoordinator.SurfaceState.MIC_UNARMED,
                atMs = 7_000,
            ).action,
        )
    }
}
