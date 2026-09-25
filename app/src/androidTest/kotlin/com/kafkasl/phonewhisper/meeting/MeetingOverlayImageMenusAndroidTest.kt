package com.kafkasl.phonewhisper.meeting

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kafkasl.phonewhisper.AndroidTranscriptNoteStorage
import com.kafkasl.phonewhisper.BuildConfig
import com.kafkasl.phonewhisper.MainActivity
import com.kafkasl.phonewhisper.NoteImage
import com.kafkasl.phonewhisper.NoteImageKind
import com.kafkasl.phonewhisper.NoteImageStore
import com.kafkasl.phonewhisper.OverlayService
import com.kafkasl.phonewhisper.TranscriptNotes
import com.kafkasl.phonewhisper.TranscriptionMode
import com.kafkasl.phonewhisper.TranscriptionModeCoordinator
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Captures the production image dialogs and panel after real document mutations. */
@RunWith(AndroidJUnit4::class)
class MeetingOverlayImageMenusAndroidTest {
    @Test
    fun capturesRealImageMenuDestinationsAndMovedThenRemovedDocument() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertTrue("image-menu capture runs only in the isolated meeting prototype", BuildConfig.MEETING_PROTOTYPE)
        assertEquals("com.uhama.whisperpin.meetingtest", target.packageName)
        assertEquals(
            "microphone permission is provisioned externally for the runner",
            android.content.pm.PackageManager.PERMISSION_GRANTED,
            target.checkSelfPermission(Manifest.permission.RECORD_AUDIO),
        )
        assertTrue("overlay permission is provisioned externally for the runner", Settings.canDrawOverlays(target))

        val fixtureId = UUID.randomUUID().toString()
        val coordinator = TranscriptionModeCoordinator.process(target)
        val previousMode = coordinator.snapshot().mode
        assertEquals("the isolated process has no native run before this fixture", null, coordinator.snapshot().activeRunMode)
        assertFalse("a poisoned process cannot safely run this UI fixture", coordinator.snapshot().poisoned)
        val previousOnboarding = target.getSharedPreferences("whisperpin", Context.MODE_PRIVATE).all["onb_complete"]
        val preferences = target.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
        val uiAutomation = instrumentation.uiAutomation
        val originalUiAutomationFlags = requireNotNull(uiAutomation.serviceInfo).flags
        var uiAutomationFlagsChanged = false
        var modeChanged = false
        var factoryInstalled = false
        var serviceStartAttempted = false
        var serviceStopped = false
        var scenario: ActivityScenario<MainActivity>? = null
        val serviceReference = AtomicReference<OverlayService?>()
        var modelStore: MeetingModelStore? = null
        var modelDirectory: File? = null
        val reservationRequests = AtomicInteger()
        val sessionStarts = AtomicInteger()
        val microphoneCreates = AtomicInteger()

        val sessionId = "meeting-image-menu-$fixtureId"
        val runId = "run-$fixtureId"
        val imageId = UUID.randomUUID().toString()
        val captureDirectory = File(
            target.getExternalFilesDir(null) ?: target.filesDir,
            "meeting-image-menu-fixtures-simulated/$fixtureId",
        ).apply { check(mkdirs() || isDirectory) }
        val imageStore = NoteImageStore(target)
        val image = NoteImage(
            id = imageId,
            number = 1,
            kind = NoteImageKind.CAMERA,
            capturedAt = System.currentTimeMillis(),
            width = 320,
            height = 180,
        )
        val document = fixtureDocument(sessionId, runId, image.marker)
        val notes = TranscriptNotes(AndroidTranscriptNoteStorage(target))
        var fixtureNoteSaved = false

        try {
            val interactiveWindowInfo = requireNotNull(uiAutomation.serviceInfo).apply {
                flags = originalUiAutomationFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            uiAutomationFlagsChanged = true
            uiAutomation.setServiceInfo(interactiveWindowInfo)

            target.stopService(Intent(target, OverlayService::class.java))
            assertTrue("a prior isolated overlay service is stopped before installing the one-shot factory", awaitCondition {
                !OverlayService.micArmed
            })

            check(coordinator.changeMode(TranscriptionMode.MEETING)) {
                "meeting mode must be selected before the real service is launched"
            }
            modeChanged = true
            createFixtureImage(imageStore, image)
            val savedNote = notes.saveMeeting(null, document, listOf(image))
            fixtureNoteSaved = true
            assertEquals(sessionId, savedNote.id)

            val draftDirectory = File(target.filesDir, "meeting-image-menu-drafts/$fixtureId")
                .apply { check(mkdirs() || isDirectory) }
            val draftFile = File(draftDirectory, "meeting.json")
            val modelRoot = File(target.filesDir, "meeting-image-menu-models/$fixtureId")
                .apply { check(mkdirs() || isDirectory) }
            modelDirectory = modelRoot
            val fixtureModels = MeetingModelStore(filesDirectory = modelRoot)
            modelStore = fixtureModels

            val overrides = OverlayService.MeetingTestOverrides(
                draftOwnership = MeetingDraftOwnership(),
                draftFile = draftFile,
                modelStore = fixtureModels,
                modelAvailability = MeetingModelAvailabilityPort { MeetingModelAvailability.MISSING },
                reservation = MeetingNativeReservationPort {
                    reservationRequests.incrementAndGet()
                    error("opening a saved document must not reserve a native meeting run")
                },
                sessionFactory = object : MeetingSessionFactoryPort {
                    override fun start(
                        runId: String,
                        language: String,
                        onReady: () -> Unit,
                        onUpdate: (MeetingHypothesis) -> Unit,
                        onFailure: (String) -> Unit,
                    ): MeetingSession {
                        sessionStarts.incrementAndGet()
                        error("this fixture must not start a native session")
                    }
                },
                microphoneFactory = MeetingMicrophoneFactoryPort {
                    microphoneCreates.incrementAndGet()
                    error("this fixture must not create an audio recorder")
                },
            )
            factoryInstalled = OverlayService.setMeetingTestOverridesFactoryForTest { service ->
                serviceReference.set(service)
                overrides
            }
            assertTrue("the one-shot overrides are installed before service creation", factoryInstalled)

            check(preferences.edit().putBoolean("onb_complete", true).commit())
            val activeScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activeScenario
            activeScenario.onActivity { activity ->
                serviceStartAttempted = true
                activity.startForegroundService(
                    Intent(activity, OverlayService::class.java).setAction(OverlayService.ACTION_ARM_MIC),
                )
            }
            assertTrue("the test factory receives the actual OverlayService", awaitCondition {
                serviceReference.get() != null
            })
            assertTrue("the real service exposes interactive overlay windows", awaitCondition {
                uiAutomation.windows.isNotEmpty()
            })

            val service = requireNotNull(serviceReference.get())
            activeScenario.onActivity {
                val openNote = OverlayService::class.java.getDeclaredMethod(
                    "openNote",
                    com.kafkasl.phonewhisper.TranscriptNote::class.java,
                ).apply { isAccessible = true }
                openNote.invoke(service, savedNote)
            }

            val imageActionLabel = "Ouvrir les actions pour photo 1"
            awaitVisibleLabel(instrumentation, imageActionLabel)
            assertEquals(0, reservationRequests.get())
            assertEquals(0, sessionStarts.get())
            assertEquals(0, microphoneCreates.get())
            capture(instrumentation, captureDirectory, "01-meeting-panel-before.png")

            clickVisibleLabel(instrumentation, imageActionLabel)
            awaitVisibleLabel(instrumentation, "Image 1 · Photo")
            awaitVisibleLabel(instrumentation, "Ouvrir")
            awaitVisibleLabel(instrumentation, "Déplacer après un passage…")
            awaitVisibleLabel(instrumentation, "Retirer cette image")
            capture(instrumentation, captureDirectory, "02-image-actions-menu.png")

            clickVisibleLabel(instrumentation, "Déplacer après un passage…")
            awaitVisibleLabel(instrumentation, "Déplacer après un passage")
            awaitVisibleFragment(instrumentation, "Karim")
            capture(instrumentation, captureDirectory, "03-image-destinations.png")
            val destinationLabel = "Karim · Passage de Karim pour destination."
            awaitVisibleLabel(instrumentation, destinationLabel)
            clickVisibleLabel(instrumentation, destinationLabel)

            assertTrue("the durable note places the marker in Karim's visible turn", awaitCondition {
                val current = TranscriptNotes(AndroidTranscriptNoteStorage(target)).get(sessionId)?.meeting ?: return@awaitCondition false
                val destination = current.turns.singleOrNull { it.id == "turn-karim" } ?: return@awaitCondition false
                destination.recognizedText.contains(image.marker) || destination.editedText?.contains(image.marker) == true
            })
            assertTrue("the attachment remains linked after movement", awaitCondition {
                TranscriptNotes(AndroidTranscriptNoteStorage(target)).get(sessionId)?.images?.any { it.id == imageId } == true
            })
            awaitVisibleLabel(instrumentation, imageActionLabel)
            capture(instrumentation, captureDirectory, "04-panel-after-move.png")

            clickVisibleLabel(instrumentation, imageActionLabel)
            awaitVisibleLabel(instrumentation, "Retirer cette image")
            capture(instrumentation, captureDirectory, "05-remove-image-menu.png")
            clickVisibleLabel(instrumentation, "Retirer cette image")

            assertTrue("removal is durably reflected in the structured note", awaitCondition {
                val note = TranscriptNotes(AndroidTranscriptNoteStorage(target)).get(sessionId) ?: return@awaitCondition false
                note.images.none { it.id == imageId } &&
                    note.meeting?.turns?.none { (it.editedText ?: it.recognizedText).contains(image.marker) } == true
            })
            assertTrue("removed image bytes are deleted after durable note mutation", awaitCondition {
                !imageStore.file(imageId).exists() && !imageStore.thumbnail(imageId).exists()
            })
            assertTrue("the real panel no longer exposes the removed image", awaitCondition {
                !visibleLabelExists(instrumentation, imageActionLabel)
            })
            capture(instrumentation, captureDirectory, "06-panel-after-remove.png")

            assertEquals("menu, move and remove stay document-only", 0, reservationRequests.get())
            assertEquals("the native meeting engine remains unused", 0, sessionStarts.get())
            assertEquals("the device microphone remains unused", 0, microphoneCreates.get())
            android.util.Log.i(
                TAG,
                "CAPTURE_SET=${captureDirectory.absolutePath} files=6 synthetic=true realService=true " +
                    "networkDownload=false reservations=${reservationRequests.get()} sessions=${sessionStarts.get()} " +
                    "microphones=${microphoneCreates.get()}",
            )
        } finally {
            if (factoryInstalled) OverlayService.clearMeetingTestOverridesFactoryForTest()
            if (serviceStartAttempted) {
                target.stopService(Intent(target, OverlayService::class.java))
                serviceStopped = awaitCondition {
                    serviceReference.get() == null || !OverlayService.micArmed
                }
            } else {
                serviceStopped = true
            }
            scenario?.close()
            if (uiAutomationFlagsChanged) {
                val restored = requireNotNull(uiAutomation.serviceInfo).apply { flags = originalUiAutomationFlags }
                uiAutomation.setServiceInfo(restored)
            }
            if (modeChanged && serviceStopped) check(coordinator.changeMode(previousMode))
            if (previousOnboarding == null) preferences.edit().remove("onb_complete").commit()
            else preferences.edit().putBoolean("onb_complete", previousOnboarding as Boolean).commit()
            if (serviceStopped) {
                if (fixtureNoteSaved) TranscriptNotes(AndroidTranscriptNoteStorage(target)).delete(sessionId)
                runCatching { imageStore.delete(imageId) }
                modelStore?.shutdownForTests()
                modelDirectory?.deleteRecursively()
            }
        }
    }

    private fun fixtureDocument(sessionId: String, runId: String, marker: String) = MeetingDocument(
        sessionId = sessionId,
        runId = runId,
        participants = listOf(
            MeetingParticipant(id = "profile-sophie", ordinal = 1, channel = 1, name = "Sophie"),
            MeetingParticipant(id = "profile-karim", ordinal = 2, channel = 2, name = "Karim"),
        ),
        turns = listOf(
            MeetingTurn(
                id = "turn-sophie",
                utteranceId = 1L,
                startMs = 0L,
                endMs = 1_000L,
                recognizedText = "Premier passage de Sophie.\n\n$marker",
                automaticParticipantId = "profile-sophie",
                attributionStable = true,
            ),
            MeetingTurn(
                id = "turn-karim",
                utteranceId = 2L,
                startMs = 1_200L,
                endMs = 2_400L,
                recognizedText = "Passage de Karim pour destination.",
                automaticParticipantId = "profile-karim",
                attributionStable = true,
            ),
        ),
        finished = true,
    )

    private fun createFixtureImage(store: NoteImageStore, image: NoteImage) {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(224, 232, 220))
        }
        try {
            listOf(store.file(image.id), store.thumbnail(image.id)).forEach { file ->
                file.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
                FileOutputStream(file).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun clickVisibleLabel(instrumentation: android.app.Instrumentation, label: String) {
        val node = awaitVisibleNode(instrumentation) { node -> node.hasLabel(label) }
        try {
            assertTrue("visible real Android action is clickable: $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } finally {
            node.recycle()
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100L)
    }

    private fun awaitVisibleLabel(instrumentation: android.app.Instrumentation, label: String) {
        awaitVisibleNode(instrumentation) { node -> node.hasLabel(label) }.recycle()
    }

    private fun awaitVisibleFragment(instrumentation: android.app.Instrumentation, fragment: String) {
        awaitVisibleNode(instrumentation) { node -> node.hasFragment(fragment) }.recycle()
    }

    private fun awaitVisibleNode(
        instrumentation: android.app.Instrumentation,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val result = AtomicReference<AccessibilityNodeInfo?>()
        assertTrue("expected accessibility node appears within eight seconds", awaitCondition {
            result.getAndSet(null)?.recycle()
            result.set(findVisibleNode(instrumentation, predicate))
            result.get() != null
        })
        return requireNotNull(result.get())
    }

    private fun findVisibleNode(
        instrumentation: android.app.Instrumentation,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        for (window in instrumentation.uiAutomation.windows) {
            val root = try { window.root } catch (_: Throwable) { null } ?: continue
            try {
                findNodeRecursive(root, predicate)?.let { return it }
            } finally {
                root.recycle()
                window.recycle()
            }
        }
        return null
    }

    private fun visibleLabelExists(instrumentation: android.app.Instrumentation, label: String): Boolean {
        val node = findVisibleNode(instrumentation) { it.hasLabel(label) } ?: return false
        node.recycle()
        return true
    }

    private fun findNodeRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && predicate(node)) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = try { node.getChild(index) } catch (_: Throwable) { null } ?: continue
            val found = try { findNodeRecursive(child, predicate) } finally { child.recycle() }
            if (found != null) return found
        }
        return null
    }

    private fun AccessibilityNodeInfo.hasLabel(label: String): Boolean =
        text?.toString()?.trim()?.equals(label, ignoreCase = true) == true ||
            contentDescription?.toString()?.trim()?.equals(label, ignoreCase = true) == true

    private fun AccessibilityNodeInfo.hasFragment(fragment: String): Boolean =
        text?.toString()?.contains(fragment, ignoreCase = true) == true ||
            contentDescription?.toString()?.contains(fragment, ignoreCase = true) == true

    private fun capture(instrumentation: android.app.Instrumentation, directory: File, name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(180L)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(directory, name)
        try {
            FileOutputStream(file).use { output ->
                assertTrue("native Android frame is encoded as PNG", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        assertTrue("captured UI image is non-empty", file.length() > 10_000L)
        android.util.Log.i(TAG, "CAPTURE=${file.absolutePath} bytes=${file.length()}")
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(8)
        while (SystemClock.uptimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            SystemClock.sleep(40L)
        }
        return runCatching(condition).getOrDefault(false)
    }

    private companion object {
        const val TAG = "MeetingImageMenusFixture"
    }
}
