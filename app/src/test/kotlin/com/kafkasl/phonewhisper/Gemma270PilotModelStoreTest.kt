package com.kafkasl.phonewhisper

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Gemma270PilotModelStoreTest {
    @Test fun verifiedAssetIsPublishedUnderItsRevisionAndCanBeReused() {
        val payload = "gguf-pilot".toByteArray()
        val manifest = manifest(payload)
        val root = Files.createTempDirectory("gemma270-store").toFile()
        try {
            val store = Gemma270PilotModelStore(root, manifest) { ByteArrayInputStream(payload) }
            val first = store.installFromAsset()
            assertNotNull(first)
            assertEquals(payload.size.toLong(), first!!.length())
            assertEquals(first, store.installedModel())
            assertEquals(first, store.installFromAsset())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun badAssetIsRejectedWithoutReplacingThePreviousVerifiedVersion() {
        val oldPayload = "old-gguf".toByteArray()
        val newPayload = "new-gguf".toByteArray()
        val root = Files.createTempDirectory("gemma270-store").toFile()
        try {
            val oldManifest = manifest(oldPayload, revision = "a".repeat(40))
            val oldStore = Gemma270PilotModelStore(root, oldManifest) { ByteArrayInputStream(oldPayload) }
            val oldFile = oldStore.installFromAsset()!!

            val badManifest = manifest(newPayload, revision = "b".repeat(40)).copy(sha256 = "0".repeat(64))
            val badStore = Gemma270PilotModelStore(root, badManifest) { ByteArrayInputStream(newPayload) }
            assertNull(badStore.installFromAsset())
            assertTrue(oldFile.isFile)
            assertEquals(oldPayload.toList(), oldFile.readBytes().toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun manifestRejectsWrongAssetIdentityBeforeCopying() {
        val payload = byteArrayOf(1, 2, 3)
        val manifest = manifest(payload).copy(fileName = "other.gguf")
        val root = Files.createTempDirectory("gemma270-store").toFile()
        try {
            val store = Gemma270PilotModelStore(root, manifest) { ByteArrayInputStream(payload) }
            assertNull(store.installFromAsset())
            assertEquals(0, root.walkTopDown().count { it.isFile })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun manifestUsesTheSharedSnakeCaseSchemaAndRejectsMissingRequiredFields() {
        val payload = byteArrayOf(4, 5, 6)
        val json = """
            {
              "filename": "${Gemma270PilotSupport.MODEL_FILE_NAME}",
              "size_bytes": ${payload.size},
              "sha256": "${sha256(payload)}",
              "label": "Gemma 3 270M IT - V3 post-traitement",
              "base_model": "google/gemma-3-270m-it",
              "base_revision": "${"c".repeat(40)}",
              "adapter_directory_sha256": "${"d".repeat(64)}",
              "adapter_weights_sha256": "${"e".repeat(64)}",
              "instruction_sha256": "${Gemma270PilotSupport.PROMPT_SHA256}",
              "release_url": "https://example.invalid/model.gguf",
              "prompt_version": "v3",
              "language": "fr",
              "format": "corrected",
              "runtime": "${Gemma270PilotSupport.RUNTIME_NAME}",
              "context_size": 8192,
              "max_new_tokens": 4096
            }
        """.trimIndent()
        assertTrue(Gemma270ModelManifest.fromJson(json.toString()).isCompatible())
        val aliases = json.replace(
            "\"base_model\": \"google/gemma-3-270m-it\",",
            "\"model_id\": \"google/gemma-3-270m-it\",",
        )
        assertThrows(Exception::class.java) { Gemma270ModelManifest.fromJson(aliases) }
    }

    private fun manifest(payload: ByteArray, revision: String = "a".repeat(40)) = Gemma270ModelManifest(
        modelId = "google/gemma-3-270m-it",
        modelRevision = revision,
        fileName = Gemma270PilotSupport.MODEL_FILE_NAME,
        sizeBytes = payload.size.toLong(),
        sha256 = sha256(payload),
        promptVersion = "v3",
        promptSha256 = "a95ce1093ba1308bddd5d2006eff73b6a936623261fbbfe6fe54562dfe3cd6f6",
        language = "fr",
        format = "corrected",
        runtime = Gemma270PilotSupport.RUNTIME_NAME,
        contextSize = 8192,
        maxNewTokens = 4096,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
