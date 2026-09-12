package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.abs

/** Regression checks for the time-based compact writing stroke. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CursiveWaveMotionTest {

    private fun wave(): CursiveWaveView = CursiveWaveView(RuntimeEnvironment.getApplication())

    @Test
    fun voicePreservesInstalledRestSilhouetteAndOpensBothDirections() {
        val view = wave()
        val resting = view.compactGeometryForTest(waveAmplitude = 0f, phase = 0f)
        val voiced = view.compactGeometryForTest(waveAmplitude = 38f, phase = 1.2f)

        assertTrue("The path should cover both fade edges", resting.any { it.startX < 0f } && resting.any { it.endX > 100f })
        assertEquals(resting.size, voiced.size)
        assertTrue("Voice should move at least one connected valley", voiced.any { it.baselineY > 28f || it.endBaselineY > 28f })
        var peakTravel = 0f
        var valleyTravel = 0f
        resting.zip(voiced).forEach { (calm, strong) ->
            assertEquals("Resting valley should match the installed silhouette", calm.baselineY.toDouble(), 28.0, 0.0001)
            assertEquals("Resting valley should match the installed silhouette", calm.endBaselineY.toDouble(), 28.0, 0.0001)
            assertEquals("Resting crest should match the installed silhouette", calm.topY.toDouble(), 12.0, 0.0001)
            assertTrue("Voice should raise the upper crest", strong.topY <= calm.topY + 0.0001f)
            peakTravel = maxOf(peakTravel, calm.topY - strong.topY)
            valleyTravel = maxOf(
                valleyTravel,
                maxOf(strong.baselineY - calm.baselineY, strong.endBaselineY - calm.endBaselineY),
            )
        }
        assertTrue("The lower excursion should be clearly visible", valleyTravel > 6f)
        assertTrue("Upper and lower excursions should be comparable", valleyTravel >= peakTravel * 0.65f)
        assertTrue("Upper and lower excursions should be bounded", valleyTravel <= peakTravel * 1.45f)
        voiced.zipWithNext().forEach { (left, right) ->
            assertEquals("Adjacent loops must share the same valley", left.endBaselineY.toDouble(), right.baselineY.toDouble(), 0.0001)
        }
        (0..24).forEach { step ->
            view.compactGeometryForTest(waveAmplitude = 38f, phase = step * 0.31f).forEach { loop ->
                assertTrue("Strong crests must retain a stroke margin", loop.topY >= 3f)
                assertTrue("Strong valleys must retain a stroke margin", loop.baselineY <= 37f && loop.endBaselineY <= 37f)
            }
        }
    }

    @Test
    fun vividPeaksAndValleysFollowTheSameLocalEnvelopeInOppositeDirections() {
        val view = wave()
        view.setCompactMotionPreset(CompactMotionPreset.VIVID)
        val resting = view.compactGeometryForTest(waveAmplitude = 0f, phase = 0f)
        val reference = resting.first { it.endX > 0f && it.startX < 100f }
        val samples = (0..12).map { step ->
            val phase = step * 0.37f
            val geometry = view.compactGeometryForTest(waveAmplitude = 38f, phase = phase)
            geometry.first { abs(it.startX - reference.startX) < 0.0001f }
        }
        val pairs = samples.map { sample ->
            (12f - sample.topY) to ((sample.baselineY + sample.endBaselineY) * 0.5f - 28f)
        }
        val meanPeak = pairs.map { it.first }.average()
        val meanValley = pairs.map { it.second }.average()
        val covariance = pairs.fold(0.0) { sum, pair ->
            sum + ((pair.first - meanPeak) * (pair.second - meanValley)).toDouble()
        }
        assertTrue("A rising crest must be paired with a descending valley", covariance > 0.5)
        val strongest = pairs.maxBy { it.first }
        assertTrue("The same local event must visibly lower its valley", strongest.second > 6f)
    }

    @Test
    fun motionPresetsChangeShapeRatherThanOnlySpeed() {
        val view = wave()
        val renders = CompactMotionPreset.values().map { preset ->
            view.setCompactMotionPreset(preset)
            view.compactGeometryForTest(waveAmplitude = 38f, phase = 1.2f)
        }
        assertTrue(
            "The three candidates should have distinct upper/lower geometry",
            renders.map { geometry -> geometry.map { it.topY to it.baselineY } }.toSet().size == CompactMotionPreset.values().size,
        )
        assertTrue(
            "Breathing candidate should expose a larger lower excursion",
            renders[CompactMotionPreset.BREATHING.ordinal].maxOf { it.baselineY } >
                renders[CompactMotionPreset.ORGANIC.ordinal].maxOf { it.baselineY },
        )
    }

    @Test
    fun offsetMovesRightAndSpatialWrapPreservesTheSameHandwriting() {
        val view = wave()
        val period = view.compactLoopPeriodForTest()
        val before = view.animationSnapshotForTest()
        view.advanceForTest(0.1f)
        val after = view.animationSnapshotForTest()

        assertTrue("Positive time must move the writing toward the right", after.first > before.first)
        assertTrue("Offset stays within one spatial period", after.first >= 0f && after.first < period)

        val atZero = view.compactGeometryForTest(scrollOffset = 0f, waveAmplitude = 23f, phase = 0.7f)
        val atPeriod = view.compactGeometryForTest(scrollOffset = period, waveAmplitude = 23f, phase = 0.7f)
        assertEquals(atZero.size, atPeriod.size)
        atZero.zip(atPeriod).forEach { (first, wrapped) ->
            assertEquals("Exact period normalizes to the same position", first.startX.toDouble(), wrapped.startX.toDouble(), 0.0001)
            assertEquals("Wrapped loop keeps its shape", first.topY.toDouble(), wrapped.topY.toDouble(), 0.0001)
            assertEquals("Wrapped loop keeps its baseline", first.baselineY.toDouble(), wrapped.baselineY.toDouble(), 0.0001)
        }

        // Inspect the two sides of the modulo boundary. A period minus epsilon and a period
        // plus epsilon must be two epsilon of continuous travel apart, rather than switching
        // to a different randomized loop pattern.
        val epsilon = 0.01f
        val beforeWrap = view.compactGeometryForTest(scrollOffset = period - epsilon, waveAmplitude = 23f, phase = 0.7f)
        val afterWrap = view.compactGeometryForTest(scrollOffset = period + epsilon, waveAmplitude = 23f, phase = 0.7f)
        val visibleBefore = beforeWrap.filter { it.endX > 0f && it.startX < 100f }
        val visibleAfter = afterWrap.filter { it.endX > 0f && it.startX < 100f }
        assertTrue("Both sides of the wrap should draw a visible sequence", visibleBefore.size >= 4 && visibleAfter.size >= 4)
        visibleBefore.forEach { before ->
            val after = visibleAfter.firstOrNull { candidate ->
                abs((candidate.startX - before.startX) - 2f * epsilon) < 0.0002f
            }
            assertTrue("A loop should continue through the spatial wrap", after != null)
            assertTrue("Spatial wrap keeps the upper shape continuous", abs(before.topY - after!!.topY) < 0.05f)
            assertEquals("Spatial wrap keeps the lower shape continuous", before.baselineY.toDouble(), after.baselineY.toDouble(), 0.05)
            assertEquals("Spatial wrap keeps the next lower junction continuous", before.endBaselineY.toDouble(), after.endBaselineY.toDouble(), 0.05)
        }
    }

    @Test
    fun elapsedTimeProducesTheSameStateAtSixtyAndOneTwentyHz() {
        val sixty = wave()
        val oneTwenty = wave()
        sixty.setLevel(1f)
        oneTwenty.setLevel(1f)
        repeat(60) { sixty.advanceForTest(1f / 60f) }
        repeat(120) { oneTwenty.advanceForTest(1f / 120f) }

        val first = sixty.animationSnapshotForTest()
        val second = oneTwenty.animationSnapshotForTest()
        assertTrue("Spatial movement should be refresh-rate independent", abs(first.first - second.first) < 0.02f)
        assertTrue("Phase should be refresh-rate independent", abs(first.second - second.second) < 0.02f)
        assertTrue("Voice response should be refresh-rate independent", abs(first.third - second.third) < 0.08f)
    }

    @Test
    fun springKeepsTwentyMillisecondSyllableTransitionsSmoothAndRefreshIndependent() {
        val targets = listOf(0f, 0.58f, 0.82f, 0.18f, 0.90f, 0.04f, 0.76f, 0f)
        val sixty = driveAudioBlocks(wave(), targets, 1f / 60f)
        val oneTwenty = driveAudioBlocks(wave(), targets, 1f / 120f)

        assertTrue("Spring output must remain inside the drawable amplitude", sixty.all { it in 0f..38f })
        val largestStep = sixty.zipWithNext().maxOf { (previous, current) -> abs(current - previous) }
        assertTrue("A 20 ms target change must not create an amplitude jump", largestStep < 16f)
        sixty.zip(oneTwenty).forEach { (atSixty, atOneTwenty) ->
            assertTrue("Audio-block transitions should be refresh-rate independent", abs(atSixty - atOneTwenty) < 0.15f)
        }
        assertTrue("A strong syllable should still reach a visibly open stroke", sixty[4] > 24f)
    }

    @Test
    fun longFrameDelayIsCappedInsteadOfJumpingAcrossTheScreen() {
        val view = wave()
        val period = view.compactLoopPeriodForTest()
        view.advanceForTest(10f)
        val snapshot = view.animationSnapshotForTest()

        // 0.12 s is the production cap, so the largest single advance is 42 * .12.
        assertTrue("A suspended view must not jump a full period", snapshot.first <= 42f * .12f + 0.001f)
        assertTrue("Offset remains normalized after a delayed frame", snapshot.first >= 0f && snapshot.first < period)
    }

    private fun driveAudioBlocks(
        view: CursiveWaveView,
        targets: List<Float>,
        frameSeconds: Float,
    ): List<Float> {
        val trace = ArrayList<Float>(targets.size)
        targets.forEach { target ->
            view.setLevel(target)
            var remaining = 0.020f
            while (remaining > 0.000001f) {
                val step = minOf(frameSeconds, remaining)
                view.advanceForTest(step)
                remaining -= step
            }
            trace += view.animationSnapshotForTest().third
        }
        return trace
    }
}
