package com.kafkasl.phonewhisper

/** Pure mapping from meeting state and gesture to one command for the host. */
internal object MeetingPillInteraction {
    internal enum class Phase {
        READY,
        PREPARING,
        LISTENING,
        PAUSING,
        PAUSED,
        FINALIZING,
        CLOSING,
        FINISHED,
        RESTORED,
        MODEL_UNAVAILABLE,
        ERROR,
    }

    internal enum class Gesture {
        TAP,
        SWIPE_DOWN,
        SWIPE_UP,
    }

    internal enum class Intent {
        NONE,
        START_NEW,
        PAUSE,
        RESUME,
        PROMPT_FINISH,
        SAVE_OPEN_NOTE,
        SHOW_PAUSE_HINT,
        OPEN_MODE_MENU,
        SHOW_MEETING_PANEL,
    }

    internal data class Context(
        val hasDocumentContent: Boolean,
        val dialogOpen: Boolean,
    )

    internal fun resolve(phase: Phase, gesture: Gesture, context: Context): Intent {
        if (context.dialogOpen) return Intent.NONE

        if (gesture == Gesture.SWIPE_UP) {
            return if (phase.isTransitioning()) Intent.NONE else Intent.OPEN_MODE_MENU
        }

        return when (phase) {
            Phase.READY -> if (gesture == Gesture.TAP) Intent.START_NEW else Intent.NONE
            Phase.LISTENING -> when (gesture) {
                Gesture.TAP -> Intent.PAUSE
                Gesture.SWIPE_DOWN -> Intent.SHOW_PAUSE_HINT
                Gesture.SWIPE_UP -> Intent.OPEN_MODE_MENU
            }
            Phase.PAUSED -> when (gesture) {
                Gesture.TAP -> Intent.RESUME
                Gesture.SWIPE_DOWN -> Intent.PROMPT_FINISH
                Gesture.SWIPE_UP -> Intent.OPEN_MODE_MENU
            }
            Phase.FINISHED, Phase.RESTORED -> when (gesture) {
                Gesture.TAP -> Intent.START_NEW
                Gesture.SWIPE_DOWN -> if (context.hasDocumentContent) Intent.SAVE_OPEN_NOTE else Intent.NONE
                Gesture.SWIPE_UP -> Intent.OPEN_MODE_MENU
            }
            Phase.MODEL_UNAVAILABLE, Phase.ERROR -> when (gesture) {
                Gesture.TAP -> Intent.SHOW_MEETING_PANEL
                Gesture.SWIPE_DOWN -> Intent.NONE
                Gesture.SWIPE_UP -> Intent.OPEN_MODE_MENU
            }
            Phase.PREPARING, Phase.PAUSING, Phase.FINALIZING, Phase.CLOSING -> Intent.NONE
        }
    }

    private fun Phase.isTransitioning(): Boolean = when (this) {
        Phase.PREPARING, Phase.PAUSING, Phase.FINALIZING, Phase.CLOSING -> true
        else -> false
    }
}
