package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeetingRecordingControllerTest {
    @Test
    fun restoredDocumentCanBeEditedButNeverStartsAnotherAsrSession() {
        val fixture = RecordingFixture()
        val document = restoredDocument()
        val controller = fixture.restored(document)

        assertFutureFails(controller.start("fr"))
        assertEquals(0, fixture.reservation.requests.size)
        assertEquals(0, fixture.sessionFactory.startCount)
        assertTrue(fixture.microphoneFactory.recorders.isEmpty())

        controller.editTurn("turn-1", "phrase retouchée")
        assertEquals("phrase retouchée", controller.state.document.turns.single().editedText)
    }

    @Test
    fun restoredLibraryNoteWithoutDraftKeepsEditsAndImageMarkersWithoutStartingAsr() {
        val fixture = RecordingFixture()
        val document = restoredDocument().copy(
            participants = listOf(MeetingParticipant("speaker-1", 1, 1, name = "Alice")),
            turns = listOf(
                restoredDocument().turns.single().copy(
                    editedText = "parole retouchée [[Image 1]]",
                    manualParticipantId = "speaker-1",
                    hasManualAttribution = true,
                ),
            ),
        )
        val controller = fixture.restoredFromNote(document)

        assertEquals(document, controller.state.document)
        assertTrue(controller.state.document.finished)
        assertEquals("Alice", controller.state.document.participants.single().name)
        assertEquals("parole retouchée [[Image 1]]", controller.state.document.turns.single().editedText)
        assertTrue(controller.state.document.turns.single().hasManualAttribution)
        assertFutureFails(controller.start("fr"))
        assertTrue(fixture.reservation.requests.isEmpty())
        assertEquals(0, fixture.sessionFactory.startCount)

        controller.editTurn("turn-1", "texte repris [[Image 1]]")
        val flushed = controller.flushDraft()

        assertTrue(flushed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertEquals("texte repris [[Image 1]]", fixture.savedDrafts.last().turns.single().editedText)
        assertEquals("Alice", fixture.savedDrafts.last().participants.single().name)
        assertEquals(0, fixture.microphoneFactory.recorders.size)
        assertTrue(fixture.publisher.documents.isEmpty())
    }

    @Test
    fun missingOrDownloadingModelsDoNotQueueAnAutomaticStartAfterAvailabilityChanges() {
        listOf(MeetingModelAvailability.MISSING, MeetingModelAvailability.DOWNLOADING).forEach { unavailable ->
            val fixture = RecordingFixture(unavailable)
            val controller = fixture.newDocument()

            assertFutureFails(controller.start("fr"))
            assertEquals(MeetingRecordingPhase.MODEL_UNAVAILABLE, controller.state.phase)
            assertTrue(fixture.reservation.requests.isEmpty())
            assertEquals(0, fixture.sessionFactory.startCount)

            fixture.modelAvailability.value = MeetingModelAvailability.READY
            fixture.dispatcher.runAll()
            assertEquals(0, fixture.sessionFactory.startCount)
            assertTrue(fixture.microphoneFactory.recorders.isEmpty())
        }
    }

    @Test
    fun safePreparationFailureCanBeRetriedWithANewReservation() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        val firstStart = controller.start("fr")
        fixture.reservation.requests.single().fail(IllegalStateException("private reservation failure"))
        fixture.dispatcher.runAll()

        assertFutureFails(firstStart)
        assertEquals(MeetingRecordingPhase.ERROR, controller.state.phase)
        assertEquals(0, fixture.sessionFactory.startCount)
        assertTrue(fixture.microphoneFactory.recorders.isEmpty())

        val retriedStart = controller.start("fr")
        assertFalse(retriedStart === firstStart)
        assertEquals(2, fixture.reservation.requests.size)

        fixture.reservation.requests.last().grant(FakeRuntimeLease())
        fixture.dispatcher.runAll()
        fixture.sessionFactory.fireReady()
        fixture.dispatcher.runAll()

        assertTrue(retriedStart.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertEquals(1, fixture.sessionFactory.startCount)
        assertEquals(1, fixture.microphoneFactory.recorders.single().startCount)
        fixture.sessionFactory.session.closed.complete(Unit)
        fixture.dispatcher.runAll()
    }

    @Test
    fun microphoneStartsOnlyAfterReservationAndNativeReady() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        val started = controller.start("fr")

        assertEquals(MeetingRecordingPhase.PREPARING, controller.state.phase)
        assertEquals(1, fixture.reservation.requests.size)
        assertTrue(fixture.microphoneFactory.recorders.isEmpty())

        val lease = FakeRuntimeLease()
        fixture.reservation.requests.single().grant(lease)
        fixture.dispatcher.runAll()
        assertEquals(1, fixture.sessionFactory.startCount)
        assertTrue(fixture.microphoneFactory.recorders.isEmpty())

        fixture.sessionFactory.fireReady()
        assertTrue(fixture.microphoneFactory.recorders.isEmpty())
        fixture.dispatcher.runAll()

        assertTrue(started.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertEquals(1, fixture.microphoneFactory.recorders.single().startCount)
        assertEquals(MeetingRecordingPhase.LISTENING, controller.state.phase)
        assertEquals(listOf("reserve", "native-open", "microphone-start"), fixture.events)
    }

    @Test
    fun leaseInvalidatedAfterNativeOpenPreventsMicrophoneStart() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        controller.start("fr")
        val lease = FakeRuntimeLease()
        fixture.reservation.requests.single().grant(lease)
        fixture.dispatcher.runAll()
        lease.usable = false

        fixture.sessionFactory.fireReady()
        fixture.sessionFactory.session.closed.complete(Unit)
        fixture.dispatcher.runAll()

        assertTrue(fixture.microphoneFactory.recorders.isEmpty())
        assertEquals(1, fixture.sessionFactory.session.cancelCount)
        assertEquals(1, lease.releaseCount)
    }

    @Test
    fun leaseAlreadyStaleWhenGrantedNeverOpensNativeOrMicrophone() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        controller.start("fr")
        val lease = FakeRuntimeLease().apply { usable = false }

        fixture.reservation.requests.single().grant(lease)
        fixture.dispatcher.runAll()

        assertEquals(0, fixture.sessionFactory.startCount)
        assertTrue(fixture.microphoneFactory.recorders.isEmpty())
        assertEquals(1, lease.releaseCount)
    }

    @Test
    fun resumeRechecksLeaseUsabilityBeforeCreatingANewRecorder() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        fixture.pauseImmediately(controller)
        fixture.reservation.lastLease.usable = false

        val resumed = controller.resume()
        fixture.dispatcher.runAll()
        assertFutureFails(resumed)
        assertEquals(1, fixture.microphoneFactory.recorders.size)
    }

    @Test
    fun leaseInvalidatedBetweenReadyAndMicrophoneStartCancelsNativeExactlyOnce() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        val started = controller.start("fr")
        val lease = FakeRuntimeLease()
        fixture.reservation.requests.single().grant(lease)
        fixture.dispatcher.runAll()
        lease.unusableOnCheck = lease.usabilityChecks + 2

        fixture.sessionFactory.fireReady()
        fixture.dispatcher.runAll()

        assertFutureFails(started)
        assertTrue(fixture.microphoneFactory.recorders.isEmpty())
        assertEquals(1, fixture.sessionFactory.session.cancelCount)
        assertEquals(0, lease.releaseCount)

        fixture.sessionFactory.session.closed.complete(Unit)
        fixture.dispatcher.runAll()

        assertEquals(1, fixture.sessionFactory.session.cancelCount)
        assertEquals(1, lease.releaseCount)
    }

    @Test
    fun unexpectedNativeCloseDuringListeningStopsRecorderBeforeReleasingLease() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        val lease = fixture.reservation.lastLease
        val microphone = fixture.microphoneFactory.recorders.single()
        microphone.stopFuture = CompletableFuture()

        fixture.sessionFactory.session.closed.complete(Unit)
        fixture.dispatcher.runAll()

        assertEquals(1, microphone.stopCount)
        assertEquals(0, lease.releaseCount)
        assertTrue(controller.state.captureActive)
        assertFalse(controller.state.document.finished)

        microphone.stopFuture.complete(Unit)
        fixture.dispatcher.runAll()

        assertEquals(1, lease.releaseCount)
        assertFalse(controller.state.captureActive)
        assertTrue(controller.state.document.finished)
    }

    @Test
    fun nativeCloseDuringMicrophoneFactoryCreationWaitsForCreatedRecorderCleanup() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        val started = controller.start("fr")
        val lease = FakeRuntimeLease()
        fixture.reservation.requests.single().grant(lease)
        fixture.dispatcher.runAll()
        val session = fixture.sessionFactory.session
        fixture.microphoneFactory.beforeReturn = { recorder ->
            recorder.stopFuture = CompletableFuture()
            session.closed.complete(Unit)
        }

        fixture.sessionFactory.fireReady()
        fixture.dispatcher.runAll()

        val microphone = fixture.microphoneFactory.recorders.single()
        assertEquals(0, microphone.startCount)
        assertEquals(1, microphone.stopCount)
        assertEquals(0, lease.releaseCount)
        assertTrue(!started.isDone || started.isCompletedExceptionally)

        microphone.stopFuture.complete(Unit)
        fixture.dispatcher.runAll()

        assertFutureFails(started)
        assertEquals(1, lease.releaseCount)
        assertFalse(controller.state.captureActive)
    }

    @Test
    fun nativeFailureStopsRecorderAndWaitsForBothNativeCloseAndRecorderStop() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        fixture.sessionFactory.emit(update("run-1", "avant la panne"))
        fixture.dispatcher.runAll()
        val lease = fixture.reservation.lastLease
        val microphone = fixture.microphoneFactory.recorders.single()
        microphone.stopFuture = CompletableFuture()

        fixture.sessionFactory.fail("private native failure")
        fixture.dispatcher.runAll()

        assertEquals(1, microphone.stopCount)
        assertTrue(controller.state.captureActive)
        assertFalse(controller.state.document.finished)
        fixture.sessionFactory.session.closed.complete(Unit)
        fixture.dispatcher.runAll()
        assertEquals(0, lease.releaseCount)
        assertFalse(controller.state.document.finished)

        microphone.stopFuture.complete(Unit)
        fixture.dispatcher.runAll()

        assertEquals(1, lease.releaseCount)
        assertFalse(controller.state.captureActive)
        assertTrue(controller.state.document.finished)
        assertEquals("avant la panne", fixture.savedDrafts.single().turns.single().recognizedText)
    }

    @Test
    fun nativeFailureCompletesPauseWaitingForCheckpoint() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        val session = fixture.sessionFactory.session
        val microphone = fixture.microphoneFactory.recorders.single()
        session.checkpointFuture = CompletableFuture()

        val paused = controller.pause()
        microphone.stopFuture.complete(Unit)
        fixture.dispatcher.runAll()
        assertEquals(1, session.checkpointCount)
        assertFalse(paused.isDone)

        fixture.sessionFactory.fail("private failure while checkpointing")
        fixture.dispatcher.runAll()
        session.closed.complete(Unit)
        fixture.dispatcher.runAll()

        assertFutureFails(paused)
    }

    @Test
    fun finishThrowFallsBackToOneNativeCancel() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        val session = fixture.sessionFactory.session
        session.throwOnFinish = true

        controller.finish()
        fixture.dispatcher.runAll()

        assertEquals(1, session.finishCount)
        assertEquals(1, session.cancelCount)
        session.closed.complete(Unit)
        fixture.dispatcher.runAll()
    }

    @Test
    fun cancelBeforeNativeReadyCompletesPendingStartAndClosesOpenedSession() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        val started = controller.start("fr")
        val lease = FakeRuntimeLease()
        fixture.reservation.requests.single().grant(lease)
        fixture.dispatcher.runAll()

        val cancelled = controller.cancel()
        fixture.sessionFactory.session.closed.complete(Unit)
        fixture.dispatcher.runAll()

        assertFutureFails(started)
        assertTrue(cancelled.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertEquals(1, fixture.sessionFactory.session.cancelCount)
        assertEquals(1, lease.releaseCount)
    }

    @Test
    fun failedMicrophoneReleasePoisonsLeaseAndPreservesDraftDuringDestroy() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        fixture.sessionFactory.emit(update("run-1", "texte à préserver"))
        fixture.dispatcher.runAll()
        val lease = fixture.reservation.lastLease
        val microphone = fixture.microphoneFactory.recorders.single()
        microphone.stopFuture = CompletableFuture()

        val destroyed = controller.destroy()
        fixture.dispatcher.stopAccepting()
        microphone.stopFuture.completeExceptionally(IOException("private recorder failure"))
        fixture.sessionFactory.session.closed.complete(Unit)

        val failure = captureFailure { destroyed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        assertTrue("destroy must report an uncertain recorder release, got $failure", failure is ExecutionException)
        assertTrue((failure as ExecutionException).cause is IllegalStateException)
        assertEquals(1, lease.poisonCount)
        assertEquals(0, lease.releaseCount)
        assertEquals("texte à préserver", fixture.savedDrafts.single().turns.single().recognizedText)
    }

    @Test
    fun resumeUsesTheSameNativeSessionAndCreatesANewRecorder() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        val sessionBeforePause = fixture.sessionFactory.session
        fixture.pauseImmediately(controller)

        val resumed = controller.resume()
        fixture.dispatcher.runAll()

        assertTrue(resumed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertEquals(1, fixture.sessionFactory.startCount)
        assertEquals(2, fixture.microphoneFactory.recorders.size)
        assertTrue(sessionBeforePause === fixture.sessionFactory.session)
        assertEquals(1, fixture.microphoneFactory.recorders.last().startCount)
    }

    @Test
    fun repeatedPauseAndFinishDuringStopDoNotDuplicateNativeTransitionsOrQueueResume() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        val microphone = fixture.microphoneFactory.recorders.single()
        val session = fixture.sessionFactory.session
        microphone.stopFuture = CompletableFuture()
        session.checkpointFuture = CompletableFuture()

        val firstPause = controller.pause()
        val repeatedPause = controller.pause()
        val resumeDuringPause = controller.resume()

        assertEquals(1, microphone.stopCount)
        assertEquals(0, session.finishCount)
        assertTrue(controller.state.captureActive)
        assertNotNull(captureFailure { resumeDuringPause.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) })
        microphone.stopFuture.complete(Unit)
        fixture.drainMainUntil { !controller.state.captureActive }
        assertEquals(1, session.checkpointCount)
        session.checkpointFuture.complete(Unit)
        fixture.dispatcher.runAll()
        assertTrue(firstPause.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertTrue(repeatedPause.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertEquals(1, fixture.microphoneFactory.recorders.size)

        val firstFinish = controller.finish()
        val repeatedFinish = controller.finish()
        fixture.dispatcher.runAll()
        assertEquals(1, session.finishCount)
        assertFalse(firstFinish.isDone)
        assertFalse(repeatedFinish.isDone)
        assertTrue(fixture.publisher.documents.isEmpty())

        fixture.sessionFactory.emit(update("run-1", "dernière phrase", final = true))
        session.closed.complete(Unit)
        fixture.dispatcher.runAll()

        assertTrue(firstFinish.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertTrue(repeatedFinish.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertEquals(1, session.finishCount)
        assertEquals(1, fixture.microphoneFactory.recorders.size)
        assertEquals(1, fixture.microphoneFactory.recorders.single().startCount)
    }

    @Test
    fun destroyAfterGrantButBeforeNativeReadyNeverStartsMicrophone() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        val started = controller.start("fr")
        val lease = FakeRuntimeLease()
        fixture.reservation.requests.single().grant(lease)
        fixture.dispatcher.runAll()
        assertEquals(1, fixture.sessionFactory.startCount)

        val destroyed = controller.destroy()
        fixture.sessionFactory.fireReady()
        fixture.sessionFactory.session.closed.complete(Unit)
        fixture.dispatcher.runAll()

        assertTrue(destroyed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertTrue(fixture.microphoneFactory.recorders.isEmpty())
        assertEquals(1, fixture.sessionFactory.session.cancelCount)
        assertEquals(1, lease.releaseCount)
        assertFutureFails(started)
    }

    @Test
    fun ensureDocumentTurnIsIdempotentStableAndDoesNotRestartLiveAsr() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        fixture.sessionFactory.emit(update("run-1", "première parole"))
        fixture.dispatcher.runAll()
        val startsBefore = fixture.sessionFactory.startCount
        val documentBefore = controller.state.document.turns.single()

        val first = controller.ensureDocumentTurn()
        val second = controller.ensureDocumentTurn()

        assertEquals(first, second)
        assertEquals("session-1:document:turn", first.id)
        assertEquals(0L, first.utteranceId)
        assertNull(first.automaticParticipantId)
        assertEquals(listOf(0L, 1L), controller.state.document.turns.map { it.utteranceId })
        assertEquals("première parole", controller.state.document.turns.last().recognizedText)
        assertEquals("première parole", documentBefore.recognizedText)
        assertEquals(startsBefore, fixture.sessionFactory.startCount)
        assertEquals(1, fixture.reservation.requests.size)
        assertEquals(controller.state.document, fixture.rememberedSnapshots.last())
    }

    @Test
    fun flushDraftCapturesFocusAndWaitsForWriterWithoutFinishingOrPublishingNote() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        fixture.sessionFactory.emit(update("run-1", "parole brute"))
        fixture.dispatcher.runAll()
        val turnId = controller.state.document.turns.single().id
        controller.editTurn(turnId, "parole corrigée")
        val focusCountBefore = fixture.focus.flushCount

        val flushed = controller.flushDraft()

        assertTrue(flushed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertEquals(focusCountBefore + 1, fixture.focus.flushCount)
        assertEquals(controller.state.document, fixture.savedDrafts.last())
        assertEquals("parole corrigée", fixture.savedDrafts.last().turns.single().editedText)
        assertFalse(controller.state.document.finished)
        assertTrue(fixture.publisher.documents.isEmpty())
        assertEquals(1, fixture.sessionFactory.startCount)
        assertTrue(fixture.rememberedSnapshots.contains(controller.state.document))
    }

    @Test
    fun cancellingPendingReservationReleasesALateLeaseWithoutStartingAudio() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        controller.start("fr")
        val request = fixture.reservation.requests.single()

        controller.cancel()
        assertTrue(request.cancelled)
        val lateLease = FakeRuntimeLease()
        request.grant(lateLease)
        fixture.dispatcher.runAll()

        assertEquals(1, lateLease.releaseCount)
        assertEquals(0, fixture.sessionFactory.startCount)
        assertTrue(fixture.microphoneFactory.recorders.isEmpty())
    }

    @Test
    fun destroyingDuringReservationCancelsStartAndReleasesLateLease() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        controller.start("fr")
        val request = fixture.reservation.requests.single()

        val destroyed = controller.destroy()
        assertTrue(request.cancelled)
        val lateLease = FakeRuntimeLease()
        request.grant(lateLease)
        fixture.dispatcher.runAll()

        assertEquals(1, lateLease.releaseCount)
        assertEquals(0, fixture.sessionFactory.startCount)
        assertTrue(fixture.microphoneFactory.recorders.isEmpty())
        assertTrue(destroyed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
    }

    @Test
    fun pauseStopsAndJoinsBeforeCheckpointAndPostsItsContinuationBehindUpdates() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        val microphone = fixture.microphoneFactory.recorders.single()
        val session = fixture.sessionFactory.session
        microphone.stopFuture = CompletableFuture()
        session.checkpointFuture = CompletableFuture()
        fixture.sessionFactory.emit(update("run-1", "premier mot"))

        val paused = controller.pause()
        assertEquals(MeetingRecordingPhase.PAUSING, controller.state.phase)
        assertTrue(controller.state.captureActive)
        assertEquals(1, microphone.stopCount)
        assertEquals(0, session.checkpointCount)
        assertTrue(fixture.savedDrafts.isEmpty())

        microphone.stopFuture.complete(Unit)
        assertFalse(paused.isDone)
        assertTrue(fixture.savedDrafts.isEmpty())
        assertTrue(controller.state.captureActive)

        fixture.drainMainUntil { !controller.state.captureActive }
        assertEquals(1, session.checkpointCount)
        assertFalse(session.checkpointFuture.isDone)
        assertTrue(fixture.savedDrafts.isEmpty())

        session.checkpointFuture.complete(Unit)
        fixture.dispatcher.runAll()

        assertEquals("premier mot", fixture.savedDrafts.single().turns.single().recognizedText)
        assertEquals(MeetingRecordingPhase.PAUSED, controller.state.phase)
        assertFalse(controller.state.captureActive)
        assertTrue(paused.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
    }

    @Test
    fun finishFromPauseAppliesFinalCallbacksBeforeOneDurablePublish() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        fixture.pauseImmediately(controller)
        fixture.sessionFactory.session.finishCount = 0

        val finished = controller.finish()
        assertEquals(0, fixture.sessionFactory.session.finishCount)
        fixture.dispatcher.runAll()
        assertEquals(1, fixture.sessionFactory.session.finishCount)
        assertTrue(fixture.publisher.documents.isEmpty())

        fixture.sessionFactory.emit(update("run-1", "dernière parole", revision = 1, final = true))
        fixture.sessionFactory.session.closed.complete(Unit)
        assertTrue(fixture.publisher.documents.isEmpty())
        fixture.dispatcher.runAll()

        assertTrue(finished.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertEquals(1, fixture.sessionFactory.session.finishCount)
        assertEquals(0, fixture.sessionFactory.session.cancelCount)
        assertEquals("dernière parole", fixture.publisher.documents.single().turns.single().recognizedText)
        assertTrue(fixture.sessionFactory.session.finishWasBeforeClose)
        assertEquals(listOf("draft", "note"), fixture.persistenceEvents.takeLast(2))
        assertEquals(MeetingRecordingPhase.FINISHED, controller.state.phase)
    }

    @Test
    fun destructionInvalidatesUiCallbacksButLeaseReleaseWaitsForNativeCloseAndIgnoresMainQueue() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        fixture.sessionFactory.emit(update("run-1", "avant fermeture"))
        fixture.dispatcher.runAll()
        val lease = fixture.reservation.lastLease
        val microphone = fixture.microphoneFactory.recorders.single()
        microphone.stopFuture = CompletableFuture()

        val destroyed = controller.destroy()
        assertEquals(0, lease.releaseCount)
        assertFalse(destroyed.isDone)
        fixture.dispatcher.stopAccepting()
        fixture.sessionFactory.emit(update("run-1", "callback tardif"))
        fixture.sessionFactory.session.closed.complete(Unit)
        assertEquals(0, lease.releaseCount)
        assertFalse(destroyed.isDone)

        microphone.stopFuture.complete(Unit)
        assertEquals(1, fixture.sessionFactory.session.cancelCount)
        assertEquals(1, lease.releaseCount)
        assertTrue(destroyed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertEquals(1, fixture.savedDrafts.size)
        assertEquals("avant fermeture", fixture.savedDrafts.single().turns.single().recognizedText)
        assertTrue(fixture.publisher.documents.isEmpty())
    }

    @Test
    fun unconfirmedNativeClosePoisonsRuntimeWithoutReleasingItsLease() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        val lease = fixture.reservation.lastLease
        val microphone = fixture.microphoneFactory.recorders.single()
        microphone.stopFuture = CompletableFuture()

        controller.destroy()
        fixture.dispatcher.stopAccepting()
        microphone.stopFuture.complete(Unit)
        fixture.sessionFactory.session.closed.completeExceptionally(IllegalStateException("private detail"))

        assertEquals(1, lease.poisonCount)
        assertEquals(0, lease.releaseCount)
        assertNull(controller.state.document.turns.firstOrNull())
    }

    @Test
    fun focusIsFlushedBeforeAsrMutationAndFilterDoesNotRestartTheSession() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)

        fixture.sessionFactory.emit(update("run-1", "bonjour"))
        fixture.dispatcher.runAll()
        assertTrue(fixture.events.indexOf("focus") < fixture.events.indexOf("document:bonjour"))

        val participantId = controller.state.document.participants.single().id
        val startsBeforeFilter = fixture.sessionFactory.startCount
        controller.setParticipantIgnored(participantId, true)

        assertEquals(2, fixture.focus.flushCount)
        assertEquals(startsBeforeFilter, fixture.sessionFactory.startCount)
        assertTrue(fixture.sessionFactory.session.closed.isDone.not())
    }

    @Test
    fun rejectedPcmStopsCaptureAndFinalizesOnlyAlreadyAcceptedBlocks() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        val microphone = fixture.microphoneFactory.recorders.single()
        fixture.sessionFactory.session.acceptResults.addAll(listOf(true, false))

        assertTrue(microphone.offer(byteArrayOf(1, 2)))
        assertFalse(microphone.offer(byteArrayOf(3, 4)))
        fixture.dispatcher.runAll()

        assertEquals(2, fixture.sessionFactory.session.acceptCount)
        assertEquals(1, microphone.stopCount)
        assertEquals(1, fixture.sessionFactory.session.finishCount)
        assertTrue(controller.state.recordingError != null)
    }

    @Test
    fun microphoneFailureStopsCaptureWithoutLoggingItsPrivateMessage() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        val microphone = fixture.microphoneFactory.recorders.single()

        microphone.fail("/private/path/audio")
        fixture.dispatcher.runAll()

        assertEquals(1, microphone.stopCount)
        assertEquals(1, fixture.sessionFactory.session.finishCount)
        assertFalse(controller.state.recordingError.orEmpty().contains("/private/path/audio"))
        assertTrue(controller.state.recordingError != null)
    }

    @Test
    fun finalDraftSaveFailureKeepsDocumentAndExplicitRetryClearsError() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        fixture.sessionFactory.emit(update("run-1", "parole conservée"))
        fixture.dispatcher.runAll()
        fixture.failNextDraftSave = true

        val finished = controller.finish()
        fixture.dispatcher.runAll()
        fixture.sessionFactory.session.closed.complete(Unit)
        fixture.dispatcher.runAll()

        assertTrue(captureFailure { finished.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) } != null)
        assertEquals("parole conservée", controller.state.document.turns.single().recognizedText)
        assertTrue(controller.state.saveError != null)
        assertTrue(fixture.publisher.documents.isEmpty())

        val retried = controller.retrySave()
        fixture.dispatcher.runAll()

        assertTrue(retried.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertNull(controller.state.saveError)
        assertTrue(fixture.savedDrafts.isNotEmpty())
    }

    @Test
    fun finalNotePublicationFailureKeepsDocumentAndExplicitRetryPublishesIt() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        fixture.sessionFactory.emit(update("run-1", "phrase à publier"))
        fixture.dispatcher.runAll()
        fixture.publisher.failNextSave = true

        val finished = controller.finish()
        fixture.dispatcher.runAll()
        fixture.sessionFactory.session.closed.complete(Unit)
        fixture.dispatcher.runAll()

        assertNotNull(captureFailure { finished.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) })
        assertTrue(controller.state.document.finished)
        assertTrue(controller.state.saveError != null)
        assertEquals(1, fixture.publisher.attemptCount)
        assertTrue(fixture.publisher.documents.isEmpty())
        assertEquals("phrase à publier", controller.state.document.turns.single().recognizedText)

        val retried = controller.retrySave()
        fixture.dispatcher.runAll()

        assertTrue(retried.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertNull(controller.state.saveError)
        assertEquals(2, fixture.publisher.attemptCount)
        assertEquals(controller.state.document, fixture.publisher.documents.single())
    }

    @Test
    fun finishingAnActuallyEmptySessionDoesNotPublishAnEmptyNote() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)

        val finished = controller.finish()
        fixture.dispatcher.runAll()
        assertEquals(1, fixture.sessionFactory.session.finishCount)
        fixture.sessionFactory.session.closed.complete(Unit)
        fixture.dispatcher.runAll()

        assertTrue(finished.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertTrue(controller.state.document.finished)
        assertTrue(controller.state.document.turns.isEmpty())
        assertTrue(fixture.publisher.documents.isEmpty())
        assertEquals(0, fixture.publisher.attemptCount)
    }

    @Test
    fun finishingAnExistingNoteAfterDeletingAllSpeechPublishesTheEmptyEdit() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        fixture.sessionFactory.emit(update("run-1", "texte à supprimer"))
        fixture.dispatcher.runAll()
        val sessionId = controller.state.document.sessionId
        val turnId = controller.state.document.turns.single().id
        fixture.publisher.savedNoteSessionIds += sessionId
        controller.editTurn(turnId, "")

        val finished = controller.finish()
        fixture.dispatcher.runAll()
        fixture.sessionFactory.session.closed.complete(Unit)
        fixture.dispatcher.runAll()

        assertTrue(finished.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertEquals("FINISH checks whether the edited document already has a durable note", 1,
            fixture.publisher.savedNoteLookupCount)
        assertTrue("saved-note lookup runs in a main-dispatch continuation", fixture.publisher.savedNoteLookupsRanOnMain)
        assertEquals(1, fixture.publisher.attemptCount)
        val published = fixture.publisher.documents.single()
        assertEquals(sessionId, published.sessionId)
        assertEquals("", published.turns.single().editedText)
        assertEquals("texte à supprimer", published.turns.single().recognizedText)
        assertTrue(published.finished)
        assertEquals(1, fixture.sessionFactory.startCount)
        assertEquals(1, fixture.sessionFactory.session.finishCount)
        assertFalse(controller.state.captureActive)
        assertEquals(MeetingRecordingPhase.FINISHED, controller.state.phase)
    }

    @Test
    fun savedNoteLookupFailureCanBeRetriedAfterNativeFinishWithoutRestartingCapture() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        fixture.sessionFactory.emit(update("run-1", "texte supprimé"))
        fixture.dispatcher.runAll()
        val sessionId = controller.state.document.sessionId
        val turnId = controller.state.document.turns.single().id
        fixture.publisher.savedNoteSessionIds += sessionId
        controller.editTurn(turnId, "")
        fixture.publisher.failNextSavedNoteLookup = true

        val finished = controller.finish()
        fixture.dispatcher.runAll()
        fixture.sessionFactory.session.closed.complete(Unit)
        fixture.dispatcher.runAll()

        assertFutureFails(finished)
        assertEquals(MeetingRecordingPhase.FINISHED, controller.state.phase)
        assertNotNull(controller.state.saveError)
        assertFalse(controller.state.saveError.orEmpty().contains("/private/path"))
        assertEquals(1, fixture.publisher.savedNoteLookupCount)
        assertEquals(0, fixture.publisher.attemptCount)
        assertFalse(controller.state.captureActive)
        assertEquals(1, fixture.sessionFactory.startCount)
        assertEquals(1, fixture.sessionFactory.session.finishCount)
        assertEquals(1, fixture.reservation.lastLease.releaseCount)

        val retry = controller.retrySave()
        fixture.dispatcher.runAll()

        assertTrue(retry.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertNull(controller.state.saveError)
        assertEquals(2, fixture.publisher.savedNoteLookupCount)
        assertTrue(fixture.publisher.savedNoteLookupsRanOnMain)
        assertEquals(1, fixture.publisher.attemptCount)
        val published = fixture.publisher.documents.single()
        assertEquals("", published.turns.single().editedText)
        assertEquals("texte supprimé", published.turns.single().recognizedText)
        assertTrue(published.finished)
        assertEquals(1, fixture.sessionFactory.startCount)
        assertEquals(1, fixture.sessionFactory.session.finishCount)
        assertEquals(1, fixture.reservation.lastLease.releaseCount)
    }

    @Test
    fun finishingWithoutSpeechPublishesAStillAttachedImageNote() {
        val fixture = RecordingFixture()
        val controller = fixture.newDocument()
        fixture.startListening(controller)
        fixture.publisher.attachmentsPresent = true

        val finished = controller.finish()
        fixture.dispatcher.runAll()
        fixture.sessionFactory.session.closed.complete(Unit)
        fixture.dispatcher.runAll()

        assertTrue(finished.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit)
        assertTrue(controller.state.document.finished)
        assertTrue(controller.state.document.turns.isEmpty())
        assertEquals(1, fixture.publisher.attemptCount)
        assertEquals(controller.state.document, fixture.publisher.documents.single())
    }

    private fun update(
        runId: String,
        text: String,
        revision: Long = 1,
        final: Boolean = false,
    ) = MeetingHypothesis(
        runId = runId,
        utteranceId = 1,
        revision = revision,
        words = listOf(MeetingWord(text, 0, 240, 1)),
        transcript = text,
        isFinal = final,
        stableSpeakerThroughMs = 240,
        audioProcessedMs = 320,
    )

    private fun restoredDocument() = MeetingDocument(
        sessionId = "restored-session",
        runId = "run-1",
        participants = listOf(MeetingParticipant("speaker-1", 1, 1)),
        turns = listOf(
            MeetingTurn(
                id = "turn-1",
                utteranceId = 1,
                startMs = 0,
                endMs = 240,
                recognizedText = "parole existante",
                automaticParticipantId = "speaker-1",
                attributionStable = true,
            ),
        ),
        finished = true,
    )

    private fun captureFailure(block: () -> Any?): Throwable? = try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }

    private fun assertFutureFails(future: CompletableFuture<*>): Unit {
        val failure = captureFailure { future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        assertTrue("expected future failure, got $failure", failure is ExecutionException)
        assertTrue((failure as ExecutionException).cause is IllegalStateException)
    }

    private class RecordingFixture(
        availability: MeetingModelAvailability = MeetingModelAvailability.READY,
    ) {
        val events = mutableListOf<String>()
        val persistenceEvents = mutableListOf<String>()
        val savedDrafts = mutableListOf<MeetingDocument>()
        val rememberedSnapshots = mutableListOf<MeetingDocument>()
        val modelAvailability = MutableModelAvailability(availability)
        val reservation = FakeReservationPort(events)
        val sessionFactory = FakeSessionFactoryPort(events)
        val microphoneFactory = FakeMicrophoneFactory(events)
        val dispatcher = ManualMainDispatcher()
        val publisher = FakeNotePublisher(persistenceEvents, isMainDispatch = { dispatcher.isDispatchingTask })
        val focus = FakeFocusedEditPort(events)
        val errorRelay = MeetingDraftErrorRelay()
        private val scheduler = InlineDraftScheduler()
        var failNextDraftSave = false
        private val writer = MeetingDraftWriter(
            persistence = MeetingDraftPersistence { document ->
                if (failNextDraftSave) {
                    failNextDraftSave = false
                    throw IOException("/private/path/draft")
                }
                savedDrafts += document
                persistenceEvents += "draft"
            },
            scheduler = scheduler,
            onErrorChanged = errorRelay::publish,
        )
        private val ports = MeetingRecordingPorts(
            modelAvailability = modelAvailability,
            reservation = reservation,
            sessionFactory = sessionFactory,
            microphoneFactory = microphoneFactory,
            notePublisher = publisher,
            mainDispatcher = dispatcher,
            focusedEdit = focus,
        )

        fun newDocument(): MeetingRecordingController = MeetingRecordingController.createNew(
            sessionId = "session-1",
            runId = "run-1",
            draftClaim = claim(MeetingDocumentRead.Absent, snapshot = null),
            ports = ports,
            onStateChanged = ::recordState,
        )

        fun restored(document: MeetingDocument): MeetingRecordingController =
            MeetingRecordingController.restored(
                draftClaim = claim(MeetingDocumentRead.Ready(document), document),
                ports = ports,
                onStateChanged = ::recordState,
            )

        fun restoredFromNote(document: MeetingDocument): MeetingRecordingController =
            MeetingRecordingController.restored(
                document = document,
                draftClaim = claim(MeetingDocumentRead.Absent, snapshot = null),
                ports = ports,
                onStateChanged = ::recordState,
            )

        private fun claim(
            recoveredDocument: MeetingDocumentRead,
            snapshot: MeetingDocument?,
        ) = MeetingDraftOwnershipClaim(
            path = File("/tmp/meeting-draft-test.json"),
            writer = writer,
            errorRelay = errorRelay,
            recoveredDocument = recoveredDocument,
            snapshot = snapshot,
            saveError = null,
            rememberAction = { rememberedSnapshots += it },
            relinquishAction = { document ->
                if (document != null) writer.updateSnapshot(document)
                writer.close()
            },
        )

        private fun recordState(state: MeetingRecordingState): Unit {
            state.document.turns.lastOrNull()?.recognizedText?.let { events += "document:$it" }
        }

        fun startListening(controller: MeetingRecordingController) {
            val started = controller.start("fr")
            val lease = FakeRuntimeLease()
            reservation.requests.single().grant(lease)
            dispatcher.runAll()
            sessionFactory.fireReady()
            dispatcher.runAll()
            started.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        fun pauseImmediately(controller: MeetingRecordingController) {
            val paused = controller.pause()
            microphoneFactory.recorders.last().stopFuture.complete(Unit)
            dispatcher.runAll()
            sessionFactory.session.checkpointFuture.complete(Unit)
            dispatcher.runAll()
            paused.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        fun drainMainUntil(condition: () -> Boolean): Unit {
            repeat(16) {
                if (condition()) return
                check(dispatcher.runNext()) { "main queue emptied before expected state transition" }
            }
            check(condition()) { "main queue did not reach expected state transition" }
        }
    }

    private class MutableModelAvailability(var value: MeetingModelAvailability) : MeetingModelAvailabilityPort {
        override fun currentAvailability(): MeetingModelAvailability = value
    }

    private class FakeRuntimeLease : MeetingNativeRuntimeLease {
        var releaseCount = 0
        var poisonCount = 0
        var usable = true
        var usabilityChecks = 0
            private set
        var unusableOnCheck: Int? = null
        override fun isCurrentAndUsable(): Boolean {
            usabilityChecks += 1
            return usable && unusableOnCheck != usabilityChecks
        }
        override fun release(): Unit { releaseCount += 1 }
        override fun poison(): Unit { poisonCount += 1 }
    }

    private class FakeReservationPort(private val events: MutableList<String>) : MeetingNativeReservationPort {
        val requests = mutableListOf<FakeReservationRequest>()
        val lastLease: FakeRuntimeLease
            get() = requests.last().grantedLease

        override fun request(runId: String): MeetingNativeReservationRequest {
            events += "reserve"
            return FakeReservationRequest().also { requests += it }
        }
    }

    private class FakeReservationRequest : MeetingNativeReservationRequest {
        override val lease = CompletableFuture<MeetingNativeRuntimeLease>()
        var cancelled = false
        lateinit var grantedLease: FakeRuntimeLease
            private set

        override fun cancel(): Unit { cancelled = true }

        fun grant(value: FakeRuntimeLease): Unit {
            grantedLease = value
            lease.complete(value)
        }

        fun fail(failure: Throwable): Unit {
            lease.completeExceptionally(failure)
        }
    }

    private class FakeSessionFactoryPort(private val events: MutableList<String>) : MeetingSessionFactoryPort {
        var startCount = 0
        lateinit var session: FakeMeetingSession
            private set
        private var ready: (() -> Unit)? = null
        private var update: ((MeetingHypothesis) -> Unit)? = null
        private var failure: ((String) -> Unit)? = null

        override fun start(
            runId: String,
            language: String,
            onReady: () -> Unit,
            onUpdate: (MeetingHypothesis) -> Unit,
            onFailure: (String) -> Unit,
        ): MeetingSession {
            events += "native-open"
            startCount += 1
            ready = onReady
            update = onUpdate
            failure = onFailure
            session = FakeMeetingSession()
            return session
        }

        fun fireReady(): Unit = requireNotNull(ready).invoke()
        fun emit(hypothesis: MeetingHypothesis): Unit = requireNotNull(update).invoke(hypothesis)
        fun fail(message: String): Unit = requireNotNull(failure).invoke(message)
    }

    private class FakeMeetingSession : MeetingSession {
        var checkpointFuture: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
        override val closed: CompletableFuture<Unit> = CompletableFuture()
        override val queuedAudioMs: Long = 0
        var checkpointCount = 0
        var finishCount = 0
        var cancelCount = 0
        var throwOnFinish = false
        var finishWasBeforeClose = false
        var acceptCount = 0
        val acceptResults = ArrayDeque<Boolean>()

        override fun acceptPcm16(buffer: ByteArray, length: Int): Boolean {
            acceptCount += 1
            return if (acceptResults.isEmpty()) true else acceptResults.removeFirst()
        }
        override fun checkpoint(): CompletableFuture<Unit> { checkpointCount += 1; return checkpointFuture }
        override fun finish(): Unit {
            finishCount += 1
            finishWasBeforeClose = !closed.isDone
            if (throwOnFinish) throw IllegalStateException("private finish failure")
        }
        override fun cancel(): Unit { cancelCount += 1 }
    }

    private class FakeMicrophoneFactory(private val events: MutableList<String>) : MeetingMicrophoneFactoryPort {
        val recorders = mutableListOf<FakeMicrophone>()
        var beforeReturn: ((FakeMicrophone) -> Unit)? = null
        override fun create(): MeetingMicrophonePort {
            val recorder = FakeMicrophone(events).also { recorders += it }
            beforeReturn?.invoke(recorder)
            return recorder
        }
    }

    private class FakeMicrophone(private val events: MutableList<String>) : MeetingMicrophonePort {
        var startCount = 0
        var stopCount = 0
        var stopFuture: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
        private var onPcm16: ((ByteArray, Int) -> Boolean)? = null
        private var onFailure: ((String) -> Unit)? = null

        override fun start(onPcm16: (ByteArray, Int) -> Boolean, onFailure: (String) -> Unit): Boolean {
            events += "microphone-start"
            startCount += 1
            this.onPcm16 = onPcm16
            this.onFailure = onFailure
            return true
        }

        override fun stopAndJoin(): CompletableFuture<Unit> { stopCount += 1; return stopFuture }
        fun offer(bytes: ByteArray): Boolean = requireNotNull(onPcm16).invoke(bytes, bytes.size)
        fun fail(message: String): Unit = requireNotNull(onFailure).invoke(message)
    }

    private class FakeNotePublisher(
        private val events: MutableList<String>,
        private val isMainDispatch: () -> Boolean,
    ) : MeetingNotePublisherPort {
        val documents = mutableListOf<MeetingDocument>()
        var failNextSave = false
        var attemptCount = 0
        var attachmentsPresent = false
        var failNextSavedNoteLookup = false
        var savedNoteLookupCount = 0
        var savedNoteLookupsRanOnMain = true
        val savedNoteSessionIds = mutableSetOf<String>()
        override fun hasAttachments(sessionId: String): Boolean = attachmentsPresent
        override fun hasSavedNote(sessionId: String): Boolean {
            savedNoteLookupCount += 1
            savedNoteLookupsRanOnMain = savedNoteLookupsRanOnMain && isMainDispatch()
            if (failNextSavedNoteLookup) {
                failNextSavedNoteLookup = false
                throw IllegalStateException("/private/path/note-lookup")
            }
            return sessionId in savedNoteSessionIds
        }
        override fun save(document: MeetingDocument): CompletableFuture<Unit> {
            events += "note"
            attemptCount += 1
            if (failNextSave) {
                failNextSave = false
                return CompletableFuture<Unit>().also {
                    it.completeExceptionally(IllegalStateException("/private/path/note"))
                }
            }
            documents += document
            savedNoteSessionIds += document.sessionId
            return CompletableFuture.completedFuture(Unit)
        }
    }

    private class FakeFocusedEditPort(private val events: MutableList<String>) : MeetingFocusedEditPort {
        var flushCount = 0
        override fun flushFocusedEdit(): Boolean { events += "focus"; flushCount += 1; return true }
    }

    private class ManualMainDispatcher : MeetingMainDispatcher {
        private val tasks = ArrayDeque<() -> Unit>()
        private var accepting = true
        var isDispatchingTask = false
            private set
        val pendingCount: Int
            get() = tasks.size

        override fun post(task: () -> Unit): Unit {
            if (accepting) tasks.addLast(task)
        }

        fun runNext(): Boolean {
            val task = tasks.pollFirst() ?: return false
            isDispatchingTask = true
            try {
                task()
            } finally {
                isDispatchingTask = false
            }
            return true
        }

        fun runAll(): Unit { while (runNext()) Unit }
        fun stopAccepting(): Unit { accepting = false; tasks.clear() }
    }

    private class InlineDraftScheduler : MeetingDraftScheduler {
        override fun schedule(delayMs: Long, task: () -> Unit): MeetingDraftScheduledTask = MeetingDraftScheduledTask {}
        override fun execute(task: () -> Unit): Unit = task()
        override fun shutdown(): Unit = Unit
    }

    private companion object {
        const val TIMEOUT_SECONDS = 2L
    }
}
