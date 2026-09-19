package com.kafkasl.phonewhisper

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pilot-only smoke test: verifies the packaged asset, the real JNI greedy API,
 * complete-result transport, and the native-start callback on an arm64 device.
 * It deliberately makes no claim about semantic cleanup quality.
 */
@RunWith(AndroidJUnit4::class)
class Gemma270PilotNativeIntegrationTest {
    @Test fun oneEngineGivesEachFormattingSessionItsOwnCancellationOwner() {
        if (!BuildConfig.GEMMA270_PILOT) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LocalFormatEngine(context).use { engine ->
            assertNotSame(engine.backend(), engine.backend())
        }
    }

    @Test fun packagedPilotModelGeneratesACompleteResultThroughTheFacade() {
        if (!BuildConfig.GEMMA270_PILOT) return
        assumeTrue("Gemma pilot requires arm64-v8a", Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Gemma270PilotModelStore(context)
        val installed = store.installFromAsset()
        assertNotNull("The pilot GGUF must be packaged and pass its manifest hash", installed)

        val request = LocalFormatRequest(
            text = "bonjour DictAI",
            instructions = "ignored by the V3 pilot",
            language = "français",
            protectedTerms = listOf("DictAI"),
            layoutKind = LocalLayoutKind.TEXT,
            isGemma270Pilot = true,
        )
        val executor = Executors.newSingleThreadExecutor()
        val chunks = mutableListOf<String>()
        var nativeStarted = false
        try {
            LocalFormatEngine(context).use { engine ->
                val future = executor.submit<String?> {
                    engine.backend().generate(request, chunks::add, { nativeStarted = true })
                }
                val output = future.get(70L, TimeUnit.SECONDS)
                assertTrue("The pilot must report native entry", nativeStarted)
                assertFalse("A complete pilot result must not be empty", output.isNullOrBlank())
                assertNotNull("The completed output must pass transport and marker checks", request.acceptOutput(output))
                assertTrue("Pilot streaming chunks stay private until a complete result", chunks.isEmpty())
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
