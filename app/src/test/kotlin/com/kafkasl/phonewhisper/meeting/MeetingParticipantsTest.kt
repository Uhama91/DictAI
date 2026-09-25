package com.kafkasl.phonewhisper.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingParticipantsTest {
    @Test
    fun ignoredParticipantKeepsItsIdentity() {
        val profiles = MeetingParticipants("meeting-a")
        val first = requireNotNull(profiles.observe(6))
        profiles.rename(first.id, "Sophie")
        profiles.setIgnored(first.id, true)

        val returned = requireNotNull(profiles.observe(6))

        assertEquals(first.id, returned.id)
        assertEquals("Sophie", returned.name)
        assertTrue(returned.ignored)
        assertEquals(1, profiles.all().size)
    }

    @Test
    fun unassignedAndUnsupportedChannelsNeverCreateProfiles() {
        val profiles = MeetingParticipants("meeting-a")

        assertNull(profiles.observe(0))
        assertNull(profiles.observe(-1))
        assertNull(profiles.observe(9))

        assertTrue(profiles.all().isEmpty())
    }

    @Test
    fun discoversAtMostEightProfilesInFirstSeenOrder() {
        val profiles = MeetingParticipants("meeting-a")
        profiles.observe(6)
        profiles.observe(2)
        profiles.observe(6)
        listOf(1, 3, 4, 5, 7, 8).forEach(profiles::observe)

        assertNull(profiles.observe(9))
        assertEquals(listOf(6, 2, 1, 3, 4, 5, 7, 8), profiles.all().map { it.channel })
        assertEquals((1..8).toList(), profiles.all().map { it.ordinal })
    }

    @Test
    fun idsAreScopedToTheSessionAndHomonymsRemainDistinct() {
        val profiles = MeetingParticipants("meeting-a")
        val first = requireNotNull(profiles.observe(6))
        val second = requireNotNull(profiles.observe(2))
        profiles.rename(first.id, "Sophie")
        profiles.rename(second.id, "Sophie")

        val nextMeeting = requireNotNull(MeetingParticipants("meeting-b").observe(6))

        assertNotEquals(first.id, second.id)
        assertEquals(listOf("Sophie", "Sophie"), profiles.all().map { it.name })
        assertEquals(1, profiles.all()[0].ordinal)
        assertEquals(2, profiles.all()[1].ordinal)
        assertNotEquals(first.id, nextMeeting.id)
    }

    @Test
    fun namesAreSingleLineUnicodeAndLimitedToEightyCodePoints() {
        val profiles = MeetingParticipants("meeting-a")
        val participant = requireNotNull(profiles.observe(1))
        val input = "\r\nÉlodie\u2028Mēi\nSophie-" + "x".repeat(90)

        profiles.rename(participant.id, input)

        val savedName = requireNotNull(profiles.all().single().name)
        assertEquals("Élodie Mēi Sophie-" + "x".repeat(62), savedName)
        assertEquals(80, savedName.codePointCount(0, savedName.length))
        assertTrue(savedName.none { it == '\r' || it == '\n' || it == '\u2028' })
    }

    @Test
    fun truncatingAtEightyCodePointsDoesNotSplitAnEmoji() {
        val profiles = MeetingParticipants("meeting-a")
        val participant = requireNotNull(profiles.observe(1))
        val input = "a".repeat(79) + "😀" + "b"

        profiles.rename(participant.id, input)

        val savedName = requireNotNull(profiles.all().single().name)
        assertEquals("a".repeat(79) + "😀", savedName)
        assertEquals(80, savedName.codePointCount(0, savedName.length))
    }

    @Test
    fun blankRenameRestoresTheDefaultNameWithoutRemovingTheProfile() {
        val profiles = MeetingParticipants("meeting-a")
        val participant = requireNotNull(profiles.observe(1))
        profiles.rename(participant.id, "Mona")

        profiles.rename(participant.id, "\r\n\t ")

        assertNull(profiles.all().single().name)
        assertEquals(participant.id, profiles.all().single().id)
    }
}
