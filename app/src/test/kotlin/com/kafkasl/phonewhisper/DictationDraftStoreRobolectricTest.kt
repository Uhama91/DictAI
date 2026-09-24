package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationDraftStoreRobolectricTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: DictationDraftStore

    @Before fun setUp() {
        context.getSharedPreferences("dictation_draft", 0).edit().clear().commit()
        store = DictationDraftStore(context)
    }

    private fun image(number: Int) = NoteImage(
        "00000000-0000-0000-0000-" + number.toString().padStart(12, '0'),
        number,
        NoteImageKind.SCREENSHOT,
        number.toLong(),
        100,
        100,
    )

    @Test fun deletingAVisibleBlockFreesOneOfTenImageSlotsForANewBatch() {
        val images = (1..10).map(::image)
        images.forEach { image ->
            assertTrue(store.reserveCapture(image.id, 0, 0))
            store.completeCapture(image)
        }
        val remaining = images.drop(1).mapIndexed { index, image ->
            TranscriptImageBlock("group-" + (index + 1), listOf(image), 0, orderAtOffset = index)
        }

        store.syncProjection(remaining)
        assertEquals(9, store.captures().count { it.image != null })
        val next = image(11)
        assertTrue(store.reserveCapture(next.id, 0, 0))
        store.completeCapture(next)

        assertEquals(10, store.captures().count { it.image != null })
        assertEquals(setOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11), store.captures().mapNotNull { it.image?.number }.toSet())
    }

    @Test fun aBatchReplacementIsAllOrNothingAndIdempotentByStableImageIds() {
        (1..9).forEach { number ->
            val image = image(number)
            assertTrue(store.reserveCapture(image.id, 0, 0))
            store.completeCapture(image)
        }
        val sessionId = "ffffffff-ffff-ffff-ffff-000000000001"
        assertTrue(store.reserveCapture(sessionId, 0, 2))

        assertFalse(store.completeBatch(sessionId, listOf(image(10), image(11))))
        assertTrue(store.captures().any { it.id == sessionId && it.image == null })
        assertEquals(9, store.captures().count { it.image != null })

        assertTrue(store.completeBatch(sessionId, listOf(image(10))))
        assertTrue(store.completeBatch(sessionId, listOf(image(10))))
        assertEquals(10, store.captures().count { it.image != null })
        assertFalse(store.captures().any { it.id == sessionId })
    }

    @Test fun idempotentBatchRetryAcceptsImagesAfterNoteReconstructionChangesGroupIdentity() {
        val first = image(1)
        val sessionId = "ffffffff-ffff-ffff-ffff-000000000002"
        assertTrue(store.reserveCapture(sessionId, 0, 2))
        assertTrue(store.completeBatch(sessionId, listOf(first)))

        store.syncProjection(listOf(TranscriptImageBlock("note:${first.id}", listOf(first), 2)))

        assertEquals("note:${first.id}", store.captures().single().groupId)
        assertTrue(store.completeBatch(sessionId, listOf(first)))
        assertEquals(first.id, store.captures().single().image?.id)
    }

    @Test fun oldInvisibleMetadataDoesNotConsumeCapacity() {
        val prefs = context.getSharedPreferences("dictation_draft", 0)
        val captures = org.json.JSONArray().apply {
            (1..10).forEach { number ->
                put(org.json.JSONObject().put("id", "00000000-0000-0000-0000-" + number.toString().padStart(12, '0'))
                    .put("offset", 0).put("visible", number != 1).put("image", NoteImageJson.write(image(number))))
            }
        }
        prefs.edit().putString("captures", captures.toString()).commit()

        assertEquals(9, store.captures().size)
        assertTrue(store.reserveCapture("batch-session", 0, 0))
    }
}
