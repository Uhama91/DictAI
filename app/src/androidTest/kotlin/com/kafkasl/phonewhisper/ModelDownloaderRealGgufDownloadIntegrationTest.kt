package com.kafkasl.phonewhisper

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelDownloaderRealGgufDownloadIntegrationTest {
    @Test
    fun downloads_the_catalogued_gguf_directly_when_explicitly_enabled() {
        assumeTrue(
            "Set -e $REAL_DOWNLOAD_ARGUMENT true to run the real GGUF download integration test.",
            InstrumentationRegistry.getArguments().getString(REAL_DOWNLOAD_ARGUMENT) == "true",
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = MODEL_CATALOG.single { it.runtimeType == RuntimeModelType.GGUF }
        val artifact = requireNotNull(model.directArtifact)
        val modelDir = ModelDownloader.modelDir(context, model)
        removeTargetModel(modelDir, context, model)

        val states = CopyOnWriteArrayList<DownloadState>()
        val terminalState = CountDownLatch(1)
        try {
            ModelDownloader.download(context, model) { state ->
                states += state
                if (state is DownloadState.Done || state is DownloadState.Error) {
                    terminalState.countDown()
                }
            }

            assertTrue(
                "GGUF download did not reach a terminal state within $DOWNLOAD_TIMEOUT_MINUTES minutes.",
                terminalState.await(DOWNLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES),
            )

            val recordedStates = states.toList()
            Log.i(TAG, "Recorded ${recordedStates.size} download states: ${stateSummary(recordedStates)}")
            assertTrue("GGUF download emitted no states.", recordedStates.isNotEmpty())
            assertTrue(
                "GGUF download failed: ${recordedStates.filterIsInstance<DownloadState.Error>()}",
                recordedStates.none { it is DownloadState.Error },
            )
            assertEquals(DownloadState.Done, recordedStates.last())
            assertFalse(
                "A direct GGUF download must not extract an archive.",
                recordedStates.any { it is DownloadState.Extracting },
            )

            val directProgress = recordedStates.filterIsInstance<DownloadState.Downloading>()
            assertTrue("GGUF download emitted no direct progress.", directProgress.isNotEmpty())
            assertTrue("GGUF progress must be marked direct.", directProgress.all { it.isDirect })
            assertTrue("GGUF progress must stay within 0..1.", directProgress.all { it.progress in 0f..1f })
            assertTrue(
                "GGUF direct progress must emit at most one callback per whole percent: ${directProgress.size}",
                directProgress.size <= 101,
            )
            assertTrue(
                "GGUF direct progress must be monotone: ${directProgress.map { it.progress }}",
                directProgress.zipWithNext().all { (previous, next) -> next.progress >= previous.progress },
            )
            assertEquals(1f, directProgress.last().progress)

            val downloadedFile = File(modelDir, artifact.fileName)
            assertTrue("The catalogued GGUF file is missing: ${artifact.fileName}", downloadedFile.isFile)
            assertEquals(artifact.fileName, downloadedFile.name)
            assertEquals(artifact.expectedSizeBytes, downloadedFile.length())
            assertTrue("The completed GGUF model must be installed.", ModelDownloader.isInstalled(context, model))
        } finally {
            removeTargetModel(modelDir, context, model)
        }
    }

    private fun removeTargetModel(modelDir: File, context: android.content.Context, model: Model) {
        if (!modelDir.exists()) return
        assertTrue("Unable to remove only the GGUF model directory before/after the test.", ModelDownloader.delete(context, model))
        assertFalse("GGUF model directory remains after cleanup.", modelDir.exists())
    }

    private fun stateSummary(states: List<DownloadState>): String {
        val progress = states.filterIsInstance<DownloadState.Downloading>()
        return "downloading=${progress.size}, extracting=${states.count { it is DownloadState.Extracting }}, " +
            "done=${states.count { it is DownloadState.Done }}, errors=${states.count { it is DownloadState.Error }}, " +
            "firstProgress=${progress.firstOrNull()?.progress}, lastProgress=${progress.lastOrNull()?.progress}"
    }

    private companion object {
        const val TAG = "ModelDownloaderGgufIT"
        const val REAL_DOWNLOAD_ARGUMENT = "dictaiRealGgufDownload"
        const val DOWNLOAD_TIMEOUT_MINUTES = 30L
    }
}
