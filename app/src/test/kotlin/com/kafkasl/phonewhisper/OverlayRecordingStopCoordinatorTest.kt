package com.kafkasl.phonewhisper

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayRecordingStopCoordinatorTest {
    @Test
    fun active_load_guard_never_reads_the_potentially_locked_resident_engine() {
        val loading = AtomicBoolean(true)
        var residentRead = false

        val result = LocalLoadStartGate.acquire(
            localLoading = loading,
            isDestroyed = { false },
            isLoaded = {
                residentRead = true
                false
            },
        )

        assertEquals(LocalLoadStartGate.Decision.BUSY, result)
        assertTrue(loading.get())
        assertEquals(false, residentRead)
    }

    @Test
    fun already_loaded_guard_releases_loading_ownership() {
        val loading = AtomicBoolean(false)

        val result = LocalLoadStartGate.acquire(
            localLoading = loading,
            isDestroyed = { false },
            isLoaded = { true },
        )

        assertEquals(LocalLoadStartGate.Decision.ALREADY_LOADED, result)
        assertEquals(false, loading.get())
    }

    @Test
    fun destroyed_lifecycle_rejects_a_late_engine_publication() {
        val lifecycle = LocalEngineLifecycle()
        var published = false

        lifecycle.destroy { }
        val accepted = lifecycle.publishIfAlive { published = true }

        assertEquals(false, accepted)
        assertEquals(false, published)
    }

    @Test
    fun resident_close_is_dispatched_without_running_on_the_caller() {
        var pending: (() -> Unit)? = null
        var closed = false

        dispatchResidentClose(close = { closed = true }, launch = { pending = it })

        assertEquals(false, closed)
        pending!!.invoke()
        assertTrue(closed)
    }

    @Test
    fun completed_stop_joins_releases_then_snapshots_in_order() {
        val events = mutableListOf<String>()
        val coordinator = RecordingStopCoordinator(
            recordThread = null,
            stopRecorder = { events += "stop" },
            releaseRecorder = { events += "release" },
            snapshot = { events += "snapshot" },
        )

        assertFalse(recorderReleaseConfirmed(coordinator))
        val result = coordinator.stopJoinRelease(timeoutMs = 1)

        assertEquals(RecordingStopCoordinator.Result.Stopped, result)
        assertTrue(recorderReleaseConfirmed(coordinator))
        assertEquals(listOf("stop", "release", "snapshot"), events)
    }

    @Test
    fun failed_stop_still_confirms_a_successful_release() {
        val events = mutableListOf<String>()
        val coordinator = RecordingStopCoordinator(
            recordThread = null,
            stopRecorder = { events += "stop"; error("synthetic stop failure") },
            releaseRecorder = { events += "release" },
            snapshot = { events += "snapshot" },
        )

        assertFalse(recorderReleaseConfirmed(coordinator))
        val result = coordinator.stopJoinRelease(timeoutMs = 1)

        assertEquals(RecordingStopCoordinator.Result.Stopped, result)
        assertTrue(recorderReleaseConfirmed(coordinator))
        assertEquals(listOf("stop", "release", "snapshot"), events)
    }

    @Test
    fun failed_release_never_confirms_release_but_keeps_snapshot_order() {
        val events = mutableListOf<String>()
        val coordinator = RecordingStopCoordinator(
            recordThread = null,
            stopRecorder = { events += "stop" },
            releaseRecorder = { events += "release"; error("synthetic release failure") },
            snapshot = { events += "snapshot" },
        )

        val result = coordinator.stopJoinRelease(timeoutMs = 1)

        assertEquals(RecordingStopCoordinator.Result.Stopped, result)
        assertFalse(recorderReleaseConfirmed(coordinator))
        assertEquals(listOf("stop", "release", "snapshot"), events)
    }

    @Test
    fun timed_out_join_defers_release_and_snapshot_until_the_blocked_reader_exits() {
        val unblock = CountDownLatch(1)
        val started = CountDownLatch(1)
        val releaseEntered = CountDownLatch(1)
        val allowReleaseReturn = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        var cleanupThread: Thread? = null
        val recordThread = Thread {
            started.countDown()
            unblock.await()
        }.apply { start() }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        val coordinator = RecordingStopCoordinator(
            recordThread = recordThread,
            stopRecorder = { events += "stop" },
            releaseRecorder = {
                events += "release"
                releaseEntered.countDown()
                check(allowReleaseReturn.await(1, TimeUnit.SECONDS))
            },
            snapshot = { events += "snapshot" },
        )

        try {
            assertFalse(recorderReleaseConfirmed(coordinator))
            val result = coordinator.stopJoinRelease(timeoutMs = 1)

            assertEquals(RecordingStopCoordinator.Result.TimedOut, result)
            assertFalse(recorderReleaseConfirmed(coordinator))
            assertEquals(listOf("stop"), events.toList())
            val cleanupResult = AtomicReference<RecordingStopCoordinator.Result>()
            val cleanupDone = CountDownLatch(1)
            cleanupThread = Thread {
                cleanupResult.set(coordinator.awaitExitThenRelease())
                cleanupDone.countDown()
            }.apply { start() }
            assertEquals(listOf("stop"), events.toList())
            unblock.countDown()
            assertTrue(releaseEntered.await(1, TimeUnit.SECONDS))
            assertFalse("release is not confirmed until releaseRecorder returns", recorderReleaseConfirmed(coordinator))
            assertEquals(listOf("stop", "release"), events.toList())

            allowReleaseReturn.countDown()
            assertTrue(cleanupDone.await(1, TimeUnit.SECONDS))
            assertEquals(RecordingStopCoordinator.Result.Stopped, cleanupResult.get())
            assertTrue(recorderReleaseConfirmed(coordinator))
            assertEquals(listOf("stop", "release", "snapshot"), events.toList())
        } finally {
            unblock.countDown()
            allowReleaseReturn.countDown()
            cleanupThread?.join(1_000)
            recordThread.join(1_000)
        }
    }

    private fun recorderReleaseConfirmed(coordinator: RecordingStopCoordinator): Boolean {
        val getter = coordinator.javaClass.declaredMethods.firstOrNull {
            it.parameterCount == 0 && it.name.substringBefore('$') == "getRecorderReleaseConfirmed"
        } ?: return false
        getter.isAccessible = true
        return getter.invoke(coordinator) as? Boolean ?: false
    }
}
