package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingPillInteractionTest {
    private fun intent(
        phase: MeetingPillInteraction.Phase,
        gesture: MeetingPillInteraction.Gesture,
        hasDocumentContent: Boolean = false,
        dialogOpen: Boolean = false,
    ) = MeetingPillInteraction.resolve(
        phase,
        gesture,
        MeetingPillInteraction.Context(hasDocumentContent, dialogOpen),
    )

    @Test
    fun tapMapsStableMeetingPhasesToOneExplicitCommand() {
        val tap = MeetingPillInteraction.Gesture.TAP
        assertEquals(MeetingPillInteraction.Intent.START_NEW, intent(MeetingPillInteraction.Phase.READY, tap))
        assertEquals(MeetingPillInteraction.Intent.NONE, intent(MeetingPillInteraction.Phase.PREPARING, tap))
        assertEquals(MeetingPillInteraction.Intent.PAUSE, intent(MeetingPillInteraction.Phase.LISTENING, tap))
        assertEquals(MeetingPillInteraction.Intent.NONE, intent(MeetingPillInteraction.Phase.PAUSING, tap))
        assertEquals(MeetingPillInteraction.Intent.RESUME, intent(MeetingPillInteraction.Phase.PAUSED, tap))
        assertEquals(MeetingPillInteraction.Intent.NONE, intent(MeetingPillInteraction.Phase.FINALIZING, tap))
        assertEquals(MeetingPillInteraction.Intent.NONE, intent(MeetingPillInteraction.Phase.CLOSING, tap))
        assertEquals(MeetingPillInteraction.Intent.START_NEW, intent(MeetingPillInteraction.Phase.FINISHED, tap))
        assertEquals(MeetingPillInteraction.Intent.START_NEW, intent(MeetingPillInteraction.Phase.RESTORED, tap))
        assertEquals(
            MeetingPillInteraction.Intent.SHOW_MEETING_PANEL,
            intent(MeetingPillInteraction.Phase.MODEL_UNAVAILABLE, tap),
        )
        assertEquals(MeetingPillInteraction.Intent.SHOW_MEETING_PANEL, intent(MeetingPillInteraction.Phase.ERROR, tap))
    }

    @Test
    fun downwardGesturePausesByHintAndOnlyOffersFinishAfterPause() {
        val down = MeetingPillInteraction.Gesture.SWIPE_DOWN
        assertEquals(MeetingPillInteraction.Intent.NONE, intent(MeetingPillInteraction.Phase.READY, down))
        assertEquals(MeetingPillInteraction.Intent.SHOW_PAUSE_HINT, intent(MeetingPillInteraction.Phase.LISTENING, down))
        assertEquals(MeetingPillInteraction.Intent.NONE, intent(MeetingPillInteraction.Phase.PAUSING, down))
        assertEquals(MeetingPillInteraction.Intent.PROMPT_FINISH, intent(MeetingPillInteraction.Phase.PAUSED, down))
        assertEquals(MeetingPillInteraction.Intent.NONE, intent(MeetingPillInteraction.Phase.FINALIZING, down))
        assertEquals(MeetingPillInteraction.Intent.NONE, intent(MeetingPillInteraction.Phase.CLOSING, down))
    }

    @Test
    fun finishedOrRestoredNoteIsSavedOnlyWhenItHasContent() {
        val down = MeetingPillInteraction.Gesture.SWIPE_DOWN
        listOf(MeetingPillInteraction.Phase.FINISHED, MeetingPillInteraction.Phase.RESTORED).forEach { phase ->
            assertEquals(MeetingPillInteraction.Intent.NONE, intent(phase, down))
            assertEquals(MeetingPillInteraction.Intent.SAVE_OPEN_NOTE, intent(phase, down, hasDocumentContent = true))
        }
    }

    @Test
    fun upwardGestureOpensModeMenuOnlyOutsideTransitions() {
        val up = MeetingPillInteraction.Gesture.SWIPE_UP
        listOf(
            MeetingPillInteraction.Phase.READY,
            MeetingPillInteraction.Phase.LISTENING,
            MeetingPillInteraction.Phase.PAUSED,
            MeetingPillInteraction.Phase.FINISHED,
            MeetingPillInteraction.Phase.RESTORED,
            MeetingPillInteraction.Phase.MODEL_UNAVAILABLE,
            MeetingPillInteraction.Phase.ERROR,
        ).forEach { phase ->
            assertEquals(MeetingPillInteraction.Intent.OPEN_MODE_MENU, intent(phase, up))
        }
        listOf(
            MeetingPillInteraction.Phase.PREPARING,
            MeetingPillInteraction.Phase.PAUSING,
            MeetingPillInteraction.Phase.FINALIZING,
            MeetingPillInteraction.Phase.CLOSING,
        ).forEach { phase ->
            assertEquals(MeetingPillInteraction.Intent.NONE, intent(phase, up))
        }
    }

    @Test
    fun anOpenDialogSuppressesEveryGestureIntent() {
        MeetingPillInteraction.Phase.values().forEach { phase ->
            MeetingPillInteraction.Gesture.values().forEach { gesture ->
                assertEquals(MeetingPillInteraction.Intent.NONE, intent(phase, gesture, hasDocumentContent = true, dialogOpen = true))
            }
        }
    }
}
