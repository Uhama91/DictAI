package com.kafkasl.phonewhisper

/** The destination is chosen by the user, never inferred from the presence of another app's field. */
internal enum class DictationPurpose {
    MESSAGE, NOTE;

    companion object {
        fun restore(stored: String?, noteId: String?): DictationPurpose = when {
            noteId != null -> NOTE
            stored == null || stored == MESSAGE.name -> MESSAGE
            else -> NOTE // A missing/deleted note or an unknown newer purpose must not become a message.
        }
    }
}

internal object NoteInteractionPolicy {
    enum class Destination { MESSAGE, NOTE, NOTE_LIST, NOTE_EXPORT }

    fun tap(purpose: DictationPurpose, action: DictationTapGestureCoordinator.Action): DictationTapGestureCoordinator.Action =
        if (purpose == DictationPurpose.MESSAGE) action else when (action) {
            DictationTapGestureCoordinator.Action.STOP_RECORDING -> DictationTapGestureCoordinator.Action.PAUSE_RECORDING
            DictationTapGestureCoordinator.Action.CANCEL_RECORDING,
            DictationTapGestureCoordinator.Action.CANCEL_RECORDING_AND_OPEN_APP -> DictationTapGestureCoordinator.Action.SAVE_AND_CLOSE_NOTE
            DictationTapGestureCoordinator.Action.CANCEL_PROCESSING,
            DictationTapGestureCoordinator.Action.ARM_PROCESSING_WINDOW -> DictationTapGestureCoordinator.Action.NONE
            else -> action
        }

    /** Also used at completion: delayed capture/pause callbacks cannot bypass note protection. */
    fun destination(purpose: DictationPurpose, archive: Boolean, export: Boolean): Destination = when {
        archive -> Destination.NOTE_LIST
        export -> Destination.NOTE_EXPORT
        purpose == DictationPurpose.NOTE -> Destination.NOTE
        else -> Destination.MESSAGE
    }
}

/** A transient, single-use confirmation. No permission is stored with the note or resumed run. */
internal class NoteInsertionGate {
    class Request internal constructor(val noteId: String, val text: String)
    private var pending: Request? = null

    fun request(noteId: String, text: String): Request? {
        pending = text.takeIf { it.isNotBlank() }?.let { Request(noteId, it) }
        return pending
    }

    fun consume(request: Request, currentNoteId: String?, currentText: String): String? {
        if (pending !== request) return null
        pending = null
        return request.text.takeIf { currentNoteId == request.noteId && currentText == request.text }
    }

    fun invalidate() { pending = null }
}
