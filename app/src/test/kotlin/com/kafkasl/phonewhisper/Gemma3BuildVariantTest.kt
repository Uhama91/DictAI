package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Gemma3BuildVariantTest {
    @Test
    fun gemma3VariantHasItsConditionalNoticeAndNeverBundlesModelWeights() {
        val context = RuntimeEnvironment.getApplication()
        val noticePresent = runCatching {
            context.assets.open("local-format/gemma3-repair-pilot-NOTICE.txt").use { it.read() }
        }.isSuccess

        assertEquals("NOTICE must be limited to the G3 pilot variant", BuildConfig.GEMMA3_REPAIR_PILOT, noticePresent)
        if (BuildConfig.GEMMA3_REPAIR_PILOT) {
            assertTrue(BuildConfig.LOCAL_FORMAT_PROTOTYPE)
            assertEquals("0.9.11-dictai-gemma3-test", BuildConfig.VERSION_NAME)
            assertEquals(40, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt())
        }
        assertFalse("Model weights are installed after download, never bundled as assets", hasBundledWeight(context.assets, ""))
    }

    private fun hasBundledWeight(assets: android.content.res.AssetManager, path: String): Boolean =
        assets.list(path).orEmpty().any { name ->
            val child = if (path.isEmpty()) name else "$path/$name"
            when {
                name.endsWith(".gguf", ignoreCase = true) || name.endsWith(".litertlm", ignoreCase = true) -> true
                runCatching { assets.list(child) }.getOrNull()?.isNotEmpty() == true -> hasBundledWeight(assets, child)
                else -> false
            }
        }
}
