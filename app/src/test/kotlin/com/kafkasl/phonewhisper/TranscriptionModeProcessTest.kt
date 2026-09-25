package com.kafkasl.phonewhisper

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptionModeProcessTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)

    @Before
    fun resetProcessCoordinator() {
        TranscriptionModeCoordinator.clearProcessForTest()
        prefs.edit().clear().commit()
    }

    @After
    fun clearProcessCoordinator() {
        TranscriptionModeCoordinator.clearProcessForTest()
        prefs.edit().clear().commit()
    }

    @Test
    fun `process singleton reads application preference once and poison survives new accessors`() {
        prefs.edit().putString("transcription_mode", TranscriptionMode.MEETING.preferenceValue).commit()
        val firstOwner = TranscriptionModeCoordinator.process(context)
        assertEquals(TranscriptionMode.MEETING, firstOwner.snapshot().mode)

        prefs.edit().putString("transcription_mode", TranscriptionMode.DICTATION.preferenceValue).commit()
        val recreatedServiceOwner = TranscriptionModeCoordinator.process(context)
        assertSame(firstOwner, recreatedServiceOwner)
        assertEquals(TranscriptionMode.MEETING, recreatedServiceOwner.snapshot().mode)

        firstOwner.reportUncertainClose()
        assertSame(firstOwner, TranscriptionModeCoordinator.process(context))
        assertEquals(true, TranscriptionModeCoordinator.process(context).snapshot().poisoned)
    }
}
