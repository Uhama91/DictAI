package com.kafkasl.phonewhisper.meeting

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingDiarizationBacklogTest {
    @Test
    fun transfer_worker_spools_and_drains_pcm_fifo_without_mutating_captured_buffers() {
        val root = Files.createTempDirectory("meeting-diarization-backlog").toFile()
        val transferred = CountDownLatch(3)
        val backlog = MeetingDiarizationBacklog(
            spoolRoot = root,
            spoolCapacityBytes = 64,
            memoryCapacityBytes = 4,
            ingressCapacityBytes = 12,
            segmentTargetBytes = 8,
            onBlockTransferredForTest = { transferred.countDown() },
        )
        val originalBlocks = listOf(
            byteArrayOf(1, 2, 3, 4),
            byteArrayOf(5, 6, 7, 8),
            byteArrayOf(9, 10, 11, 12),
        )
        val offeredBlocks = originalBlocks.map(ByteArray::clone)

        try {
            backlog.prepare()
            offeredBlocks.forEach { assertEquals(MeetingDiarizationBacklog.OfferResult.ACCEPTED, backlog.offer(it, it.size)) }
            assertTrue("the transfer worker should store all three blocks", transferred.await(3, TimeUnit.SECONDS))
            assertTrue("a queued delayed block should have a private spool segment", root.walkTopDown().any { it.extension == "pcmq" })
            offeredBlocks.forEach { it.fill(99) }
            backlog.finishInput()

            originalBlocks.forEach { assertArrayEquals(it, backlog.take()) }
            assertNull(backlog.take())
            assertTrue("the consumed session spool is reclaimed", root.walkTopDown().none { it.extension == "pcmq" })
        } finally {
            backlog.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun admission_remains_nonblocking_when_the_transfer_worker_is_stalled_and_ingress_is_bounded() {
        val transferEntered = CountDownLatch(1)
        val transferRelease = CountDownLatch(1)
        val backlog = MeetingDiarizationBacklog(
            spoolRoot = null,
            spoolCapacityBytes = 0,
            memoryCapacityBytes = 8,
            ingressCapacityBytes = 8,
            beforeQueueOfferForTest = {
                transferEntered.countDown()
                check(transferRelease.await(3, TimeUnit.SECONDS))
            },
        )
        try {
            backlog.prepare()
            assertEquals(MeetingDiarizationBacklog.OfferResult.ACCEPTED, backlog.offer(byteArrayOf(1, 2, 3, 4), 4))
            assertTrue("transfer worker did not reach the held write", transferEntered.await(3, TimeUnit.SECONDS))

            assertEquals(MeetingDiarizationBacklog.OfferResult.ACCEPTED, backlog.offer(byteArrayOf(5, 6, 7, 8), 4))
            val offerReturned = CountDownLatch(1)
            val thirdResult = AtomicReference<MeetingDiarizationBacklog.OfferResult>()
            Thread({
                thirdResult.set(backlog.offer(byteArrayOf(9, 10), 2))
                offerReturned.countDown()
            }, "meeting-test-backlog-admission").apply { isDaemon = true }.start()

            assertTrue("capture admission must not wait on the held transfer operation", offerReturned.await(250, TimeUnit.MILLISECONDS))
            assertEquals(MeetingDiarizationBacklog.OfferResult.BACKLOG_LIMIT, thirdResult.get())
        } finally {
            transferRelease.countDown()
            backlog.close()
        }
    }

    @Test
    fun cancel_does_not_wait_for_a_stalled_transfer_and_close_reclaims_the_session_spool() {
        val root = Files.createTempDirectory("meeting-diarization-backlog-cancel").toFile()
        val transferEntered = CountDownLatch(1)
        val transferRelease = CountDownLatch(1)
        val backlog = MeetingDiarizationBacklog(
            spoolRoot = root,
            spoolCapacityBytes = 64,
            memoryCapacityBytes = 4,
            ingressCapacityBytes = 4,
            beforeQueueOfferForTest = {
                transferEntered.countDown()
                check(transferRelease.await(3, TimeUnit.SECONDS))
            },
        )
        try {
            backlog.prepare()
            assertEquals(MeetingDiarizationBacklog.OfferResult.ACCEPTED, backlog.offer(byteArrayOf(1, 2, 3, 4), 4))
            assertTrue("transfer worker did not reach the held write", transferEntered.await(3, TimeUnit.SECONDS))
            assertTrue(root.listFiles().orEmpty().any { it.isDirectory && it.name.startsWith("session-") })

            val cancelReturned = CountDownLatch(1)
            Thread({ backlog.cancel(); cancelReturned.countDown() }, "meeting-test-backlog-cancel")
                .apply { isDaemon = true }
                .start()
            assertTrue("cancel waits for the stalled transfer", cancelReturned.await(250, TimeUnit.MILLISECONDS))
            assertNull("cancel wakes a blocked reader", backlog.take())

            transferRelease.countDown()
            backlog.close()
            assertTrue(
                "close reclaims the private spool session",
                root.listFiles().orEmpty().none { it.isDirectory && it.name.startsWith("session-") },
            )
        } finally {
            transferRelease.countDown()
            backlog.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun spool_preparation_failure_is_reported_as_storage_error_before_capture() {
        val parent = Files.createTempDirectory("meeting-diarization-backlog-prepare").toFile()
        val notDirectory = File(parent, "not-a-directory").apply { writeText("block") }
        val backlog = MeetingDiarizationBacklog(
            spoolRoot = notDirectory,
            spoolCapacityBytes = 64,
            memoryCapacityBytes = 4,
            ingressCapacityBytes = 4,
        )
        try {
            val failure = assertThrows(MeetingDiarizationBacklogException::class.java) { backlog.prepare() }
            assertEquals(MeetingDiarizationBacklogException.Kind.STORAGE_ERROR, failure.kind)
        } finally {
            backlog.close()
            parent.deleteRecursively()
        }
    }

    @Test
    fun spool_capacity_failure_is_distinct_from_storage_failure_and_cancels_the_reader() {
        val root = Files.createTempDirectory("meeting-diarization-backlog-limit").toFile()
        val failure = AtomicReference<MeetingDiarizationBacklogException?>()
        val failed = CountDownLatch(1)
        val firstFourTransferred = CountDownLatch(4)
        val backlog = MeetingDiarizationBacklog(
            spoolRoot = root,
            spoolCapacityBytes = 8,
            memoryCapacityBytes = 4,
            ingressCapacityBytes = 10,
            segmentTargetBytes = 8,
            onBlockTransferredForTest = { firstFourTransferred.countDown() },
            onFailure = {
                failure.set(it)
                failed.countDown()
            },
        )
        try {
            backlog.prepare()
            repeat(4) { block ->
                assertEquals(
                    MeetingDiarizationBacklog.OfferResult.ACCEPTED,
                    backlog.offer(byteArrayOf((block * 2).toByte(), (block * 2 + 1).toByte()), 2),
                )
            }
            assertTrue("four blocks should reach the downstream queue before the capacity probe", firstFourTransferred.await(3, TimeUnit.SECONDS))
            assertEquals(
                MeetingDiarizationBacklog.OfferResult.ACCEPTED,
                backlog.offer(byteArrayOf(8, 9), 2),
            )

            assertTrue("spool capacity failure should wake bridge observers", failed.await(3, TimeUnit.SECONDS))
            assertEquals(MeetingDiarizationBacklogException.Kind.BACKLOG_LIMIT, failure.get()?.kind)
            assertNull("failure cancels and wakes a blocked reader", backlog.take())
        } finally {
            backlog.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun finish_without_audio_closes_the_reader_and_cancel_discards_pending_audio() {
        val emptyBacklog = MeetingDiarizationBacklog(
            spoolRoot = null,
            spoolCapacityBytes = 0,
            memoryCapacityBytes = 8,
            ingressCapacityBytes = 8,
        )
        emptyBacklog.prepare()
        emptyBacklog.finishInput()
        assertNull(emptyBacklog.take())
        emptyBacklog.close()

        val backlog = MeetingDiarizationBacklog(
            spoolRoot = null,
            spoolCapacityBytes = 0,
            memoryCapacityBytes = 8,
            ingressCapacityBytes = 8,
        )
        backlog.prepare()
        assertEquals(MeetingDiarizationBacklog.OfferResult.ACCEPTED, backlog.offer(byteArrayOf(1, 2), 2))
        backlog.cancel()
        assertNull(backlog.take())
        assertFalse(backlog.isFinished)
        backlog.close()
    }
}
