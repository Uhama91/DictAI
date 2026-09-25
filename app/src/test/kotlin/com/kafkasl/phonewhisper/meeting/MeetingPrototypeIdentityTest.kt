package com.kafkasl.phonewhisper.meeting

import com.kafkasl.phonewhisper.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingPrototypeIdentityTest {
    @Test
    fun buildUsesTheIdentityOfItsSelectedPrototype() {
        when {
            BuildConfig.MEETING_PROTOTYPE -> {
                assertEquals("com.uhama.whisperpin.meetingtest", BuildConfig.APPLICATION_ID)
                assertEquals("0.9.6-dictai-meeting-test", BuildConfig.VERSION_NAME)
            }
            BuildConfig.LOCAL_FORMAT_PROTOTYPE -> {
                assertEquals("com.uhama.whisperpin", BuildConfig.APPLICATION_ID)
                assertEquals("0.9.6-dictai-gemma-test", BuildConfig.VERSION_NAME)
            }
            else -> {
                assertEquals("com.uhama.whisperpin", BuildConfig.APPLICATION_ID)
                assertEquals("0.9.6-dictai", BuildConfig.VERSION_NAME)
            }
        }
    }
}
