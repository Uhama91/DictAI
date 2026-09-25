package com.kafkasl.phonewhisper.meeting

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private enum class MeetingControllerTermination {
    NONE,
    FINISH,
    FAILURE,
    CANCEL,
    DESTROY,
}

private data class MeetingReservationOutcome(
    val lease: MeetingNativeRuntimeLease?,
    val failure: Throwable?,
)

/** One controller owns exactly one meeting document and, at most, one recording run. */
internal class MeetingRecordingController private constructor(
    private val editor: MeetingDocumentEditor,
    private val draftClaim: MeetingDraftOwnershipClaim,
    private val ports: MeetingRecordingPorts,
    private val runId: String,
    private val recordable: Boolean,
    private val onStateChanged: (MeetingRecordingState) -> Unit,
) {
    private val lifecycleLock = Any()
    private val nativeCommand = AtomicInteger(NATIVE_COMMAND_NONE)
    private val nativeResourceClosed = AtomicBoolean(false)
    private val nativeClosureFinalized = AtomicBoolean(false)
    private val nativeLeasePoisoned = AtomicBoolean(false)
    private val destroyFlowStarted = AtomicBoolean(false)
    private val destroyFinalized = AtomicBoolean(false)

    @Volatile
    private var destroyed = false

    @Volatile
    private var termination = MeetingControllerTermination.NONE

    private var currentState = MeetingRecordingState(
        document = editor.snapshot(),
        phase = MeetingRecordingPhase.DOCUMENT,
        saveError = draftClaim.saveError,
    )

    private var startLanguage: String? = null
    private var startFuture: CompletableFuture<Unit>? = null
    private var reservationRequest: MeetingNativeReservationRequest? = null
    private var reservationOutcome: MeetingReservationOutcome? = null
    private var reservationOutcomeHandled = false
    private var reservationSettled = CompletableFuture.completedFuture(Unit)
    private var lease: MeetingNativeRuntimeLease? = null
    private var nativeSession: MeetingSession? = null
    private var nativeClosed: CompletableFuture<Unit>? = null
    private var nativeCloseObserved = false
    private var nativeCloseFailure: Throwable? = null
    private var microphone: MeetingMicrophonePort? = null
    private var microphoneStartInProgress = false
    private var microphoneStartSettled: CompletableFuture<Unit>? = null
    private var microphoneStopFuture: CompletableFuture<Unit>? = null
    private var microphoneStopResolved = true
    private var microphoneStopFailed = false

    private var pauseFuture: CompletableFuture<Unit>? = null
    private var finishFuture: CompletableFuture<Unit>? = null
    private var cancelFuture: CompletableFuture<Unit>? = null
    private var destroyFuture: CompletableFuture<Unit>? = null
    private var finalizationStarted = false
    private var finalizationNeedsNotePublication = false
    private var finalizationDurable = false
    private var currentAudioFailure: String? = null

    val state: MeetingRecordingState
        get() = currentState

    companion object {
        private const val NATIVE_COMMAND_NONE = 0
        private const val NATIVE_COMMAND_FINISH = 1
        private const val NATIVE_COMMAND_CANCEL = 2
        private const val START_ERROR = "Impossible de démarrer la réunion."
        private const val MODEL_UNAVAILABLE_ERROR = "Modèle de réunion indisponible."
        private const val TRANSITION_ERROR = "Cette action n’est pas disponible maintenant."
        private const val LEASE_ERROR = "Le moteur de réunion n’est plus disponible."
        private const val MICROPHONE_ERROR = "Le microphone n’a pas pu enregistrer."
        private const val BUFFER_ERROR = "La file audio est saturée ; la réunion s’arrête."
        private const val INFERENCE_ERROR = "La réunion n’a pas pu être traitée."
        private const val CLOSE_ERROR = "La session de réunion n’a pas pu être libérée."
        private const val SAVE_ERROR = "Sauvegarde impossible"
        private const val NOTE_ERROR = "La note de réunion n’a pas pu être enregistrée."
        private const val OWNERSHIP_ERROR = "Impossible de transférer le brouillon de réunion."

        fun createNew(
            sessionId: String,
            runId: String,
            draftClaim: MeetingDraftOwnershipClaim,
            ports: MeetingRecordingPorts,
            onStateChanged: (MeetingRecordingState) -> Unit = {},
        ): MeetingRecordingController {
            require(sessionId.isNotBlank()) { "sessionId must not be blank" }
            require(runId.isNotBlank()) { "runId must not be blank" }
            val editor = MeetingDocumentEditor.create(sessionId, runId)
            return MeetingRecordingController(
                editor = editor,
                draftClaim = draftClaim,
                ports = ports,
                runId = runId,
                recordable = true,
                onStateChanged = onStateChanged,
            )
        }

        fun restored(
            draftClaim: MeetingDraftOwnershipClaim,
            ports: MeetingRecordingPorts,
            onStateChanged: (MeetingRecordingState) -> Unit = {},
        ): MeetingRecordingController {
            val document = draftClaim.snapshot
                ?: (draftClaim.recoveredDocument as? MeetingDocumentRead.Ready)?.document
                ?: throw IllegalArgumentException("A restored recording controller requires a ready meeting draft")
            return restored(
                document = document,
                draftClaim = draftClaim,
                ports = ports,
                onStateChanged = onStateChanged,
            )
        }

        fun restored(
            document: MeetingDocument,
            draftClaim: MeetingDraftOwnershipClaim,
            ports: MeetingRecordingPorts,
            onStateChanged: (MeetingRecordingState) -> Unit = {},
        ): MeetingRecordingController {
            return restoredFromDraft(
                document = document,
                draftClaim = draftClaim,
                ports = ports,
                onStateChanged = onStateChanged,
            )
        }

        private fun restoredFromDraft(
            document: MeetingDocument,
            draftClaim: MeetingDraftOwnershipClaim,
            ports: MeetingRecordingPorts,
            onStateChanged: (MeetingRecordingState) -> Unit,
        ): MeetingRecordingController {
            return MeetingRecordingController(
                editor = MeetingDocumentEditor.restore(document),
                draftClaim = draftClaim,
                ports = ports,
                runId = document.runId,
                recordable = false,
                onStateChanged = onStateChanged,
            )
        }
    }

    init {
        draftClaim.errorRelay.attach { message ->
            if (destroyed) return@attach
            postMain {
                if (!destroyed) publishState(saveError = message)
            }
        }
    }

    fun start(language: String): CompletableFuture<Unit> {
        if (destroyed || !recordable || currentState.document.finished || termination != MeetingControllerTermination.NONE) {
            return failedFuture(START_ERROR)
        }
        startFuture?.let { return it }
        if (currentState.phase != MeetingRecordingPhase.DOCUMENT &&
            currentState.phase != MeetingRecordingPhase.MODEL_UNAVAILABLE &&
            currentState.phase != MeetingRecordingPhase.ERROR
        ) {
            return failedFuture(START_ERROR)
        }
        if (ports.modelAvailability.currentAvailability() != MeetingModelAvailability.READY) {
            publishState(phase = MeetingRecordingPhase.MODEL_UNAVAILABLE)
            return failedFuture(MODEL_UNAVAILABLE_ERROR)
        }

        val completion = CompletableFuture<Unit>()
        startFuture = completion
        startLanguage = language
        publishState(phase = MeetingRecordingPhase.PREPARING, recordingError = null)

        val request = try {
            ports.reservation.request(runId)
        } catch (_: Throwable) {
            failPreparation(START_ERROR)
            return completion
        }
        synchronized(lifecycleLock) {
            reservationRequest = request
            reservationOutcome = null
            reservationOutcomeHandled = false
            reservationSettled = CompletableFuture()
        }
        request.lease.whenComplete { grantedLease, failure ->
            val outcome = MeetingReservationOutcome(grantedLease, failure)
            val cleanupNow = synchronized(lifecycleLock) {
                if (reservationOutcome != null) {
                    false
                } else {
                    reservationOutcome = outcome
                    destroyed || termination != MeetingControllerTermination.NONE
                }
            }
            if (cleanupNow) {
                consumeReservationForTermination()
            } else {
                postMain { handleReservationOnMain(request) }
            }
        }
        return completion
    }

    fun pause(): CompletableFuture<Unit> {
        if (destroyed || termination != MeetingControllerTermination.NONE) return failedFuture(TRANSITION_ERROR)
        pauseFuture?.takeIf { currentState.phase == MeetingRecordingPhase.PAUSING }?.let { return it }
        if (currentState.phase != MeetingRecordingPhase.LISTENING) return failedFuture(TRANSITION_ERROR)

        val completion = CompletableFuture<Unit>()
        pauseFuture = completion
        publishState(phase = MeetingRecordingPhase.PAUSING)
        val activeSession = nativeSession
        val stopped = stopCurrentMicrophone()
        stopped.whenComplete { _, failure ->
            postMain { continuePause(activeSession, completion, failure) }
        }
        return completion
    }

    fun resume(): CompletableFuture<Unit> {
        if (destroyed || termination != MeetingControllerTermination.NONE || currentState.phase != MeetingRecordingPhase.PAUSED) {
            return failedFuture(TRANSITION_ERROR)
        }
        val currentLease = lease
        if (currentLease == null || !leaseUsable(currentLease)) {
            setRecordingError(LEASE_ERROR)
            return failedFuture(LEASE_ERROR)
        }
        val activeSession = nativeSession ?: return failedFuture(TRANSITION_ERROR)
        val completion = CompletableFuture<Unit>()
        startMicrophone(activeSession, completion, MeetingRecordingPhase.LISTENING)
        return completion
    }

    fun finish(): CompletableFuture<Unit> {
        finishFuture?.let { return it }
        if (destroyed || termination == MeetingControllerTermination.CANCEL || termination == MeetingControllerTermination.DESTROY) {
            return failedFuture(TRANSITION_ERROR)
        }
        if (currentState.phase == MeetingRecordingPhase.FINISHED) {
            return CompletableFuture.completedFuture(Unit)
        }

        val completion = CompletableFuture<Unit>()
        finishFuture = completion
        termination = MeetingControllerTermination.FINISH
        failPendingTransitionFutures(TRANSITION_ERROR)
        finalizationNeedsNotePublication = true
        ports.focusedEdit.flushFocusedEdit()
        publishState(phase = MeetingRecordingPhase.FINALIZING)

        val pendingRequest = reservationRequest
        if (pendingRequest != null && !reservationSettled.isDone) {
            pendingRequest.cancel()
            consumeReservationForTermination()
            reservationSettled.whenComplete { _, _ ->
                if (nativeSession == null) postMain { finalizeWithoutNativeSession() }
            }
            return completion
        }

        val activeSession = nativeSession
        if (activeSession == null) {
            finalizeWithoutNativeSession()
        } else {
            stopCurrentMicrophone().whenComplete { _, failure ->
                postMain { continueFinishAfterRecorder(activeSession, failure) }
            }
        }
        return completion
    }

    fun cancel(): CompletableFuture<Unit> {
        cancelFuture?.let { return it }
        if (destroyed) return destroyFuture ?: failedFuture(TRANSITION_ERROR)
        if (currentState.phase == MeetingRecordingPhase.FINISHED) return CompletableFuture.completedFuture(Unit)

        val completion = CompletableFuture<Unit>()
        cancelFuture = completion
        termination = MeetingControllerTermination.CANCEL
        failPendingTransitionFutures(TRANSITION_ERROR)
        val pendingRequest = reservationRequest
        if (pendingRequest != null && !reservationSettled.isDone) {
            pendingRequest.cancel()
            consumeReservationForTermination()
            reservationSettled.whenComplete { _, _ ->
                if (nativeSession == null && !destroyed) postMain { finalizeCancelledWithoutNativeSession() }
            }
            return completion
        }

        publishState(phase = MeetingRecordingPhase.CLOSING)
        val activeSession = nativeSession
        if (activeSession == null) {
            finalizeCancelledWithoutNativeSession()
        } else {
            stopCurrentMicrophone().whenComplete { _, _ -> requestNativeCancel(activeSession) }
        }
        return completion
    }

    fun destroy(): CompletableFuture<Unit> {
        destroyFuture?.let { return it }
        val completion = CompletableFuture<Unit>()
        destroyFuture = completion
        destroyed = true
        failPendingTransitionFutures(TRANSITION_ERROR)
        if (termination != MeetingControllerTermination.FINISH || nativeCommand.get() == NATIVE_COMMAND_NONE) {
            termination = MeetingControllerTermination.DESTROY
        }
        draftClaim.errorRelay.detach()

        val pendingRequest = reservationRequest
        if (pendingRequest != null && !reservationSettled.isDone) {
            pendingRequest.cancel()
            consumeReservationForTermination()
            reservationSettled.whenComplete { _, _ -> continueDestroyAfterReservation() }
        } else {
            continueDestroyAfterReservation()
        }
        return completion
    }

    fun editTurn(turnId: String, text: String): Unit {
        if (destroyed) return
        editor.edit(turnId, text)
        publishState(persistDraft = true)
    }

    fun assignTurn(turnId: String, participantId: String?): Unit {
        if (destroyed) return
        ports.focusedEdit.flushFocusedEdit()
        editor.assign(turnId, participantId)
        publishState(persistDraft = true)
    }

    fun renameParticipant(participantId: String, name: String): Unit {
        if (destroyed) return
        ports.focusedEdit.flushFocusedEdit()
        editor.rename(participantId, name)
        publishState(persistDraft = true)
    }

    fun setParticipantIgnored(participantId: String, ignored: Boolean): Unit {
        if (destroyed) return
        ports.focusedEdit.flushFocusedEdit()
        editor.setIgnored(participantId, ignored)
        publishState(persistDraft = true)
    }

    fun ensureDocumentTurn(): MeetingTurn {
        check(!destroyed) { TRANSITION_ERROR }
        ports.focusedEdit.flushFocusedEdit()
        val turn = editor.ensureDocumentTurn()
        publishState(persistDraft = true)
        return turn
    }

    fun flushDraft(): CompletableFuture<Unit> {
        if (destroyed) return failedFuture(TRANSITION_ERROR)
        ports.focusedEdit.flushFocusedEdit()
        publishState(persistDraft = true)
        return flushCurrentDraft()
    }

    fun retrySave(): CompletableFuture<Unit> {
        if (destroyed) return failedFuture(TRANSITION_ERROR)
        val completion = CompletableFuture<Unit>()
        flushCurrentDraft().whenComplete { _, failure ->
            postMain {
                if (destroyed) {
                    completion.completeExceptionally(IllegalStateException(TRANSITION_ERROR))
                } else if (failure != null) {
                    publishState(saveError = SAVE_ERROR)
                    completion.completeExceptionally(IllegalStateException(SAVE_ERROR))
                } else if (finalizationNeedsNotePublication) {
                    publishFinalNote(completion)
                } else {
                    publishState(saveError = null)
                    completion.complete(Unit)
                }
            }
        }
        return completion
    }

    private fun handleReservationOnMain(request: MeetingNativeReservationRequest) {
        if (request !== reservationRequest) return
        val outcome = synchronized(lifecycleLock) {
            if (reservationOutcomeHandled) null else reservationOutcome.also { reservationOutcomeHandled = it != null }
        } ?: return

        if (destroyed || termination != MeetingControllerTermination.NONE) {
            releaseLateReservation(outcome)
            return
        }
        if (outcome.failure != null || outcome.lease == null) {
            reservationSettled.complete(Unit)
            failPreparation(START_ERROR)
            return
        }
        val granted = outcome.lease
        if (!leaseUsable(granted)) {
            val released = releaseUnopenedLease(granted)
            reservationSettled.complete(Unit)
            if (released) failPreparation(LEASE_ERROR) else failPreparation(CLOSE_ERROR, retryable = false)
            return
        }
        lease = granted

        val activeSession = try {
            ports.sessionFactory.start(
                runId = runId,
                language = requireNotNull(startLanguage),
                onReady = { postMain { onNativeReady() } },
                onUpdate = { hypothesis -> postMain { onNativeUpdate(hypothesis) } },
                onFailure = { postMain { onNativeFailure() } },
            )
        } catch (_: Throwable) {
            lease = null
            val released = releaseUnopenedLease(granted)
            reservationSettled.complete(Unit)
            failPreparation(if (released) START_ERROR else CLOSE_ERROR, retryable = released)
            return
        }
        nativeSession = activeSession
        registerNativeClose(activeSession, granted)
        reservationSettled.complete(Unit)
    }

    private fun consumeReservationForTermination() {
        val outcome = synchronized(lifecycleLock) {
            if (reservationOutcomeHandled) null else reservationOutcome?.also { reservationOutcomeHandled = true }
        } ?: return
        releaseLateReservation(outcome)
    }

    private fun releaseLateReservation(outcome: MeetingReservationOutcome) {
        val granted = outcome.lease
        val releaseFailure = if (granted == null) null else if (releaseUnopenedLease(granted)) null else CLOSE_ERROR
        reservationSettled.complete(Unit)
        val completion = startFuture
        if (completion != null && !completion.isDone) completion.completeExceptionally(IllegalStateException(START_ERROR))
        if (destroyed) {
            continueDestroyAfterReservation()
        } else if (termination == MeetingControllerTermination.FINISH) {
            postMain {
                if (releaseFailure != null) setRecordingError(releaseFailure)
                finalizeWithoutNativeSession()
            }
        } else if (termination == MeetingControllerTermination.CANCEL) {
            postMain {
                if (releaseFailure != null) setRecordingError(releaseFailure)
                finalizeCancelledWithoutNativeSession()
            }
        }
    }

    private fun onNativeReady() {
        if (destroyed || termination != MeetingControllerTermination.NONE) {
            nativeSession?.let(::requestNativeCancel)
            return
        }
        val activeSession = nativeSession ?: return
        val currentLease = lease
        if (currentLease == null || !leaseUsable(currentLease)) {
            setRecordingError(LEASE_ERROR)
            startFuture?.completeExceptionally(IllegalStateException(LEASE_ERROR))
            termination = MeetingControllerTermination.FAILURE
            failPendingTransitionFutures(LEASE_ERROR)
            requestNativeCancel(activeSession)
            return
        }
        startMicrophone(activeSession, startFuture ?: CompletableFuture(), MeetingRecordingPhase.LISTENING)
    }

    private fun startMicrophone(
        activeSession: MeetingSession,
        completion: CompletableFuture<Unit>,
        nextPhase: MeetingRecordingPhase,
    ) {
        val currentLease = lease
        if (currentLease == null || !leaseUsable(currentLease)) {
            setRecordingError(LEASE_ERROR)
            completion.completeExceptionally(IllegalStateException(LEASE_ERROR))
            termination = MeetingControllerTermination.FAILURE
            failPendingTransitionFutures(LEASE_ERROR)
            requestNativeCancel(activeSession)
            return
        }

        val startupGate = synchronized(lifecycleLock) {
            if (nativeCloseObserved || activeSession.closed.isDone || destroyed ||
                termination != MeetingControllerTermination.NONE
            ) {
                null
            } else {
                microphoneStopResolved = false
                microphoneStopFailed = false
                microphoneStartInProgress = true
                CompletableFuture<Unit>().also { microphoneStartSettled = it }
            }
        }
        if (startupGate == null) {
            completion.completeExceptionally(IllegalStateException(TRANSITION_ERROR))
            return
        }

        val recorder = try {
            ports.microphoneFactory.create()
        } catch (_: Throwable) {
            val settlement = synchronized(lifecycleLock) {
                microphoneStartInProgress = false
                microphoneStartSettled = null
                microphoneStopResolved = true
                microphoneStopFailed = false
                nativeCloseObserved || activeSession.closed.isDone || destroyed ||
                    termination != MeetingControllerTermination.NONE
            }
            startupGate.complete(Unit)
            val resources = synchronized(lifecycleLock) { nativeSession to lease }
            if (resources.first != null && resources.second != null) {
                completeNativeCloseIfReady(resources.first!!, resources.second!!)
            }
            if (settlement) completion.completeExceptionally(IllegalStateException(TRANSITION_ERROR))
            else failMicrophoneStart(activeSession, completion)
            return
        }

        val shouldStart = synchronized(lifecycleLock) {
            microphone = recorder
            microphoneStopFuture = null
            !nativeCloseObserved && !activeSession.closed.isDone && !destroyed &&
                termination == MeetingControllerTermination.NONE
        }
        val started = if (shouldStart) {
            try {
                recorder.start(
                    onPcm16 = { buffer, length ->
                        val accepted = try {
                            activeSession.acceptPcm16(buffer, length)
                        } catch (_: Throwable) {
                            false
                        }
                        if (!accepted) postMain { onAudioCaptureFailure(activeSession, BUFFER_ERROR) }
                        accepted
                    },
                    onFailure = { postMain { onAudioCaptureFailure(activeSession, MICROPHONE_ERROR) } },
                )
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }

        val settled = synchronized(lifecycleLock) {
            microphoneStartInProgress = false
            val gate = microphoneStartSettled
            microphoneStartSettled = null
            val canContinue = !nativeCloseObserved && !activeSession.closed.isDone && !destroyed &&
                termination == MeetingControllerTermination.NONE
            gate to canContinue
        }
        settled.first?.complete(Unit)
        if (!settled.second) {
            completion.completeExceptionally(IllegalStateException(TRANSITION_ERROR))
            stopCurrentMicrophone()
            return
        }
        if (!started) {
            failMicrophoneStart(activeSession, completion)
            return
        }
        currentAudioFailure = null
        publishState(phase = nextPhase, captureActive = true, recordingError = null)
        completion.complete(Unit)
    }

    private fun failMicrophoneStart(activeSession: MeetingSession, completion: CompletableFuture<Unit>) {
        setRecordingError(MICROPHONE_ERROR)
        completion.completeExceptionally(IllegalStateException(MICROPHONE_ERROR))
        termination = MeetingControllerTermination.FINISH
        finishFuture = finishFuture ?: CompletableFuture()
        finalizationNeedsNotePublication = true
        publishState(phase = MeetingRecordingPhase.FINALIZING, recordingError = MICROPHONE_ERROR)
        stopCurrentMicrophone().whenComplete { _, failure ->
            postMain { continueFinishAfterRecorder(activeSession, failure) }
        }
    }

    private fun onAudioCaptureFailure(activeSession: MeetingSession, message: String) {
        if (destroyed || activeSession !== nativeSession || termination != MeetingControllerTermination.NONE) return
        currentAudioFailure = message
        publishState(recordingError = message)
        termination = MeetingControllerTermination.FINISH
        failPendingTransitionFutures(message)
        finishFuture = finishFuture ?: CompletableFuture()
        finalizationNeedsNotePublication = true
        publishState(phase = MeetingRecordingPhase.FINALIZING, recordingError = message)
        stopCurrentMicrophone().whenComplete { _, failure ->
            postMain { continueFinishAfterRecorder(activeSession, failure) }
        }
    }

    private fun onNativeUpdate(hypothesis: MeetingHypothesis) {
        if (destroyed || termination == MeetingControllerTermination.CANCEL ||
            termination == MeetingControllerTermination.DESTROY || editor.snapshot().finished
        ) return
        ports.focusedEdit.flushFocusedEdit()
        editor.apply(hypothesis)
        publishState(persistDraft = true)
    }

    private fun onNativeFailure() {
        if (destroyed || termination == MeetingControllerTermination.CANCEL || termination == MeetingControllerTermination.DESTROY) return
        currentAudioFailure = INFERENCE_ERROR
        termination = MeetingControllerTermination.FAILURE
        failPendingTransitionFutures(INFERENCE_ERROR)
        finishFuture = finishFuture ?: CompletableFuture()
        finalizationNeedsNotePublication = true
        publishState(phase = MeetingRecordingPhase.FINALIZING, recordingError = INFERENCE_ERROR)
        val activeSession = nativeSession ?: return
        stopCurrentMicrophone().whenComplete { _, failure ->
            if (failure == null) {
                postMain {
                    if (!destroyed && activeSession === nativeSession && !microphoneStopFailed) {
                        markMicrophoneStopped()
                    }
                }
            } else {
                postMain {
                    if (!destroyed && activeSession === nativeSession) setRecordingError(MICROPHONE_ERROR)
                }
            }
        }
    }

    private fun failPendingTransitionFutures(message: String): Unit {
        val failure = IllegalStateException(message)
        startFuture?.let { if (!it.isDone) it.completeExceptionally(failure) }
        pauseFuture?.let { if (!it.isDone) it.completeExceptionally(failure) }
    }

    private fun continuePause(
        activeSession: MeetingSession?,
        completion: CompletableFuture<Unit>,
        stopFailure: Throwable?,
    ) {
        if (destroyed || termination != MeetingControllerTermination.NONE) {
            if (!completion.isDone) completion.completeExceptionally(IllegalStateException(TRANSITION_ERROR))
            return
        }
        if (stopFailure != null) {
            setRecordingError(MICROPHONE_ERROR)
            completion.completeExceptionally(IllegalStateException(MICROPHONE_ERROR))
            currentAudioFailure = MICROPHONE_ERROR
            if (activeSession != null) beginFinishAfterCaptureFailure(activeSession, MICROPHONE_ERROR, stopFailure)
            return
        }
        markMicrophoneStopped()
        val session = activeSession ?: run {
            completion.completeExceptionally(IllegalStateException(TRANSITION_ERROR))
            return
        }
        val checkpoint = try {
            session.checkpoint()
        } catch (_: Throwable) {
            failedFuture<Unit>(INFERENCE_ERROR)
        }
        checkpoint.whenComplete { _, failure ->
            postMain {
                if (destroyed || termination != MeetingControllerTermination.NONE) return@postMain
                if (failure != null) {
                    setRecordingError(INFERENCE_ERROR)
                    completion.completeExceptionally(IllegalStateException(INFERENCE_ERROR))
                    beginFinishAfterCaptureFailure(session, INFERENCE_ERROR)
                } else {
                    flushCurrentDraft().whenComplete { _, flushFailure ->
                        postMain {
                            if (destroyed || termination != MeetingControllerTermination.NONE) return@postMain
                            if (flushFailure != null) {
                                publishState(phase = MeetingRecordingPhase.PAUSED, saveError = SAVE_ERROR)
                                completion.completeExceptionally(IllegalStateException(SAVE_ERROR))
                            } else {
                                publishState(phase = MeetingRecordingPhase.PAUSED, captureActive = false, saveError = null)
                                completion.complete(Unit)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun beginFinishAfterCaptureFailure(
        activeSession: MeetingSession,
        message: String,
        stopFailure: Throwable? = null,
    ): Unit {
        if (termination != MeetingControllerTermination.NONE) return
        termination = MeetingControllerTermination.FAILURE
        failPendingTransitionFutures(message)
        finishFuture = finishFuture ?: CompletableFuture()
        finalizationNeedsNotePublication = true
        publishState(phase = MeetingRecordingPhase.FINALIZING, recordingError = message)
        continueFinishAfterRecorder(activeSession, stopFailure)
    }

    private fun continueFinishAfterRecorder(activeSession: MeetingSession, stopFailure: Throwable?) {
        if (destroyed || termination == MeetingControllerTermination.CANCEL || termination == MeetingControllerTermination.DESTROY) return
        if (stopFailure == null) {
            markMicrophoneStopped()
        } else {
            currentAudioFailure = MICROPHONE_ERROR
            setRecordingError(MICROPHONE_ERROR)
        }
        if (nativeCommand.compareAndSet(NATIVE_COMMAND_NONE, NATIVE_COMMAND_FINISH)) {
            try {
                activeSession.finish()
            } catch (_: Throwable) {
                setRecordingError(INFERENCE_ERROR)
                currentAudioFailure = INFERENCE_ERROR
                requestNativeCancelAfterFinishFailure(activeSession)
            }
        }
    }

    private fun registerNativeClose(activeSession: MeetingSession, activeLease: MeetingNativeRuntimeLease) {
        val closedSignal = CompletableFuture<Unit>()
        nativeClosed = closedSignal
        activeSession.closed.whenComplete { _, nativeFailure ->
            synchronized(lifecycleLock) {
                nativeCloseObserved = true
                nativeCloseFailure = nativeFailure
            }
            if (nativeFailure != null) poisonRuntimeLease(activeLease)

            // A native close can arrive without a UI command. Do not drop a live reader
            // or release the process-wide lease until its asynchronous stop is confirmed.
            stopCurrentMicrophone()
            completeNativeCloseIfReady(activeSession, activeLease)
        }
    }

    private fun completeNativeCloseIfReady(
        activeSession: MeetingSession,
        activeLease: MeetingNativeRuntimeLease,
    ) {
        val closeOutcome = synchronized(lifecycleLock) {
            if (!nativeCloseObserved || !microphoneStopResolved ||
                !nativeClosureFinalized.compareAndSet(false, true)
            ) {
                null
            } else {
                nativeCloseFailure to microphoneStopFailed
            }
        } ?: return

        val resourceFailure = closeRuntimeLease(activeLease, closeOutcome.first, closeOutcome.second)
        val closedSignal = nativeClosed
        if (resourceFailure == null) {
            closedSignal?.complete(Unit)
        } else {
            closedSignal?.completeExceptionally(IllegalStateException(resourceFailure))
        }
        postMain { onNativeClosedOnMain(activeSession, resourceFailure) }
    }

    private fun closeRuntimeLease(
        activeLease: MeetingNativeRuntimeLease,
        nativeFailure: Throwable?,
        microphoneFailure: Boolean,
    ): String? {
        if (!nativeResourceClosed.compareAndSet(false, true)) return null
        if (nativeFailure != null || microphoneFailure) {
            poisonRuntimeLease(activeLease)
            return CLOSE_ERROR
        }
        return try {
            activeLease.release()
            synchronized(lifecycleLock) { if (lease === activeLease) lease = null }
            null
        } catch (_: Throwable) {
            poisonRuntimeLease(activeLease)
            CLOSE_ERROR
        }
    }

    private fun poisonRuntimeLease(activeLease: MeetingNativeRuntimeLease) {
        if (!nativeLeasePoisoned.compareAndSet(false, true)) return
        try {
            activeLease.poison()
        } catch (_: Throwable) {
            // An uncertain resource remains retained even if the host cannot report poison.
        }
    }

    private fun onNativeClosedOnMain(activeSession: MeetingSession, closeError: String?) {
        if (activeSession !== nativeSession || destroyed) return
        if (currentState.captureActive && microphoneReleaseConfirmed()) markMicrophoneStopped()
        val currentTerminal = termination
        when (currentTerminal) {
            MeetingControllerTermination.CANCEL -> finalizeCancelledAfterClose(closeError)
            MeetingControllerTermination.DESTROY -> continueDestroyAfterReservation()
            MeetingControllerTermination.FINISH,
            MeetingControllerTermination.FAILURE,
            MeetingControllerTermination.NONE -> {
                if (currentTerminal == MeetingControllerTermination.NONE) {
                    termination = MeetingControllerTermination.FAILURE
                    failPendingTransitionFutures(closeError ?: INFERENCE_ERROR)
                    finishFuture = finishFuture ?: CompletableFuture()
                    finalizationNeedsNotePublication = true
                }
                if (closeError != null) currentAudioFailure = closeError
                finalizeDocumentAfterClose(closeError)
            }
        }
    }

    private fun finalizeWithoutNativeSession() {
        if (destroyed || finalizationStarted) return
        finalizationStarted = true
        editor.finish()
        publishState(phase = MeetingRecordingPhase.FINALIZING, captureActive = false, persistDraft = true)
        flushCurrentDraft().whenComplete { _, failure ->
            postMain {
                if (destroyed) return@postMain
                if (failure != null) failFinalDraft()
                else {
                    finalizationDurable = true
                    publishFinalNote(finishFuture ?: CompletableFuture())
                }
            }
        }
    }

    private fun finalizeCancelledWithoutNativeSession() {
        if (destroyed || finalizationStarted) return
        finalizationStarted = true
        editor.finish()
        publishState(phase = MeetingRecordingPhase.FINALIZING, captureActive = false, persistDraft = true)
        flushCurrentDraft().whenComplete { _, failure ->
            postMain {
                if (destroyed) return@postMain
                if (failure != null) {
                    publishState(phase = MeetingRecordingPhase.FINISHED, saveError = SAVE_ERROR)
                    cancelFuture?.completeExceptionally(IllegalStateException(SAVE_ERROR))
                } else {
                    publishState(phase = MeetingRecordingPhase.FINISHED, saveError = null)
                    cancelFuture?.complete(Unit)
                }
            }
        }
    }

    private fun finalizeCancelledAfterClose(closeError: String?) {
        if (finalizationStarted || destroyed) return
        finalizationStarted = true
        editor.finish()
        publishState(
            phase = MeetingRecordingPhase.FINALIZING,
            captureActive = microphoneReleaseConfirmed().not(),
            recordingError = closeError,
            persistDraft = true,
        )
        flushCurrentDraft().whenComplete { _, failure ->
            postMain {
                if (destroyed) return@postMain
                if (failure != null) {
                    publishState(phase = MeetingRecordingPhase.FINISHED, saveError = SAVE_ERROR)
                    cancelFuture?.completeExceptionally(IllegalStateException(SAVE_ERROR))
                } else {
                    publishState(
                        phase = MeetingRecordingPhase.FINISHED,
                        captureActive = microphoneReleaseConfirmed().not(),
                        recordingError = closeError,
                        saveError = null,
                    )
                    if (closeError == null) cancelFuture?.complete(Unit)
                    else cancelFuture?.completeExceptionally(IllegalStateException(closeError))
                }
            }
        }
    }

    private fun finalizeDocumentAfterClose(closeError: String?) {
        if (finalizationStarted || destroyed) return
        finalizationStarted = true
        editor.finish()
        publishState(
            phase = MeetingRecordingPhase.FINALIZING,
            captureActive = !microphoneReleaseConfirmed(),
            recordingError = closeError ?: currentAudioFailure,
            persistDraft = true,
        )
        flushCurrentDraft().whenComplete { _, failure ->
            postMain {
                if (destroyed) return@postMain
                if (failure != null) {
                    failFinalDraft()
                } else {
                    finalizationDurable = true
                    if (closeError != null) {
                        finalizationNeedsNotePublication = false
                        publishState(
                            phase = MeetingRecordingPhase.FINISHED,
                            captureActive = !microphoneReleaseConfirmed(),
                            saveError = null,
                            recordingError = closeError,
                        )
                        finishFuture?.completeExceptionally(IllegalStateException(closeError))
                    } else {
                        publishFinalNote(finishFuture ?: CompletableFuture())
                    }
                }
            }
        }
    }

    private fun failFinalDraft() {
        publishState(
            phase = MeetingRecordingPhase.FINISHED,
            captureActive = !microphoneReleaseConfirmed(),
            saveError = SAVE_ERROR,
        )
        finishFuture?.completeExceptionally(IllegalStateException(SAVE_ERROR))
    }

    private fun publishFinalNote(completion: CompletableFuture<Unit>) {
        if (destroyed) {
            completion.completeExceptionally(IllegalStateException(TRANSITION_ERROR))
            return
        }
        val document = currentState.document
        val hasSpokenText = document.turns.any { turn ->
            (turn.editedText ?: turn.recognizedText).isNotBlank()
        }
        val hasAttachments = try {
            ports.notePublisher.hasAttachments(document.sessionId)
        } catch (_: Throwable) {
            finalizationNeedsNotePublication = true
            publishState(phase = MeetingRecordingPhase.FINISHED, saveError = NOTE_ERROR)
            completion.completeExceptionally(IllegalStateException(NOTE_ERROR))
            return
        }
        val hasSavedNote = try {
            ports.notePublisher.hasSavedNote(document.sessionId)
        } catch (_: Throwable) {
            finalizationNeedsNotePublication = true
            publishState(phase = MeetingRecordingPhase.FINISHED, saveError = NOTE_ERROR)
            completion.completeExceptionally(IllegalStateException(NOTE_ERROR))
            return
        }
        if (!hasSpokenText && !hasAttachments && !hasSavedNote) {
            finalizationNeedsNotePublication = false
            publishState(phase = MeetingRecordingPhase.FINISHED, saveError = null)
            completion.complete(Unit)
            return
        }
        val publication = try {
            ports.notePublisher.save(document)
        } catch (_: Throwable) {
            failedFuture<Unit>(NOTE_ERROR)
        }
        publication.whenComplete { _, failure ->
            postMain {
                if (destroyed) {
                    completion.completeExceptionally(IllegalStateException(TRANSITION_ERROR))
                } else if (failure != null) {
                    finalizationNeedsNotePublication = true
                    publishState(phase = MeetingRecordingPhase.FINISHED, saveError = NOTE_ERROR)
                    completion.completeExceptionally(IllegalStateException(NOTE_ERROR))
                } else {
                    finalizationNeedsNotePublication = false
                    publishState(phase = MeetingRecordingPhase.FINISHED, saveError = null)
                    completion.complete(Unit)
                }
            }
        }
    }

    private fun continueDestroyAfterReservation() {
        if (!destroyed || !destroyFlowStarted.compareAndSet(false, true)) return
        val activeSession = nativeSession
        if (activeSession == null) {
            val activeLease = lease
            val leaseError = if (activeLease == null) null else if (releaseUnopenedLease(activeLease)) null else CLOSE_ERROR
            finalizeDestroy(leaseError)
            return
        }

        val stopped = stopCurrentMicrophone()
        stopped.whenComplete { _, _ -> requestNativeCancel(activeSession) }
        val closeSignal = nativeClosed ?: activeSession.closed.handle { _, failure ->
            if (failure == null) Unit else throw IllegalStateException(CLOSE_ERROR)
        }
        val stopSettled = stopped.handle { _, _ -> Unit }
        CompletableFuture.allOf(stopSettled, closeSignal.handle { _, failure ->
            if (failure == null) Unit else throw IllegalStateException(CLOSE_ERROR)
        }).whenComplete { _, _ ->
            val failure = if (closeSignal.isCompletedExceptionally) CLOSE_ERROR else null
            finalizeDestroy(failure)
        }
    }

    private fun finalizeDestroy(closeError: String?) {
        if (!destroyFinalized.compareAndSet(false, true)) return
        val completion = destroyFuture ?: return
        val snapshot = try {
            editor.finish()
            editor.snapshot()
        } catch (_: Throwable) {
            currentState.document
        }
        try {
            draftClaim.rememberSnapshot(snapshot)
            draftClaim.writer.updateSnapshot(snapshot)
        } catch (_: Throwable) {
            // relinquish still attempts to flush the writer's last retained snapshot.
        }
        val transfer = try {
            draftClaim.relinquish(snapshot)
        } catch (_: Throwable) {
            failedFuture<Unit>(OWNERSHIP_ERROR)
        }
        transfer.whenComplete { _, failure ->
            when {
                closeError != null -> completion.completeExceptionally(IllegalStateException(closeError))
                failure != null -> completion.completeExceptionally(IllegalStateException(SAVE_ERROR))
                else -> completion.complete(Unit)
            }
        }
    }

    private fun stopCurrentMicrophone(): CompletableFuture<Unit> {
        var startupGate: CompletableFuture<Unit>? = null
        var existingStop: CompletableFuture<Unit>? = null
        var stopTarget: Pair<MeetingMicrophonePort, CompletableFuture<Unit>>? = null
        synchronized(lifecycleLock) {
            when {
                microphoneStartInProgress -> startupGate = requireNotNull(microphoneStartSettled)
                microphoneStopFuture != null -> existingStop = microphoneStopFuture
                microphone != null -> {
                    val completion = CompletableFuture<Unit>()
                    microphoneStopFuture = completion
                    stopTarget = requireNotNull(microphone) to completion
                }
            }
        }
        existingStop?.let { return it }
        startupGate?.let { gate ->
            val deferredStop = CompletableFuture<Unit>()
            gate.whenComplete { _, startupFailure ->
                if (startupFailure != null) {
                    deferredStop.completeExceptionally(IllegalStateException(MICROPHONE_ERROR))
                } else {
                    stopCurrentMicrophone().whenComplete { _, stopFailure ->
                        if (stopFailure == null) deferredStop.complete(Unit)
                        else deferredStop.completeExceptionally(IllegalStateException(MICROPHONE_ERROR))
                    }
                }
            }
            return deferredStop
        }
        val target = stopTarget ?: return CompletableFuture.completedFuture(Unit)
        val recorder = target.first
        val completion = target.second

        val stopped = try {
            recorder.stopAndJoin()
        } catch (_: Throwable) {
            failedFuture<Unit>(MICROPHONE_ERROR)
        }
        stopped.whenComplete { _, failure ->
            val resources = synchronized(lifecycleLock) {
                if (microphone === recorder) {
                    microphoneStopResolved = true
                    microphoneStopFailed = failure != null
                    if (failure == null) {
                        microphone = null
                        microphoneStopFuture = null
                    }
                }
                nativeSession to lease
            }
            if (failure != null) resources.second?.let(::poisonRuntimeLease)
            if (failure == null) completion.complete(Unit)
            else completion.completeExceptionally(IllegalStateException(MICROPHONE_ERROR))
            val activeSession = resources.first
            val activeLease = resources.second
            if (activeSession != null && activeLease != null) {
                completeNativeCloseIfReady(activeSession, activeLease)
            }
        }
        return completion
    }

    private fun markMicrophoneStopped() {
        synchronized(lifecycleLock) {
            if (!microphoneStopResolved || microphoneStopFailed) return
            microphone = null
            microphoneStopFuture = null
        }
        publishState(captureActive = false)
    }

    private fun microphoneReleaseConfirmed(): Boolean = synchronized(lifecycleLock) {
        microphoneStopResolved && !microphoneStopFailed
    }

    private fun requestNativeCancel(activeSession: MeetingSession) {
        if (nativeCommand.compareAndSet(NATIVE_COMMAND_NONE, NATIVE_COMMAND_CANCEL)) {
            try {
                activeSession.cancel()
            } catch (_: Throwable) {
                // closed remains the only authority for releasing the native lease.
            }
        }
    }

    private fun requestNativeCancelAfterFinishFailure(activeSession: MeetingSession) {
        if (nativeCommand.compareAndSet(NATIVE_COMMAND_FINISH, NATIVE_COMMAND_CANCEL)) {
            try {
                activeSession.cancel()
            } catch (_: Throwable) {
                // The closed signal remains the only authority for releasing the lease.
            }
        }
    }

    private fun releaseUnopenedLease(activeLease: MeetingNativeRuntimeLease): Boolean = try {
        activeLease.release()
        true
    } catch (_: Throwable) {
        try {
            activeLease.poison()
        } catch (_: Throwable) {
            // The runtime remains uncertain and must not be admitted again.
        }
        false
    }

    private fun leaseUsable(activeLease: MeetingNativeRuntimeLease): Boolean = try {
        activeLease.isCurrentAndUsable()
    } catch (_: Throwable) {
        false
    }

    private fun failPreparation(message: String, retryable: Boolean = true) {
        if (destroyed) return
        val failedAttempt = startFuture
        startFuture = null
        startLanguage = null
        synchronized(lifecycleLock) {
            reservationRequest = null
            reservationOutcome = null
            reservationOutcomeHandled = false
        }
        if (!retryable) termination = MeetingControllerTermination.FAILURE
        publishState(phase = MeetingRecordingPhase.ERROR, captureActive = false, recordingError = message)
        failedAttempt?.let { if (!it.isDone) it.completeExceptionally(IllegalStateException(message)) }
    }

    private fun flushCurrentDraft(): CompletableFuture<Unit> {
        val future = try {
            draftClaim.writer.flush()
        } catch (_: Throwable) {
            failedFuture<Unit>(SAVE_ERROR)
        }
        val result = CompletableFuture<Unit>()
        future.whenComplete { _, failure ->
            if (failure == null) {
                try {
                    draftClaim.clearSaveError()
                } catch (_: Throwable) {
                    // A successful write remains successful if its former claim has already detached.
                }
                result.complete(Unit)
            } else {
                result.completeExceptionally(IllegalStateException(SAVE_ERROR))
            }
        }
        return result
    }

    private fun publishState(
        phase: MeetingRecordingPhase = currentState.phase,
        captureActive: Boolean = currentState.captureActive,
        recordingError: String? = currentState.recordingError,
        saveError: String? = currentState.saveError,
        persistDraft: Boolean = false,
    ) {
        val document = editor.snapshot()
        var nextSaveError = saveError
        if (persistDraft) {
            try {
                draftClaim.rememberSnapshot(document)
                draftClaim.writer.updateSnapshot(document)
            } catch (_: Throwable) {
                nextSaveError = SAVE_ERROR
            }
        }
        currentState = MeetingRecordingState(
            document = document,
            phase = phase,
            captureActive = captureActive,
            recordingError = recordingError,
            saveError = nextSaveError,
        )
        try {
            onStateChanged(currentState)
        } catch (_: Throwable) {
            // UI observers do not own the recording state machine.
        }
    }

    private fun setRecordingError(message: String) {
        publishState(recordingError = message)
    }

    private fun postMain(task: () -> Unit) {
        try {
            ports.mainDispatcher.post(task)
        } catch (_: Throwable) {
            // Resource finalization is performed independently from this UI queue.
        }
    }

    private fun <T> failedFuture(message: String): CompletableFuture<T> =
        CompletableFuture<T>().also { it.completeExceptionally(IllegalStateException(message)) }

}
