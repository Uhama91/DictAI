package com.kafkasl.phonewhisper

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class GemmaRuntimeAdmissionTest {
    @Test fun initializationTimeoutRejectsWaitersUntilNativeReturns() {
        val admission = GemmaRuntimeAdmission()
        val deadline = GemmaInitializationDeadline(admission::block, admission::resume)
        val preparing = CompletableFuture<Long>()
        assertTrue(admission.admit(preparing))
        deadline.timeout()
        assertTrue(preparing.isCompletedExceptionally)
        val duringLoad = CompletableFuture<String?>()
        assertFalse(admission.admit(duringLoad))
        deadline.returned()
        assertFalse(admission.isBlocked)
        assertTrue(admission.admit(CompletableFuture<String?>()))
        assertTrue(preparing.isCompletedExceptionally)
    }

    @Test fun initializationReturningBeforeWatchdogCannotBeBlockedByLateAlarm() {
        val admission = GemmaRuntimeAdmission()
        val deadline = GemmaInitializationDeadline(admission::block, admission::resume)
        deadline.returned()
        deadline.timeout()
        assertFalse(admission.isBlocked)
        assertTrue(admission.admit(CompletableFuture<String?>()))
    }

    @Test fun repeatedWatchdogOrCleanupDoesNotUndoALaterFailure() {
        val admission = GemmaRuntimeAdmission()
        val deadline = GemmaInitializationDeadline(admission::block, admission::resume)
        deadline.timeout()
        deadline.timeout()
        deadline.returned()
        admission.block()
        deadline.returned()
        deadline.timeout()
        assertTrue(admission.isBlocked)
    }

    @Test fun stalledCancellationReleasesQueuedWaitersAndRejectsNewWork() {
        val admission = GemmaRuntimeAdmission()
        val queuedGeneration = CompletableFuture<String?>()
        val queuedPreparation = CompletableFuture<Long>()
        assertTrue(admission.admit(queuedGeneration))
        assertTrue(admission.admit(queuedPreparation))
        admission.block()
        for (pending in listOf(queuedGeneration, queuedPreparation)) {
            assertTrue(pending.isCompletedExceptionally)
            try {
                pending.get(1L, TimeUnit.SECONDS)
                fail("Blocked native runtime cannot leave a waiting caller")
            } catch (_: ExecutionException) { }
        }
        val next = CompletableFuture<String?>()
        assertFalse(admission.admit(next))
        assertTrue(next.isCompletedExceptionally)
    }

    @Test fun terminalCleanupAllowsNewWorkWithoutRevivingRejectedRequests() {
        val admission = GemmaRuntimeAdmission()
        val previous = CompletableFuture<String?>()
        assertTrue(admission.admit(previous))
        admission.block()
        admission.resume()
        val next = CompletableFuture<String?>()
        assertTrue(admission.admit(next))
        assertFalse(next.isDone)
        assertTrue(previous.isCompletedExceptionally)
        next.complete("Ready")
        assertEquals("Ready", next.get(1L, TimeUnit.SECONDS))
    }

    @Test fun completedRequestsAreNotQueuedOrChangedByLaterCancellationFailure() {
        val admission = GemmaRuntimeAdmission()
        val result = CompletableFuture.completedFuture("Existing result")
        assertFalse(admission.admit(result))
        admission.block()
        assertEquals("Existing result", result.get(1L, TimeUnit.SECONDS))
    }
}
