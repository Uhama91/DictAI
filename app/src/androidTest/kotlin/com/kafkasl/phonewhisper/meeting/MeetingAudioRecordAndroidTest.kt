package com.kafkasl.phonewhisper.meeting

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kafkasl.phonewhisper.BuildConfig
import com.kafkasl.phonewhisper.MainActivity
import com.kafkasl.phonewhisper.TranscriptionMode
import com.kafkasl.phonewhisper.TranscriptionModeCoordinator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real AudioRecord adapter while the isolated prototype activity is visible. */
@RunWith(AndroidJUnit4::class)
class MeetingAudioRecordAndroidTest {
    @Test
    fun visibleActivityCapturesAndStopsTwoBoundedPcmCycles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertTrue("AudioRecord smoke test requires the isolated meeting prototype", BuildConfig.MEETING_PROTOTYPE)
        assertEquals("com.uhama.whisperpin.meetingtest", target.packageName)
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            target.checkSelfPermission(Manifest.permission.RECORD_AUDIO),
        )

        val preferences = target.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
        val modeCoordinator = TranscriptionModeCoordinator.process(target)
        val previousMode = modeCoordinator.snapshot().mode
        val hadOnboardingValue = preferences.contains("onb_complete")
        val oldOnboardingValue = preferences.getBoolean("onb_complete", false)
        var scenario: ActivityScenario<MainActivity>? = null
        var meetingModeSelected = false
        try {
            assertTrue(
                "Meeting mode must be admitted before launching the activity",
                modeCoordinator.changeMode(TranscriptionMode.MEETING),
            )
            meetingModeSelected = true
            check(preferences.edit().putBoolean("onb_complete", true).commit())
            val activeScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activeScenario
            activeScenario.onActivity { activity -> assertFalse(activity.isFinishing) }

            val factory = MeetingAudioRecord(
                readinessCheck = {
                    target.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                },
            )
            val first = captureFewBlocks(factory)
            val second = captureFewBlocks(factory)
            assertTrue(first.blocks >= REQUIRED_BLOCKS)
            assertTrue(second.blocks >= REQUIRED_BLOCKS)
            assertTrue(first.bytes > 0L && second.bytes > 0L)

            Log.i(
                LOG_TAG,
                "status=pass sampleRateHz=$SAMPLE_RATE_HZ " +
                    "cycle1Blocks=${first.blocks} cycle1Bytes=${first.bytes} cycle1Ms=${first.elapsedMs} " +
                    "cycle2Blocks=${second.blocks} cycle2Bytes=${second.bytes} cycle2Ms=${second.elapsedMs} " +
                    "allBuffersEven=true stoppedCycles=2",
            )
        } finally {
            scenario?.close()
            val restore = preferences.edit()
            if (hadOnboardingValue) restore.putBoolean("onb_complete", oldOnboardingValue)
            else restore.remove("onb_complete")
            restore.commit()
            if (meetingModeSelected) {
                check(modeCoordinator.changeMode(previousMode)) {
                    "Could not restore the transcription mode after the AudioRecord smoke test"
                }
            }
        }
    }

    private fun captureFewBlocks(factory: MeetingAudioRecord): CaptureStats {
        val received = CountDownLatch(REQUIRED_BLOCKS)
        val blocks = AtomicInteger()
        val bytes = AtomicLong()
        val malformedBuffer = AtomicBoolean()
        val callbackFailed = AtomicBoolean()
        val capture = factory.create()
        val startedAt = SystemClock.elapsedRealtime()
        var started = false
        var receivedEnough = false
        var stopFinished = false
        try {
            started = capture.start(
                onPcm16 = { buffer, length ->
                    if (length <= 0 || length > buffer.size || length % 2 != 0) {
                        malformedBuffer.set(true)
                    } else {
                        blocks.incrementAndGet()
                        bytes.addAndGet(length.toLong())
                        received.countDown()
                    }
                    true
                },
                onFailure = { callbackFailed.set(true) },
            )
            assertTrue("AudioRecord did not start", started)
            receivedEnough = received.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            stopFinished = try {
                capture.stopAndJoin().get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS) == Unit
            } catch (_: Throwable) {
                false
            }
        }

        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        assertTrue("stopAndJoin must finish after releasing the reader", stopFinished)
        assertTrue("AudioRecord did not deliver enough PCM blocks", receivedEnough)
        assertFalse("AudioRecord delivered an invalid PCM16 byte range", malformedBuffer.get())
        assertFalse("AudioRecord reported a capture failure", callbackFailed.get())
        return CaptureStats(blocks.get(), bytes.get(), elapsedMs)
    }

    private data class CaptureStats(
        val blocks: Int,
        val bytes: Long,
        val elapsedMs: Long,
    )

    private companion object {
        const val LOG_TAG = "MeetingAudioRecordTest"
        const val SAMPLE_RATE_HZ = 16_000
        const val REQUIRED_BLOCKS = 3
        const val CAPTURE_TIMEOUT_SECONDS = 3L
        const val STOP_TIMEOUT_SECONDS = 3L
    }
}
