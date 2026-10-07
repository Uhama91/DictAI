package com.kafkasl.phonewhisper

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingActivityTest {
    @Test fun preferencesExposeInsertionDiagnosisWithoutRequiringAnotherDictation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create(android.os.Bundle().apply { putString("main_screen", "PREFERENCES") })
        try {
            val root = controller.get().findViewById<ViewGroup>(android.R.id.content)
            assertTrue(views(root).filterIsInstance<TextView>().any { it.text == "Dernière insertion" })
        } finally { controller.destroy() }
    }

    @Test fun microphoneModelAndOverlayAllowStartingWithoutOptionalPermissions() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowSettings.setCanDrawOverlays(true)
        // A previously installed, non-recommended ONNX model must remain usable.
        val model = MODEL_CATALOG.first { it.runtimeType == RuntimeModelType.TRANSDUCER }
        val dir = ModelDownloader.modelDir(app, model).apply { mkdirs() }
        listOf("tokens.txt", "encoder.onnx", "decoder.onnx", "joiner.onnx").forEach { File(dir, it).writeText("fixture") }
        val controller = Robolectric.buildActivity(OnboardingActivity::class.java).create().start().resume()
        try {
            val root = controller.get().findViewById<ViewGroup>(android.R.id.content)
            val start = views(root).filterIsInstance<TextView>().single { it.text.contains("copie manuelle") && it.isClickable }
            assertTrue("Optional permissions must not block dictation", start.isEnabled)
            assertNull(InjectionGateway.current())
        } finally { controller.pause().stop().destroy(); ShadowSettings.reset() }
    }

    @Test fun runtimePermissionResultRefreshesTheStepImmediately() {
        val controller = Robolectric.buildActivity(OnboardingActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val before = views(root).filterIsInstance<TextView>().count { it.text == "✓" }
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
            activity.onRequestPermissionsResult(1, arrayOf(Manifest.permission.RECORD_AUDIO), intArrayOf(0))
            assertEquals(before + 1, views(root).filterIsInstance<TextView>().count { it.text == "✓" })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun onboardingRendersAtPhoneAndTabletWidths() {
        listOf(390 to 844, 800 to 1100).forEach { (width, height) ->
            val controller = Robolectric.buildActivity(OnboardingActivity::class.java).create().start().resume()
            try {
                val root = controller.get().findViewById<ViewGroup>(android.R.id.content)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File("build/robolectric-renders/onboarding-$width.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            } finally { controller.pause().stop().destroy() }
        }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
