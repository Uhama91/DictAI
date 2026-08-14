package com.kafkasl.phonewhisper

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
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

        val result = coordinator.stopJoinRelease(timeoutMs = 1)

        assertEquals(RecordingStopCoordinator.Result.Stopped, result)
        assertEquals(listOf("stop", "release", "snapshot"), events)
    }

    @Test
    fun timed_out_join_defers_release_and_snapshot_until_the_blocked_reader_exits() {
        val unblock = CountDownLatch(1)
        val started = CountDownLatch(1)
        val events = mutableListOf<String>()
        val recordThread = Thread {
            started.countDown()
            unblock.await()
        }.apply { start() }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        val coordinator = RecordingStopCoordinator(
            recordThread = recordThread,
            stopRecorder = { events += "stop" },
            releaseRecorder = { events += "release" },
            snapshot = { events += "snapshot" },
        )

        val result = coordinator.stopJoinRelease(timeoutMs = 1)

        assertEquals(RecordingStopCoordinator.Result.TimedOut, result)
        assertEquals(listOf("stop"), events)
        val cleanupResult = AtomicReference<RecordingStopCoordinator.Result>()
        val cleanupDone = CountDownLatch(1)
        Thread {
            cleanupResult.set(coordinator.awaitExitThenRelease())
            cleanupDone.countDown()
        }.start()
        assertEquals(listOf("stop"), events)
        unblock.countDown()
        assertTrue(cleanupDone.await(1, TimeUnit.SECONDS))
        assertEquals(RecordingStopCoordinator.Result.Stopped, cleanupResult.get())
        assertEquals(listOf("stop", "release", "snapshot"), events)
    }
}
