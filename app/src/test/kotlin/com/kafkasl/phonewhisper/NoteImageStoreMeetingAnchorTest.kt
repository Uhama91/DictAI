package com.kafkasl.phonewhisper

import android.graphics.Bitmap
import com.kafkasl.phonewhisper.meeting.MeetingImageAnchor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteImageStoreMeetingAnchorTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: NoteImageStore
    private val prefs get() = context.getSharedPreferences("note_capture", android.content.Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        store = NoteImageStore(context)
        store.pending()?.let { store.clearPending(it.id) }
        prefs.edit().clear().commit()
    }

    @Test
    fun meetingBatchPersistsSessionTurnAndUtf16OffsetWhileLegacyBatchStaysLegacy() {
        val anchor = MeetingImageAnchor("session-42", "turn-7", 13)

        val meeting = store.beginMeetingBatch(anchor, NoteImageKind.CAMERA, resume = true, number = 4)

        assertEquals("session-42", meeting.noteId)
        assertEquals(anchor, store.pending()!!.meetingAnchor)
        assertFalse(store.pending()!!.meetingAnchorInvalid)
        store.clearPending(meeting.id)

        val legacy = store.beginBatch(NoteImageKind.CAMERA, resume = false, number = 2, maxImages = 3)
        val decodedLegacy = requireNotNull(NoteImageStore(context).pending())
        assertEquals(legacy.id, decodedLegacy.id)
        assertNull(decodedLegacy.meetingAnchor)
        assertFalse(decodedLegacy.meetingAnchorInvalid)
    }

    @Test
    fun oldSingleCaptureWithoutMeetingMetadataRemainsReadable() {
        val id = "00000000-0000-0000-0000-000000000001"
        prefs.edit().putString(
            "pending",
            JSONObject()
                .put("id", id)
                .put("noteId", "legacy-note")
                .put("number", 1)
                .put("kind", NoteImageKind.CAMERA.name)
                .put("time", 1L)
                .put("resume", false)
                .toString(),
        ).commit()

        val legacy = requireNotNull(store.pending())

        assertEquals("legacy-note", legacy.noteId)
        assertNull(legacy.meetingAnchor)
        assertFalse(legacy.meetingAnchorInvalid)
    }

    @Test
    fun invalidMeetingAnchorRemainsReservedAndCannotBeRewrittenAsLegacyBatch() {
        val id = "00000000-0000-0000-0000-000000000002"
        val raw = JSONObject()
            .put("id", id)
            .put("noteId", "session-42")
            .put("number", 1)
            .put("kind", NoteImageKind.CAMERA.name)
            .put("time", 1L)
            .put("resume", false)
            .put("batch", true)
            .put("meetingCapture", true)
            .put("meetingAnchor", JSONObject().put("sessionId", "session-42").put("offsetUtf16", 3))
            .toString()
        prefs.edit().putString("pending", raw).commit()

        val invalid = requireNotNull(store.pending())

        assertTrue(invalid.meetingAnchorInvalid)
        assertNull(invalid.meetingAnchor)
        assertEquals(raw, prefs.getString("pending", null))
        assertThrows(IllegalStateException::class.java) {
            store.beginBatch(NoteImageKind.CAMERA, resume = false, number = 1, maxImages = 2)
        }
        assertThrows(IllegalStateException::class.java) {
            store.acceptBatch(id)
        }
        assertEquals(raw, prefs.getString("pending", null))

        // Explicit cancellation is the only operation allowed to remove invalid metadata.
        store.clearPending(id)
        assertNull(store.pending())
    }

    @Test
    fun malformedAnchorOnOtherwiseLegacyBatchIsNotDowngraded() {
        val id = "00000000-0000-0000-0000-000000000003"
        prefs.edit().putString(
            "pending",
            JSONObject()
                .put("id", id)
                .put("noteId", "session-42")
                .put("number", 1)
                .put("kind", NoteImageKind.CAMERA.name)
                .put("time", 1L)
                .put("batch", true)
                .put("meetingCapture", true)
                .toString(),
        ).commit()

        val invalid = requireNotNull(store.pending())

        assertTrue(invalid.meetingAnchorInvalid)
        assertNull(invalid.meetingAnchor)
        assertThrows(IllegalStateException::class.java) { store.acceptBatch(id) }
    }

    @Test
    fun resolvedNumberFlagWithoutMeetingAnchorIsNotDowngradedToLegacyCapture() {
        val id = "00000000-0000-0000-0000-000000000005"
        val raw = JSONObject()
            .put("id", id)
            .put("noteId", "session-42")
            .put("number", 1)
            .put("kind", NoteImageKind.CAMERA.name)
            .put("time", 1L)
            .put("batch", true)
            .put("meetingImageNumbersResolved", true)
            .toString()
        prefs.edit().putString("pending", raw).commit()

        val pending = requireNotNull(store.pending())

        assertTrue(pending.meetingAnchorInvalid)
        assertTrue(pending.meetingCapture)
        assertNull(pending.meetingAnchor)
        assertEquals(raw, prefs.getString("pending", null))
        assertThrows(IllegalStateException::class.java) {
            store.beginBatch(NoteImageKind.CAMERA, resume = false, number = 1, maxImages = 2)
        }
    }

    @Test
    fun nonStringSessionOrTurnValuesMakeTheAnchorInvalid() {
        val id = "00000000-0000-0000-0000-000000000004"
        val raw = JSONObject()
            .put("id", id)
            .put("noteId", "42")
            .put("number", 1)
            .put("kind", NoteImageKind.CAMERA.name)
            .put("time", 1L)
            .put("batch", true)
            .put("meetingCapture", true)
            .put(
                "meetingAnchor",
                JSONObject().put("sessionId", 42).put("turnId", 7).put("offsetUtf16", 3),
            )
            .toString()
        prefs.edit().putString("pending", raw).commit()

        val invalid = requireNotNull(store.pending())

        assertTrue(invalid.meetingAnchorInvalid)
        assertNull(invalid.meetingAnchor)
        assertEquals(raw, prefs.getString("pending", null))
    }

    @Test
    fun failedClearThatMutatesPreferencesMemoryRollsBackAndKeepsReservation() {
        val persistence = MemoryMutatingFailurePersistence()
        val injectedStore = NoteImageStore(context, persistence)
        val capture = injectedStore.beginMeetingBatch(
            MeetingImageAnchor("session-42", "turn-7", 3),
            NoteImageKind.CAMERA,
            resume = false,
        )
        val storedReservation = requireNotNull(persistence.durable)
        persistence.failNextCommit = true

        assertThrows(IllegalStateException::class.java) { injectedStore.clearPending(capture.id) }

        assertEquals(storedReservation, persistence.memory)
        assertEquals(storedReservation, persistence.durable)
        assertEquals(capture.id, injectedStore.pending()?.id)
        assertThrows(IllegalStateException::class.java) {
            injectedStore.beginBatch(NoteImageKind.CAMERA, resume = false, number = 1, maxImages = 1)
        }
        injectedStore.clearPending(capture.id)
        assertNull(injectedStore.pending())
        assertNull(persistence.durable)
    }

    @Test
    fun failedFirstReservationWriteRollsBackMutatedPreferencesMemory() {
        val persistence = MemoryMutatingFailurePersistence().apply { failNextCommit = true }
        val injectedStore = NoteImageStore(context, persistence)

        assertThrows(IllegalStateException::class.java) {
            injectedStore.beginMeetingBatch(
                MeetingImageAnchor("session-42", "turn-7", 3),
                NoteImageKind.CAMERA,
                resume = false,
            )
        }

        assertNull(persistence.memory)
        assertNull(persistence.durable)
        assertNull(injectedStore.pending())
    }

    @Test
    fun resolvedMeetingNumbersAreDurableAndSurviveStoreRecreation() {
        val batch = store.beginMeetingBatch(
            MeetingImageAnchor("session-42", "turn-7", 3),
            NoteImageKind.CAMERA,
            resume = false,
        )
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            store.store(batch.id, bitmap, NoteImageKind.CAMERA)
        } finally {
            bitmap.recycle()
        }
        val accepted = store.acceptBatch(batch.id)
        val normalized = accepted.images.map { it.copy(number = 7) }

        val resolved = store.resolveMeetingBatchImages(batch.id, normalized)
        val recovered = requireNotNull(NoteImageStore(context).pending())

        assertTrue(resolved.meetingImageNumbersResolved)
        assertEquals(normalized, resolved.images)
        assertTrue(recovered.meetingImageNumbersResolved)
        assertEquals(normalized, recovered.images)
    }

    @Test
    fun failedMeetingNumberResolutionRollsBackThePendingMapping() {
        val persistence = MemoryMutatingFailurePersistence()
        val injectedStore = NoteImageStore(context, persistence)
        val batch = injectedStore.beginMeetingBatch(
            MeetingImageAnchor("session-42", "turn-7", 3),
            NoteImageKind.CAMERA,
            resume = false,
        )
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            injectedStore.store(batch.id, bitmap, NoteImageKind.CAMERA)
        } finally {
            bitmap.recycle()
        }
        val accepted = injectedStore.acceptBatch(batch.id)
        val before = requireNotNull(persistence.durable)
        persistence.failNextCommit = true

        assertThrows(IllegalStateException::class.java) {
            injectedStore.resolveMeetingBatchImages(batch.id, accepted.images.map { it.copy(number = 7) })
        }

        assertEquals(before, persistence.memory)
        assertEquals(before, persistence.durable)
        assertFalse(requireNotNull(injectedStore.pending()).meetingImageNumbersResolved)
    }

    @Test
    fun cancellingAcceptedInvalidMeetingReservationKeepsItsImageFile() {
        val batch = store.beginMeetingBatch(
            MeetingImageAnchor("session-42", "turn-7", 3),
            NoteImageKind.CAMERA,
            resume = false,
        )
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            store.store(batch.id, bitmap, NoteImageKind.CAMERA)
        } finally {
            bitmap.recycle()
        }
        val accepted = store.acceptBatch(batch.id)
        val image = accepted.images.single()
        val imageFile = store.file(image.id)
        val corrupted = JSONObject(requireNotNull(prefs.getString("pending", null)))
            .put("meetingAnchor", JSONObject().put("sessionId", "session-42").put("offsetUtf16", 3))
        prefs.edit().putString("pending", corrupted.toString()).commit()

        try {
            val invalid = requireNotNull(store.pending())
            assertTrue(invalid.meetingAnchorInvalid)
            assertTrue(invalid.accepted)
            assertTrue(imageFile.isFile)

            store.cancelBatch(batch.id)

            assertNull(store.pending())
            assertTrue("accepted attachments may already be referenced by the saved note", imageFile.isFile)
        } finally {
            store.delete(image.id)
        }
    }

    private class MemoryMutatingFailurePersistence : PendingCapturePersistence {
        var memory: String? = null
        var durable: String? = null
        var failNextCommit = false

        override fun read(): String? = memory

        override fun commit(raw: String?): Boolean {
            memory = raw
            if (failNextCommit) {
                failNextCommit = false
                return false
            }
            durable = raw
            return true
        }
    }
}
