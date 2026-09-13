package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class PausedTapGestureTest {
    @Test fun `single paused tap resumes only after double tap window`() {
        val taps = DictationTapGestureCoordinator()
        val first = taps.onTap(DictationTapGestureCoordinator.SurfaceState.PAUSED, 1000)
        assertEquals(DictationTapGestureCoordinator.Action.NONE, first.action)
        assertEquals(DictationTapGestureCoordinator.Action.NONE, taps.onTimeout(first.timeout!!, 1279).action)
        assertEquals(DictationTapGestureCoordinator.Action.RESUME_RECORDING, taps.onTimeout(first.timeout, 1280).action)
    }
    @Test fun `double paused tap cancels without briefly resuming microphone`() {
        val taps = DictationTapGestureCoordinator()
        val first = taps.onTap(DictationTapGestureCoordinator.SurfaceState.PAUSED, 1000)
        assertEquals(DictationTapGestureCoordinator.Action.CANCEL_RECORDING,
            taps.onTap(DictationTapGestureCoordinator.SurfaceState.PAUSED, 1100).action)
        assertEquals(DictationTapGestureCoordinator.Action.NONE, taps.onTimeout(first.timeout!!, 1300).action)
    }
    @Test fun `drag invalidates pending resume`() {
        val taps = DictationTapGestureCoordinator()
        val first = taps.onTap(DictationTapGestureCoordinator.SurfaceState.PAUSED, 1000)
        taps.reset()
        assertEquals(DictationTapGestureCoordinator.Action.NONE, taps.onTimeout(first.timeout!!, 1300).action)
    }
}
