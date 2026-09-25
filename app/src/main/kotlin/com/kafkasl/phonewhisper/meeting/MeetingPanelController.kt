package com.kafkasl.phonewhisper.meeting

import android.content.Context
import android.os.Looper
import com.kafkasl.phonewhisper.NoteImage
import com.kafkasl.phonewhisper.NoteImageMarkers
import com.kafkasl.phonewhisper.TranscriptImageBlocks

internal data class MeetingPanelChoice(val id: String, val label: String)

internal data class MeetingPanelChoicesRequest(
    val title: String,
    val choices: List<MeetingPanelChoice>,
)

internal data class MeetingPanelTextInputRequest(
    val title: String,
    val initialText: String,
)

internal interface MeetingPanelDialogHost {
    fun showChoices(request: MeetingPanelChoicesRequest, onChoice: (String?) -> Unit)
    fun showTextInput(request: MeetingPanelTextInputRequest, onSubmit: (String?) -> Unit)
}

internal enum class MeetingPanelSessionCommand {
    START,
    PAUSE,
    RESUME,
    FINISH,
    CANCEL,
    CANCEL_DOWNLOAD,
}

internal enum class MeetingPanelDocumentAction {
    PHOTO,
    SCREENSHOT,
    COPY,
    EXPORT,
}

internal data class MeetingPanelActions(
    val edit: (String, String) -> Unit = { _, _ -> },
    val assign: (String, String?) -> Unit = { _, _ -> },
    val rename: (String, String) -> Unit = { _, _ -> },
    val setIgnored: (String, Boolean) -> Unit = { _, _ -> },
    val download: () -> Unit = {},
    val retrySave: () -> Unit = {},
    val sessionCommand: (MeetingPanelSessionCommand) -> Unit = {},
    val imageAction: (MeetingPanelAnchor?, NoteImage) -> Unit = { _, _ -> },
    val reviewPassages: () -> Unit = {},
    val documentAction: (MeetingPanelDocumentAction, MeetingPanelAnchor?) -> Unit = { _, _ -> },
)

internal data class MeetingPanelStatus(
    val phase: Phase = Phase.READY,
    val detail: String? = null,
    val progressPercent: Int? = null,
    val modelSize: String? = null,
    val saveError: String? = null,
) {
    internal enum class Phase {
        READY,
        LISTENING,
        PAUSED,
        PAUSING,
        LOADING,
        FINALIZING,
        CLOSING,
        FINISHED,
        ERROR,
        DOWNLOADING,
        MODEL_UNAVAILABLE,
        DELAYED,
    }
}

internal data class MeetingPanelAnchor(val turnId: String, val serializedOffset: Int)

/** Main-thread owner of one immutable render snapshot and identified UI commands. */
internal class MeetingPanelController(
    context: Context,
    private val dialogHost: MeetingPanelDialogHost,
    private val actions: MeetingPanelActions = MeetingPanelActions(),
    private val onAcquireWindow: () -> Unit = {},
    private val onReleaseWindow: () -> Unit = {},
) : MeetingPanelRowCallbacks {
    val view: MeetingPanelView = MeetingPanelView(context)
    private var disposed = false
    private var document: MeetingDocument? = null
    private var status = MeetingPanelStatus()
    private var pendingRender: RenderRequest? = null
    private var pendingRenderPosted = false

    private data class RenderRequest(
        val document: MeetingDocument,
        val images: List<NoteImage>,
        val status: MeetingPanelStatus,
    )

    private data class SessionToken(val sessionId: String, val runId: String)

    private val applyPendingRender = object : Runnable {
        override fun run() {
            pendingRenderPosted = false
            if (disposed) {
                pendingRender = null
                return
            }
            if (view.hasNotifyingEditor()) {
                pendingRenderPosted = view.postDelayed(this, 1L)
                return
            }
            val latest = pendingRender ?: return
            pendingRender = null
            applyRender(latest)
        }
    }

    init {
        view.setRowCallbacks(this)
        view.onParticipants = ::showParticipants
        view.onActions = ::showActions
        view.onCancel = ::cancelCurrentOperation
        view.onRetrySave = ::retrySave
    }

    fun render(
        document: MeetingDocument,
        images: List<NoteImage> = emptyList(),
        status: MeetingPanelStatus = MeetingPanelStatus(),
    ) {
        if (disposed) return
        check(Looper.myLooper() == Looper.getMainLooper()) { "Meeting panel rendering must run on the main thread" }
        val request = RenderRequest(document, images.toList(), status)
        if (view.hasNotifyingEditor()) {
            pendingRender = request
            if (!pendingRenderPosted) pendingRenderPosted = view.post(applyPendingRender)
            return
        }
        view.removeCallbacks(applyPendingRender)
        pendingRenderPosted = false
        pendingRender = null
        applyRender(request)
    }

    fun flushFocusedEdit(): Boolean {
        if (disposed) return false
        return view.focusedTurnEditor()?.flushFocusedEdit() ?: false
    }

    fun captureAnchor(): MeetingPanelAnchor? {
        if (disposed) return null
        return view.focusedTurnEditor()?.serializedAnchor()
    }

    fun refreshTheme() {
        if (!disposed) view.refreshTheme()
    }

    /** Flushes the active composition before releasing the overlay's focusable window flags. */
    fun endEditing() {
        if (disposed) return
        flushFocusedEdit()
        view.focusedTurnEditor()?.finishComposition()
        view.visibleTurnEditors().forEach(MeetingTurnEditor::endEditingField)
        onReleaseWindow()
    }

    fun dispose() {
        if (disposed) return
        endEditing()
        disposed = true
        pendingRender = null
        view.removeCallbacks(applyPendingRender)
        view.cancelPendingScrollRestore()
        pendingRenderPosted = false
        view.onParticipants = {}
        view.onActions = {}
        view.onCancel = {}
        view.onRetrySave = {}
        view.visibleTurnEditors().forEach(MeetingTurnEditor::bindPlaceholder)
    }

    override fun edit(turnId: String, serializedText: String) {
        if (!disposed) actions.edit(turnId, serializedText)
    }

    override fun assign(turnId: String) {
        val snapshot = document ?: return
        val choices = participantChoices(snapshot.participants, includeUnknown = true)
        showChoices("Attribuer la prise de parole", choices) { selectedId ->
            flushFocusedEdit()
            if (disposed || document?.turns?.any { it.id == turnId } != true) return@showChoices
            actions.assign(turnId, selectedId.takeUnless { it == UNKNOWN_PARTICIPANT_ID })
        }
    }

    override fun acquireWindow() {
        if (!disposed) onAcquireWindow()
    }

    override fun finishEditing() = endEditing()

    override fun imageAction(entry: MeetingPanelEntry, image: NoteImage) {
        if (disposed) return
        val anchor = entry.turnId?.let { id ->
            view.findTurnEditor(id)?.takeIf { it.entry?.row?.editableSpeech == true }?.serializedAnchor()
        }
        actions.imageAction(anchor, image)
    }

    private fun applyRender(request: RenderRequest) {
        if (disposed) return
        document = request.document
        status = request.status
        view.updateHeader(request.document, request.status)

        val byNumber = request.images.distinctBy { it.number }.associateBy { it.number }
        val emittedNumbers = mutableSetOf<Int>()
        val entries = MeetingProjection.rows(
            request.document,
            imageNumbers = byNumber.keys,
            keepEmptyTurns = true,
        ).asSequence()
            .filter { it.editableSpeech || it.body.isNotBlank() }
            .map { row ->
                val rowImages = NoteImageMarkers.numbers(row.body).distinct().mapNotNull { number ->
                    byNumber[number]?.takeIf { emittedNumbers.add(number) }
                }
                MeetingPanelEntry(
                    stableKey = "turn:${row.turnId}",
                    turnId = row.turnId,
                    row = row,
                    projection = TranscriptImageBlocks.fromNote(row.body, rowImages),
                    images = rowImages,
                )
            }
            .toMutableList()
        val orphans = request.images.distinctBy { it.number }.filter { emittedNumbers.add(it.number) }
        if (orphans.isNotEmpty()) {
            entries += MeetingPanelEntry(
                stableKey = "meeting:orphan-images",
                turnId = null,
                row = null,
                projection = TranscriptImageBlocks.fromNote("", orphans),
                images = orphans,
                orphanImages = true,
            )
        }
        view.submitRows(entries)
    }

    private fun showParticipants() {
        val snapshot = document ?: return
        val participants = snapshot.participants.take(8).toList()
        if (participants.isEmpty()) return
        showChoices("Intervenants (${participants.size})", participantChoices(participants)) { selectedId ->
            val profile = participants.firstOrNull { it.id == selectedId } ?: return@showChoices
            showProfileActions(profile)
        }
    }

    private fun showProfileActions(profile: MeetingParticipant) {
        val visibilityAction = if (profile.ignored) {
            "Réafficher ses interventions dans cette réunion"
        } else {
            "Masquer ses interventions dans cette réunion"
        }
        showChoices(
            profileDisplayName(profile),
            listOf(
                MeetingPanelChoice(PROFILE_RENAME, "Renommer"),
                MeetingPanelChoice(PROFILE_TOGGLE_IGNORED, visibilityAction),
            ),
        ) { actionId ->
            when (actionId) {
                PROFILE_RENAME -> showTextInput("Renommer ${profileDisplayName(profile)}", profile.name.orEmpty()) { name ->
                    if (name != null && !disposed) actions.rename(profile.id, name)
                }
                PROFILE_TOGGLE_IGNORED -> {
                    flushFocusedEdit()
                    view.focusedTurnEditor()?.finishComposition()
                    if (!disposed) actions.setIgnored(profile.id, !profile.ignored)
                }
            }
        }
    }

    private fun showActions() {
        if (disposed) return
        flushFocusedEdit()
        if (disposed) return
        val documentAnchor = captureAnchor()
        val choices = mutableListOf(MeetingPanelChoice(ACTION_REVIEW, "Passages à vérifier"))
        status.saveError?.takeIf(String::isNotBlank)?.let {
            choices += MeetingPanelChoice(ACTION_RETRY_SAVE, "Réessayer la sauvegarde")
        }
        when (status.phase) {
            MeetingPanelStatus.Phase.READY,
            MeetingPanelStatus.Phase.LISTENING,
            MeetingPanelStatus.Phase.DELAYED,
            MeetingPanelStatus.Phase.PAUSED,
            MeetingPanelStatus.Phase.FINISHED,
            MeetingPanelStatus.Phase.MODEL_UNAVAILABLE,
            MeetingPanelStatus.Phase.ERROR -> choices += documentActionChoices()
            MeetingPanelStatus.Phase.LOADING,
            MeetingPanelStatus.Phase.DOWNLOADING,
            MeetingPanelStatus.Phase.PAUSING,
            MeetingPanelStatus.Phase.FINALIZING,
            MeetingPanelStatus.Phase.CLOSING -> Unit
        }
        when (status.phase) {
            MeetingPanelStatus.Phase.READY -> choices += MeetingPanelChoice(ACTION_START, "Démarrer la réunion")
            MeetingPanelStatus.Phase.LISTENING,
            MeetingPanelStatus.Phase.DELAYED -> {
                choices += MeetingPanelChoice(ACTION_PAUSE, "Mettre en pause")
                choices += MeetingPanelChoice(ACTION_FINISH, "Terminer la réunion")
                choices += MeetingPanelChoice(ACTION_CANCEL, "Annuler la réunion")
            }
            MeetingPanelStatus.Phase.PAUSED -> {
                choices += MeetingPanelChoice(ACTION_RESUME, "Reprendre la réunion")
                choices += MeetingPanelChoice(ACTION_FINISH, "Terminer la réunion")
                choices += MeetingPanelChoice(ACTION_CANCEL, "Annuler la réunion")
            }
            MeetingPanelStatus.Phase.LOADING -> choices += MeetingPanelChoice(ACTION_CANCEL, "Annuler le chargement")
            MeetingPanelStatus.Phase.DOWNLOADING -> choices += MeetingPanelChoice(ACTION_CANCEL_DOWNLOAD, "Annuler le téléchargement")
            MeetingPanelStatus.Phase.MODEL_UNAVAILABLE -> choices += MeetingPanelChoice(ACTION_DOWNLOAD, "Télécharger le modèle")
            MeetingPanelStatus.Phase.FINISHED -> choices += MeetingPanelChoice(ACTION_START, "Nouvelle réunion")
            MeetingPanelStatus.Phase.ERROR -> choices += MeetingPanelChoice(ACTION_CANCEL, "Fermer la réunion")
            MeetingPanelStatus.Phase.PAUSING,
            MeetingPanelStatus.Phase.FINALIZING,
            MeetingPanelStatus.Phase.CLOSING -> Unit
        }
        showChoices("Actions de la réunion", choices) { actionId ->
            when (actionId) {
                ACTION_REVIEW -> actions.reviewPassages()
                ACTION_RETRY_SAVE -> retrySave()
                ACTION_DOWNLOAD -> actions.download()
                ACTION_START -> dispatchSession(MeetingPanelSessionCommand.START)
                ACTION_PAUSE -> dispatchSession(MeetingPanelSessionCommand.PAUSE)
                ACTION_RESUME -> dispatchSession(MeetingPanelSessionCommand.RESUME)
                ACTION_FINISH -> dispatchSession(MeetingPanelSessionCommand.FINISH)
                ACTION_CANCEL -> dispatchSession(MeetingPanelSessionCommand.CANCEL)
                ACTION_CANCEL_DOWNLOAD -> actions.sessionCommand(MeetingPanelSessionCommand.CANCEL_DOWNLOAD)
                ACTION_DOCUMENT_PHOTO -> actions.documentAction(MeetingPanelDocumentAction.PHOTO, documentAnchor)
                ACTION_DOCUMENT_SCREENSHOT -> actions.documentAction(MeetingPanelDocumentAction.SCREENSHOT, documentAnchor)
                ACTION_DOCUMENT_COPY -> actions.documentAction(MeetingPanelDocumentAction.COPY, documentAnchor)
                ACTION_DOCUMENT_EXPORT -> actions.documentAction(MeetingPanelDocumentAction.EXPORT, documentAnchor)
            }
        }
    }

    private fun documentActionChoices(): List<MeetingPanelChoice> = listOf(
        MeetingPanelChoice(ACTION_DOCUMENT_PHOTO, "Prendre une photo"),
        MeetingPanelChoice(ACTION_DOCUMENT_SCREENSHOT, "Ajouter une capture"),
        MeetingPanelChoice(ACTION_DOCUMENT_COPY, "Copier la transcription"),
        MeetingPanelChoice(ACTION_DOCUMENT_EXPORT, "Exporter la note"),
    )

    private fun cancelCurrentOperation() {
        when (status.phase) {
            MeetingPanelStatus.Phase.LOADING -> dispatchSession(MeetingPanelSessionCommand.CANCEL)
            MeetingPanelStatus.Phase.DOWNLOADING -> actions.sessionCommand(MeetingPanelSessionCommand.CANCEL_DOWNLOAD)
            else -> Unit
        }
    }

    private fun retrySave() {
        if (!disposed && status.saveError != null) actions.retrySave()
    }

    private fun dispatchSession(command: MeetingPanelSessionCommand) {
        if (disposed) return
        if (command == MeetingPanelSessionCommand.PAUSE ||
            command == MeetingPanelSessionCommand.RESUME ||
            command == MeetingPanelSessionCommand.FINISH ||
            command == MeetingPanelSessionCommand.CANCEL
        ) flushFocusedEdit()
        if (!disposed) actions.sessionCommand(command)
    }

    private fun showChoices(
        title: String,
        choices: List<MeetingPanelChoice>,
        onChoice: (String) -> Unit,
    ) {
        if (disposed) return
        val token = document?.let { SessionToken(it.sessionId, it.runId) }
        val allowedIds = choices.mapTo(mutableSetOf()) { it.id }
        dialogHost.showChoices(MeetingPanelChoicesRequest(title, choices)) { selected ->
            val selectedId = selected ?: return@showChoices
            if (!disposed && isCurrentSession(token) && selectedId in allowedIds) onChoice(selectedId)
        }
    }

    private fun showTextInput(title: String, initialText: String, onSubmit: (String?) -> Unit) {
        if (disposed) return
        val token = document?.let { SessionToken(it.sessionId, it.runId) }
        dialogHost.showTextInput(MeetingPanelTextInputRequest(title, initialText)) { value ->
            if (!disposed && isCurrentSession(token)) onSubmit(value)
        }
    }

    private fun isCurrentSession(token: SessionToken?): Boolean =
        when (token) {
            null -> document == null
            else -> document?.let { it.sessionId == token.sessionId && it.runId == token.runId } == true
        }

    private fun participantChoices(
        participants: List<MeetingParticipant>,
        includeUnknown: Boolean = false,
    ): List<MeetingPanelChoice> = buildList {
        participants.take(8).forEach { profile ->
            val ignoredSuffix = if (profile.ignored) " · Ignoré" else ""
            add(MeetingPanelChoice(profile.id, profileDisplayName(profile) + ignoredSuffix))
        }
        if (includeUnknown) add(MeetingPanelChoice(UNKNOWN_PARTICIPANT_ID, "Inconnu"))
    }

    private fun profileDisplayName(profile: MeetingParticipant): String {
        val name = profile.name?.takeIf(String::isNotBlank)
        val ordinal = "Personne ${profile.ordinal}"
        return if (name == null) ordinal else "$name · $ordinal"
    }

    private companion object {
        const val UNKNOWN_PARTICIPANT_ID = "participant:unknown"
        const val PROFILE_RENAME = "profile:rename"
        const val PROFILE_TOGGLE_IGNORED = "profile:toggle-ignored"
        const val ACTION_REVIEW = "action:review"
        const val ACTION_RETRY_SAVE = "action:retry-save"
        const val ACTION_START = "action:start"
        const val ACTION_PAUSE = "action:pause"
        const val ACTION_RESUME = "action:resume"
        const val ACTION_FINISH = "action:finish"
        const val ACTION_CANCEL = "action:cancel"
        const val ACTION_CANCEL_DOWNLOAD = "action:cancel-download"
        const val ACTION_DOWNLOAD = "action:download"
        const val ACTION_DOCUMENT_PHOTO = "action:document:photo"
        const val ACTION_DOCUMENT_SCREENSHOT = "action:document:screenshot"
        const val ACTION_DOCUMENT_COPY = "action:document:copy"
        const val ACTION_DOCUMENT_EXPORT = "action:document:export"
    }
}
