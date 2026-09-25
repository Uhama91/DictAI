package com.kafkasl.phonewhisper

import android.app.Activity
import android.animation.ValueAnimator
import android.os.Looper
import android.provider.Settings
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CursiveWaveMeetingModeTest {
    private fun wave() = CursiveWaveView(RuntimeEnvironment.getApplication())

    @Test
    fun meetingCrownHasSixClosedLoopsInTheCenteredSquareWithOpenCenter() {
        val view = wave()
        view.setMeetingMode(true)

        val geometry = view.meetingSpiralGeometryForTest(widthPx = 120f, heightPx = 56f)
        val drawnPath = view.meetingSpiralPathPointsForTest(widthPx = 120f, heightPx = 56f)

        assertEquals(6, geometry.loopCount)
        assertEquals(6, geometry.loopCenters.size)
        assertTrue("the handwritten crown forms a closed path", geometry.isClosed)
        assertEquals(60f, geometry.centerX, 0.01f)
        assertEquals(28f, geometry.centerY, 0.01f)
        assertTrue("the crown is centered in the available square", geometry.outerRadiusPx * 2f <= 56f)
        assertTrue("the middle remains visibly open", geometry.innerRadiusPx > geometry.strokeWidthPx / 2f)
        assertEquals("meeting loops never translate horizontally", 0f, geometry.horizontalTranslationPx, 0.001f)
        assertTrue("tests inspect the path used by onDraw", drawnPath.size > 12)
        assertEquals(drawnPath[1], drawnPath[drawnPath.size - 2], 0.5f)
        assertEquals(drawnPath[2], drawnPath[drawnPath.size - 1], 0.5f)
        var nearestPathRadius = Float.MAX_VALUE
        var farthestPathRadius = 0f
        for (index in 0 until drawnPath.size / 3) {
            val point = index * 3
            val dx = drawnPath[point + 1] - geometry.centerX
            val dy = drawnPath[point + 2] - geometry.centerY
            val radius = kotlin.math.sqrt(dx * dx + dy * dy)
            nearestPathRadius = minOf(nearestPathRadius, radius)
            farthestPathRadius = maxOf(farthestPathRadius, radius)
        }
        assertTrue("the actual stroke path leaves its center open", nearestPathRadius > geometry.strokeWidthPx / 2f)
        assertTrue(
            "the actual path and stroke stay inside the centered square",
            (farthestPathRadius + geometry.strokeWidthPx / 2f) * 2f <= 56f,
        )
        geometry.loopCenters.forEach { center ->
            val dx = center.x - geometry.centerX
            val dy = center.y - geometry.centerY
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            assertTrue("every loop stays on the circular crown", kotlin.math.abs(distance - geometry.loopCenterRadiusPx) < 0.1f)
        }
    }

    @Test
    fun silentCaptureRotatesAndPauseResumePreservesTheSameAngle() {
        val view = wave()
        view.setMeetingMode(true)
        view.setLevel(0f)
        val restAngle = view.meetingSpiralSnapshotForTest().rotationDegrees

        view.setMeetingCaptureActive(true)
        view.advanceMeetingForTest(1f)
        val afterOneSecondOfSilence = view.meetingSpiralSnapshotForTest().rotationDegrees
        assertEquals(112.5f, afterOneSecondOfSilence, 0.5f)
        assertTrue("the active microphone animates even with no voice energy", afterOneSecondOfSilence != restAngle)

        view.setMeetingCaptureActive(false)
        view.advanceMeetingForTest(1f)
        assertEquals(afterOneSecondOfSilence, view.meetingSpiralSnapshotForTest().rotationDegrees, 0.01f)

        view.setMeetingCaptureActive(true)
        view.advanceMeetingForTest(1f)
        assertEquals(225f, view.meetingSpiralSnapshotForTest().rotationDegrees, 0.5f)
    }

    @Test
    fun reducedMotionKeepsTheCrownFixedWithoutSchedulingAFrame() {
        val context = RuntimeEnvironment.getApplication()
        val oldScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        try {
            Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val view = CursiveWaveView(activity)
            activity.setContentView(view)
            view.setMeetingMode(true)
            view.setMeetingCaptureActive(true)
            view.advanceMeetingForTest(1f)

            val snapshot = view.meetingSpiralSnapshotForTest()
            assertTrue("the reduced-motion crown remains visible", view.visibility == View.VISIBLE)
            assertEquals(6, view.meetingSpiralGeometryForTest(120f, 56f).loopCount)
            assertEquals(0f, snapshot.rotationDegrees, 0.01f)
            assertFalse("reduced motion schedules no animation frame", snapshot.frameScheduled)
            activity.finish()
        } finally {
            Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, oldScale)
        }
    }

    @Test
    fun hiddenOrDetachedMeetingViewHasNoScheduledFrame() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CursiveWaveView(activity)
        attachVisibleMeetingView(activity, view)
        view.setMeetingMode(true)
        assertVisibleMeetingPreconditions(view)
        view.setMeetingCaptureActive(true)
        assertTrue("the visible attached view schedules its active spiral", view.meetingSpiralSnapshotForTest().frameScheduled)

        view.visibility = View.GONE
        assertFalse("a hidden view cancels its frame callback", view.meetingSpiralSnapshotForTest().frameScheduled)
        view.visibility = View.VISIBLE
        assertTrue("a visible active view resumes scheduling", view.meetingSpiralSnapshotForTest().frameScheduled)

        activity.setContentView(View(activity))
        assertFalse("a detached view has no pending frame callback", view.meetingSpiralSnapshotForTest().frameScheduled)
        activity.finish()
    }

    @Test
    fun hiddenWindowCancelsTheSingleFrameAndVisibleWindowSchedulesItAgain() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CursiveWaveView(activity)
        attachVisibleMeetingView(activity, view)
        view.setMeetingMode(true)
        assertVisibleMeetingPreconditions(view)
        view.setMeetingCaptureActive(true)
        assertTrue(view.meetingSpiralSnapshotForTest().frameScheduled)

        dispatchWindowVisibility(activity, View.GONE)
        assertFalse("a hidden host window cancels the meeting frame", view.meetingSpiralSnapshotForTest().frameScheduled)
        assertEquals(View.GONE, view.windowVisibility)
        dispatchWindowVisibility(activity, View.GONE)
        assertFalse(view.meetingSpiralSnapshotForTest().frameScheduled)

        dispatchWindowVisibility(activity, View.VISIBLE)
        assertEquals(View.VISIBLE, view.windowVisibility)
        assertTrue("a visible active host schedules exactly one pending frame", view.meetingSpiralSnapshotForTest().frameScheduled)
        dispatchWindowVisibility(activity, View.VISIBLE)
        assertTrue(view.meetingSpiralSnapshotForTest().frameScheduled)
        activity.setContentView(View(activity))
        assertFalse("detaching the view removes its pending callback", view.meetingSpiralSnapshotForTest().frameScheduled)
        activity.finish()
    }

    @Test
    fun meetingModeDoesNotChangeTheExistingDictationLoopGeometry() {
        val view = wave()
        val before = view.compactGeometryForTest(waveAmplitude = 0f, phase = 0.7f)
        view.setMeetingMode(true)
        view.setMeetingMode(false)
        val after = view.compactGeometryForTest(waveAmplitude = 0f, phase = 0.7f)
        assertEquals(before, after)
    }

    private fun attachVisibleMeetingView(activity: Activity, view: CursiveWaveView) {
        activity.setContentView(view)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("fixture view is attached after window traversals", view.isAttachedToWindow)
        dispatchWindowVisibilityForTest(view, View.VISIBLE)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertVisibleMeetingPreconditions(view)
    }

    private fun assertVisibleMeetingPreconditions(view: CursiveWaveView) {
        assertTrue("fixture view is attached after window traversals", view.isAttachedToWindow)
        assertTrue("fixture view and ancestors are shown", view.isShown)
        assertEquals("fixture host window is visible", View.VISIBLE, view.windowVisibility)
        assertTrue("system animations are enabled for scheduler assertions", ValueAnimator.areAnimatorsEnabled())
    }

    private fun dispatchWindowVisibility(activity: Activity, visibility: Int) {
        val view = activity.window.decorView.findViewById<View>(android.R.id.content)
            ?: error("Activity content view is missing")
        dispatchWindowVisibilityForTest(view, visibility)
    }

    /** Robolectric has no real ViewRootImpl traversal; mirror its AttachInfo update for this event. */
    private fun dispatchWindowVisibilityForTest(view: View, visibility: Int) {
        val attachInfoField = View::class.java.getDeclaredField("mAttachInfo").apply { isAccessible = true }
        val attachInfo = requireNotNull(attachInfoField.get(view)) { "test view must be attached" }
        val visibilityField = attachInfo.javaClass.getDeclaredField("mWindowVisibility").apply { isAccessible = true }
        visibilityField.setInt(attachInfo, visibility)
        view.dispatchWindowVisibilityChanged(visibility)
    }
}
