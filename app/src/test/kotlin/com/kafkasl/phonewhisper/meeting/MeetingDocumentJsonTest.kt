package com.kafkasl.phonewhisper.meeting

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeetingDocumentJsonTest {
    @Test
    fun `round trip preserves profile order identity text edits and attribution states`() {
        val ignoredAlex = MeetingParticipant(
            id = "session:participant:2", ordinal = 1, channel = 2, name = "Alex", ignored = true,
        )
        val alex = MeetingParticipant(
            id = "session:participant:1", ordinal = 2, channel = 1, name = "Alex",
        )
        val zoe = MeetingParticipant(
            id = "session:participant:3", ordinal = 3, channel = 3, name = "Zoë 🌸",
        )
        val document = MeetingDocument(
            schemaVersion = 1,
            sessionId = "session-α",
            runId = "run-β",
            participants = listOf(ignoredAlex, alex, zoe),
            turns = listOf(
                MeetingTurn(
                    id = "turn-quoted", utteranceId = 7, startMs = 100, endMs = 250,
                    recognizedText = "ligne \"citée\"\nDeuxième 😀",
                    automaticParticipantId = ignoredAlex.id,
                    manualParticipantId = null,
                    hasManualAttribution = true,
                    editedText = "",
                    attributionStable = false,
                ),
                MeetingTurn(
                    id = "turn-known", utteranceId = 8, startMs = 251, endMs = 310,
                    recognizedText = "retour d’Alex",
                    automaticParticipantId = ignoredAlex.id,
                    manualParticipantId = alex.id,
                    hasManualAttribution = true,
                    editedText = null,
                    attributionStable = true,
                ),
            ),
            finished = true,
        )

        val encoded = MeetingDocumentJson.encode(document)
        val decoded = MeetingDocumentJson.decode(encoded)

        assertEquals(MeetingDocumentRead.Ready(document), decoded)
    }

    @Test
    fun `missing payload is an old note and future versions preserve raw json`() {
        assertEquals(MeetingDocumentRead.Absent, MeetingDocumentJson.decode(null))

        val raw = """ { "schemaVersion": 7, "future": { "keep": [1, true] } } """
        assertEquals(MeetingDocumentRead.Unsupported(version = 7, raw = raw), MeetingDocumentJson.decode(raw))
    }

    @Test
    fun `malformed or invalid version payload remains intact as invalid`() {
        val truncated = """{"schemaVersion":1,"sessionId":"broken"""
        assertEquals(MeetingDocumentRead.Invalid(truncated), MeetingDocumentJson.decode(truncated))

        val invalidVersion = documentJson(schemaVersion = 0)
        assertEquals(MeetingDocumentRead.Invalid(invalidVersion), MeetingDocumentJson.decode(invalidVersion))

        val withTrailingText = MeetingDocumentJson.encode(MeetingDocument(sessionId = "session", runId = "run")) + " trailing"
        assertEquals(MeetingDocumentRead.Invalid(withTrailingText), MeetingDocumentJson.decode(withTrailingText))

        val futureWithTrailingText = """{"schemaVersion":7,"future":true} trailing"""
        assertEquals(MeetingDocumentRead.Invalid(futureWithTrailingText), MeetingDocumentJson.decode(futureWithTrailingText))
    }

    @Test
    fun `decode rejects invalid session profiles turns timestamps and references`() {
        val first = participantJson(id = "p1", ordinal = 1, channel = 1)
        val second = participantJson(id = "p2", ordinal = 2, channel = 2)
        val validTurn = turnJson(id = "t1", startMs = 4, endMs = 5, automaticId = "p1")
        val invalidPayloads = listOf(
            documentJson(sessionId = " "),
            documentJson(runId = "\n"),
            documentJson(participants = arrayOf(first, participantJson("p1", 2, 2))),
            documentJson(participants = arrayOf(first, participantJson("p2", 2, 1))),
            documentJson(participants = arrayOf(first, participantJson("p2", 1, 2))),
            documentJson(participants = arrayOf(participantJson("p1", 1, 0))),
            documentJson(participants = arrayOf(first, second, participantJson("p3", 3, 3),
                participantJson("p4", 4, 4), participantJson("p5", 5, 5), participantJson("p6", 6, 6),
                participantJson("p7", 7, 7), participantJson("p8", 8, 8), participantJson("p9", 9, 1))),
            documentJson(turns = arrayOf(validTurn, turnJson(id = "t1", startMs = 6, endMs = 7))),
            documentJson(turns = arrayOf(turnJson(id = "negative-start", startMs = -1, endMs = 1))),
            documentJson(turns = arrayOf(turnJson(id = "negative-end", startMs = 0, endMs = -1))),
            documentJson(turns = arrayOf(turnJson(id = "reversed", startMs = 9, endMs = 8))),
            documentJson(turns = arrayOf(turnJson(id = "dangling-auto", startMs = 0, endMs = 1, automaticId = "missing"))),
            documentJson(participants = arrayOf(first), turns = arrayOf(
                turnJson(id = "dangling-manual", startMs = 0, endMs = 1, manualId = "missing", manual = true),
            )),
        )

        invalidPayloads.forEach { raw ->
            assertEquals(MeetingDocumentRead.Invalid(raw), MeetingDocumentJson.decode(raw))
        }
    }

    @Test
    fun `encode rejects documents outside the version one invariants`() {
        val valid = MeetingDocument(sessionId = "session", runId = "run")
        val invalid = listOf(
            valid.copy(schemaVersion = 2),
            valid.copy(sessionId = " "),
            valid.copy(runId = "\t"),
            valid.copy(participants = listOf(MeetingParticipant("p", 1, 9))),
            valid.copy(turns = listOf(MeetingTurn("t", 1, 2, 1, "text", null))),
        )

        invalid.forEach { document ->
            try {
                MeetingDocumentJson.encode(document)
                fail("Expected invalid document to be rejected: $document")
            } catch (_: IllegalArgumentException) {
                // The public encoder rejects invalid v1 documents.
            }
        }
    }

    private fun documentJson(
        schemaVersion: Int = 1,
        sessionId: String = "session",
        runId: String = "run",
        participants: Array<JSONObject> = emptyArray(),
        turns: Array<JSONObject> = emptyArray(),
    ): String = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("sessionId", sessionId)
        .put("runId", runId)
        .put("participants", JSONArray().apply { participants.forEach { put(it) } })
        .put("turns", JSONArray().apply { turns.forEach { put(it) } })
        .put("finished", false)
        .toString()

    private fun participantJson(id: String, ordinal: Int, channel: Int): JSONObject = JSONObject()
        .put("id", id)
        .put("ordinal", ordinal)
        .put("channel", channel)
        .put("name", JSONObject.NULL)
        .put("ignored", false)

    private fun turnJson(
        id: String,
        startMs: Long,
        endMs: Long,
        automaticId: String? = null,
        manualId: String? = null,
        manual: Boolean = false,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("utteranceId", 1)
        .put("startMs", startMs)
        .put("endMs", endMs)
        .put("recognizedText", "text")
        .put("automaticParticipantId", automaticId ?: JSONObject.NULL)
        .put("manualParticipantId", manualId ?: JSONObject.NULL)
        .put("hasManualAttribution", manual)
        .put("editedText", JSONObject.NULL)
        .put("attributionStable", false)
}
