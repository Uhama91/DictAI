package com.kafkasl.phonewhisper

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class LocalFinishDiagnosticTest {
    private fun request(text: String = "pain lait") = LocalFormatRequest(text, "List", "French")

    private fun backend(
        cancel: () -> Unit = {},
        body: (LocalFormatRequest, (String) -> Unit, () -> Unit) -> String?,
    ) = object : LocalFormatBackend {
        override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String? =
            body(request, onChunk, {})
        override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit, onNativeStart: () -> Unit): String? =
            body(request, onChunk, onNativeStart)
        override fun cancel() = cancel.invoke()
    }

    @Test fun generatedThenCachedResultRetainsItsActualNativeInvocation() {
        val calls = AtomicInteger()
        LocalFormattingSession(backend { _, _, started ->
            calls.incrementAndGet()
            started()
            "• pain\n• lait"
        }).use { session ->
            assertNull(session.lastFinish)
            assertEquals("• pain\n• lait", session.finish(request(), 2000) {})
            val generated = session.lastFinish!!
            assertEquals("generated", generated.route)
            assertEquals("applied", generated.outcome)
            assertTrue(generated.nativeStarted)
            assertTrue(generated.waitMs >= 0L)
            assertEquals("• pain\n• lait", session.finish(request(), 2000) {})
            assertEquals("cache", session.lastFinish!!.route)
            assertEquals("applied", session.lastFinish!!.outcome)
            assertTrue(session.lastFinish!!.nativeStarted)
            assertEquals(1, calls.get())
        }
    }

    @Test fun directAcknowledgmentNeverClaimsNativeGeneration() {
        LocalFormattingSession(backend { _, _, _ -> error("Direct result should bypass the backend") }).use { session ->
            val request = request("OK ça marche").copy(layoutKind = LocalLayoutKind.EMAIL)
            assertEquals(request.text, session.finish(request, 2000) {})
            assertEquals("direct", session.lastFinish!!.route)
            assertEquals("applied", session.lastFinish!!.outcome)
            assertFalse(session.lastFinish!!.nativeStarted)
        }
    }

    @Test fun unchangedEmailIsAnAcceptedResultRatherThanAnAssumedFallback() {
        val request = request("Bonjour Paul, voici le dossier demandé pour la réunion.")
            .copy(layoutKind = LocalLayoutKind.EMAIL)
        LocalFormattingSession(backend { next, _, started -> started(); next.text }).use { session ->
            assertEquals(request.text, session.finish(request, 2000) {})
            assertEquals("applied", session.lastFinish!!.outcome)
            assertTrue(session.lastFinish!!.nativeStarted)
        }
    }

    @Test fun legacyBackendDoesNotPretendItEnteredNativeCode() {
        LocalFormattingSession(object : LocalFormatBackend {
            override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit) = request.text
            override fun cancel() {}
        }).use { session ->
            assertEquals("pain lait", session.finish(request(), 2000) {})
            assertFalse(session.lastFinish!!.nativeStarted)
        }
    }

    @Test fun emptyBackendAndThrownBackendHaveDifferentOutcomesWithoutExceptionText() {
        for ((output, expected) in listOf(null to "backend_empty", "   " to "backend_empty")) {
            LocalFormattingSession(backend { _, _, started -> started(); output }).use { session ->
                assertNull(session.finish(request(), 2000) {})
                assertEquals(expected, session.lastFinish!!.outcome)
                assertTrue(session.lastFinish!!.nativeStarted)
            }
        }
        LocalFormattingSession(backend { _, _, _ -> error("private-source-and-exception") }).use { session ->
            assertNull(session.finish(request("private-source-and-exception"), 2000) {})
            assertEquals("backend_error", session.lastFinish!!.outcome)
            assertFalse(session.lastFinish!!.nativeStarted)
            assertFalse(session.lastFinish.toString().contains("private-source-and-exception"))
        }
    }

    @Test fun fidelityAndExactVocabularyRejectionsAreDistinguishable() {
        LocalFormattingSession(backend { _, _, started -> started(); "• pain\n• riz" }).use { session ->
            assertNull(session.finish(request().copy(layoutKind = LocalLayoutKind.LIST), 2000) {})
            assertEquals("fidelity_rejected", session.lastFinish!!.outcome)
        }
        val request = request("Bonjour Jean Pierre, voici le dossier.").copy(
            layoutKind = LocalLayoutKind.EMAIL,
            protectedTerms = listOf("Jean Pierre"),
        )
        LocalFormattingSession(backend { _, _, started -> started(); "Bonjour Jean\n\nPierre, voici le dossier." }).use { session ->
            assertNull(session.finish(request, 2000) {})
            assertEquals("vocabulary_rejected", session.lastFinish!!.outcome)
            assertFalse(session.lastFinish.toString().contains("Jean"))
        }
    }

    @Test fun queuedFinalRequestIsIdentifiedBeforeCancellingTheObsoleteDraft() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        LocalFormattingSession(backend(cancel = { release.countDown() }) { next, _, started ->
            if (next.text == "old") {
                entered.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                null
            } else {
                started()
                next.text
            }
        }).use { session ->
            session.offer(request("old"))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            session.offer(request("final"))
            assertNull(session.lastFinish)
            assertEquals("final", session.finish(request("final"), 2000) {})
            assertEquals("queued", session.lastFinish!!.route)
            assertEquals("applied", session.lastFinish!!.outcome)
            assertTrue(session.lastFinish!!.nativeStarted)
        }
    }

    @Test fun timeoutSnapshotPrecedesCancellationAndSurvivesLateNativeStartAndResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val atCancellation = AtomicReference<LocalFinishDiagnostic>()
        lateinit var session: LocalFormattingSession
        session = LocalFormattingSession(backend(cancel = {
            atCancellation.set(session.lastFinish)
            release.countDown()
        }) { next, chunk, started ->
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            started()
            chunk(next.text)
            returned.countDown()
            next.text
        })
        try {
            session.offer(request())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNull(session.finish(request(), 20) { fail("Late text must be discarded") })
            val diagnostic = session.lastFinish!!
            assertSame(diagnostic, atCancellation.get())
            assertEquals("in_flight", diagnostic.route)
            assertEquals("wait_timeout", diagnostic.outcome)
            assertFalse(diagnostic.nativeStarted)
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertSame(diagnostic, session.lastFinish)
            session.close()
            assertSame(diagnostic, session.lastFinish)
        } finally { release.countDown(); session.close() }
    }

    @Test fun timeoutAfterNativeStartedReportsThatInvocation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        LocalFormattingSession(backend(cancel = { release.countDown() }) { next, _, started ->
            started()
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            next.text
        }).use { session ->
            session.offer(request())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNull(session.finish(request(), 20) {})
            assertEquals("wait_timeout", session.lastFinish!!.outcome)
            assertTrue(session.lastFinish!!.nativeStarted)
        }
    }

    @Test fun closingAnAwaitedFinishReportsCancellation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<String>("unfinished")
        val session = LocalFormattingSession(backend(cancel = { release.countDown() }) { next, _, started ->
            started()
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            next.text
        })
        val finishing = Thread { result.set(session.finish(request(), 2000) {}) }
        try {
            finishing.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            session.close()
            finishing.join(2000)
            assertFalse(finishing.isAlive)
            assertNull(result.get())
            assertEquals("cancelled", session.lastFinish!!.outcome)
            assertTrue(session.lastFinish!!.nativeStarted)
        } finally { release.countDown(); session.close(); finishing.interrupt() }
    }

    @Test fun interruptionIsReportedAndTheInterruptFlagIsRestored() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val result = AtomicReference<String>("unfinished")
        val session = LocalFormattingSession(backend(cancel = { release.countDown() }) { next, _, started ->
            started()
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            next.text
        })
        val finishing = Thread {
            result.set(session.finish(request(), 2000) {})
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        try {
            finishing.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            finishing.interrupt()
            finishing.join(2000)
            assertFalse(finishing.isAlive)
            assertNull(result.get())
            assertTrue(interrupted.get())
            assertEquals("interrupted", session.lastFinish!!.outcome)
        } finally { release.countDown(); session.close(); finishing.interrupt() }
    }

    @Test fun emptyOrClosedFinishRecordsNoBackendCall() {
        val session = LocalFormattingSession(backend { _, _, _ -> error("Unexpected backend call") })
        assertNull(session.finish(request(" "), 2000) {})
        assertEquals("not_called", session.lastFinish!!.route)
        assertEquals("empty_input", session.lastFinish!!.outcome)
        session.close()
        assertNull(session.finish(request(), 2000) {})
        assertEquals("not_called", session.lastFinish!!.route)
        assertEquals("cancelled", session.lastFinish!!.outcome)
        assertFalse(session.lastFinish!!.nativeStarted)
    }
}
