package com.kafkasl.phonewhisper

import android.content.Context
import com.kafkasl.phonewhisper.meeting.MeetingModelStore
import com.kafkasl.phonewhisper.meeting.MeetingModelStoreState
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient

/** Isolated local store fixture; any HTTP request is counted and fails without using the network. */
internal class MeetingActivityStoreFixture(
    context: Context,
    private val blockRequests: Boolean = false,
    private val releaseRequest: CountDownLatch = CountDownLatch(if (blockRequests) 1 else 0),
) : AutoCloseable {
    val requests = AtomicInteger()
    val requestEntered = CountDownLatch(1)
    private val directory = File(context.cacheDir, "meeting-activity-${UUID.randomUUID()}")
    val store = MeetingModelStore(
        filesDirectory = directory,
        httpClient = OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet()
            requestEntered.countDown()
            if (blockRequests) releaseRequest.await(5, TimeUnit.SECONDS)
            throw IOException("isolated meeting activity fixture")
        }.build(),
        availableBytes = { Long.MAX_VALUE },
        spaceMarginBytes = 0L,
    )

    fun observeStates(): StateRecorder = StateRecorder(store)

    fun releasePendingRequest() = releaseRequest.countDown()

    override fun close() {
        releaseRequest.countDown()
        store.shutdownForTests()
        directory.takeIf(File::exists)?.deleteRecursively()
    }

    class StateRecorder internal constructor(private val store: MeetingModelStore) : AutoCloseable {
        private val states = LinkedBlockingQueue<MeetingModelStoreState>()
        private val listener: (MeetingModelStoreState) -> Unit = { states.offer(it) }

        init {
            store.addListener(listener)
        }

        fun awaitInspection(timeout: Long = 3, unit: TimeUnit = TimeUnit.SECONDS): Boolean {
            val deadline = System.nanoTime() + unit.toNanos(timeout)
            var checkingSeen = false
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                val state = states.poll(remaining, TimeUnit.NANOSECONDS) ?: return false
                if (state is MeetingModelStoreState.Checking) checkingSeen = true
                else if (checkingSeen) return true
            }
        }

        fun awaitState(timeout: Long = 3, unit: TimeUnit = TimeUnit.SECONDS,
                       predicate: (MeetingModelStoreState) -> Boolean): MeetingModelStoreState? {
            val deadline = System.nanoTime() + unit.toNanos(timeout)
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return null
                val state = states.poll(remaining, TimeUnit.NANOSECONDS) ?: return null
                if (predicate(state)) return state
            }
        }

        override fun close() = store.removeListener(listener)
    }
}
