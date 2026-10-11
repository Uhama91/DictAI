package com.kafkasl.phonewhisper

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import okhttp3.ResponseBody.Companion.toResponseBody

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdateCheckerTest {
    private val feed = """{"schemaVersion":1,"android":{"applicationId":"com.uhama.whisperpin","versionCode":100,"versionName":"1.0","minSdk":30,"downloadUrl":"${AppUpdateManifestTest.URL}"}}"""
    @Test fun networkBodyAcceptsShortFeedAndRejectsOversizedFeed() {
        assertEquals(feed, AppUpdateChecker.readManifestBody(feed.toResponseBody()))
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateChecker.readManifestBody(" ".repeat(65537).toResponseBody())
        }
    }
    @Test fun cachesOfferThrottlesChecksAndRemovesOfferAfterInstalledUpdate() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("update-test", 0)
        prefs.edit().clear().commit()
        var now = 100_000_000L
        var requests = 0
        val checker = AppUpdateChecker(prefs, 45, 34, { now }, Executor { it.run() }) { requests++; feed }
        checker.check()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AppUpdateManifestTest.URL, checker.offer?.downloadUrl)
        checker.check()
        assertEquals(1, requests)
        checker.check(force = true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, requests)
        val restored = AppUpdateChecker(prefs, 45, 34, { now }, Executor { it.run() }) { feed }
        assertEquals("1.0", restored.offer?.version)
        val updated = AppUpdateChecker(prefs, 100, 34, { now }, Executor { it.run() }) { feed }
        assertNull(updated.offer)
        now += 86_400_000
        checker.check()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, requests)
    }
    @Test fun networkFailureNeverClaimsCurrentAndRetainsPreviousOffer() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("update-failure-test", 0)
        prefs.edit().clear().commit()
        var fail = false
        val checker = AppUpdateChecker(prefs, 45, 34, { 100_000_000L }, Executor { it.run() }) {
            if (fail) throw java.io.IOException("offline") else feed
        }
        checker.check(force = true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        fail = true
        checker.check(force = true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AppUpdateChecker.Status.UNAVAILABLE, checker.status)
        assertEquals("1.0", checker.offer?.version)
    }
}
