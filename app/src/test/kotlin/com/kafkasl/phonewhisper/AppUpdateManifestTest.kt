package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdateManifestTest {
    private fun feed(code: Int = 46, url: String = URL, minSdk: Int = 30, id: String = "com.uhama.whisperpin") =
        """{"schemaVersion":1,"android":{"applicationId":"$id","versionCode":$code,"versionName":"0.9.17-dictai","minSdk":$minSdk,"downloadUrl":"$url"}}"""

    @Test fun onlyNewerCompatibleAndroidBuildOffersDownload() {
        assertEquals(URL, AppUpdateManifest.android(feed(), 45, 34)?.downloadUrl)
        assertNull(AppUpdateManifest.android(feed(45), 45, 34))
        assertNull(AppUpdateManifest.android(feed(44), 45, 34))
        assertNull(AppUpdateManifest.android(feed(minSdk = 35), 45, 34))
        assertNull(AppUpdateManifest.android(feed(id = "other.app"), 45, 34))
        assertNull(AppUpdateManifest.android("""{"schemaVersion":1,"macos":{}}""", 45, 34))
    }

    @Test fun refusesUntrustedOrMisleadingDownloadUrls() {
        listOf(URL.replace("https:", "http:"), URL.replace("github.com", "evil.example"),
            URL.replace("/Uhama91/", "/someone/"), URL.replace("github.com", "github.com@evil.example"),
            "$URL?redirect=other", "$URL#other", URL.replace(".apk", ".pkg"),
            URL.replace("/v1/", "/../"), URL.replace("github.com", "github.com:444"),
            URL.replace("/v1/", "/%2e%2e/"), URL.replace("/v1/", "/v1/extra/")).forEach {
            assertThrows(IllegalArgumentException::class.java) { AppUpdateManifest.android(feed(url = it), 45, 34) }
        }
    }

    @Test fun rejectsMalformedOversizedOrUnknownFeeds() {
        listOf("{}", "bad json", feed().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            feed().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"testOnly\":true"),
            feed().replace("\"versionCode\":46", "\"versionCode\":1.5"),
            feed().replace("\"versionCode\":46", "\"versionCode\":\"46\""),
            feed().replace("0.9.17-dictai", "bad\\nlabel"), " ".repeat(65537)).forEach {
            assertThrows(IllegalArgumentException::class.java) { AppUpdateManifest.android(it, 45, 34) }
        }
    }
    @Test fun refusesNonStringVersionLabels() {
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateManifest.android(feed().replace("\"0.9.17-dictai\"", "123"), 45, 34)
        }
    }

    @Test fun scheduleAllowsManualRetryButThrottlesBackgroundFailures() {
        val now = 100_000_000L
        assertTrue(AppUpdateSchedule.due(now, 0, false))
        assertFalse(AppUpdateSchedule.due(now, now - 1_000, true))
        assertTrue(AppUpdateSchedule.due(now, now - 86_400_000, true))
        assertFalse(AppUpdateSchedule.due(now, now - 3_599_999, false))
        assertTrue(AppUpdateSchedule.due(now, now - 3_600_000, false))
        assertTrue(AppUpdateSchedule.due(now, now + 1, true))
    }

    companion object { const val URL = "https://github.com/Uhama91/DictAI/releases/download/v1/DictAI-android.apk" }
}
