package com.kafkasl.phonewhisper.meeting

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/** Assigns a speaker only when a word is fully covered by stable, sufficiently clear frames. */
internal object MeetingSpeakerAttribution {
    const val UNKNOWN_CHANNEL = 0

    private const val MILLIS_PER_SECOND = 1_000.0
    private const val FLOAT32_RELATIVE_BOUNDARY_TOLERANCE = 6.0e-8
    private const val MIN_FRAME_POSITION_TOLERANCE = 1.0e-9
    private const val MIN_ACTIVE_PROBABILITY = 0.5
    private const val MIN_MEAN_PROBABILITY = 0.5
    private const val MIN_MEAN_MARGIN = 0.2
    private const val MIN_UNIQUE_ACTIVE_COVERAGE = 0.75
    private const val MAX_COMPETITOR_ACTIVE_COVERAGE = 0.25
    private const val MAX_CHANNEL = 8
    private const val MAX_EXACT_DOUBLE_FRAME_INDEX = 9_007_199_254_740_991L

    fun channelFor(startMs: Long, endMs: Long, window: DiarizationFrameWindow?): Int {
        if (window == null || startMs < 0L || endMs <= startMs) return UNKNOWN_CHANNEL

        val speakerCount = window.speakerCount
        val frameCount = window.probabilities.size / speakerCount
        if (frameCount == 0 || window.firstFrameIndex > Long.MAX_VALUE - frameCount) return UNKNOWN_CHANNEL
        val windowEndFrameIndex = window.firstFrameIndex + frameCount
        if (windowEndFrameIndex > MAX_EXACT_DOUBLE_FRAME_INDEX) return UNKNOWN_CHANNEL

        val frameDurationMs = window.secondsPerFrame * MILLIS_PER_SECOND
        if (!frameDurationMs.isFinite() || frameDurationMs <= 0.0) return UNKNOWN_CHANNEL

        val wordStartFrame = framePosition(startMs.toDouble() / frameDurationMs)
        val wordEndFrame = framePosition(endMs.toDouble() / frameDurationMs)
        val wordDurationFrames = wordEndFrame - wordStartFrame
        if (!wordStartFrame.isFinite() || !wordEndFrame.isFinite() || wordDurationFrames <= 0.0) {
            return UNKNOWN_CHANNEL
        }

        val windowStartFrame = window.firstFrameIndex.toDouble()
        val windowEndFrame = windowEndFrameIndex.toDouble()
        val stableEndFrame = minOf(windowEndFrameIndex, window.totalFrameCount, window.stableFrameCount).toDouble()
        if (wordStartFrame < windowStartFrame || wordEndFrame > windowEndFrame || wordEndFrame > stableEndFrame) {
            return UNKNOWN_CHANNEL
        }

        val probabilityTotals = DoubleArray(speakerCount)
        val activeDurations = DoubleArray(speakerCount)
        val uniqueActiveDurations = DoubleArray(speakerCount)
        var concurrentDuration = 0.0

        var frameIndex = floor(wordStartFrame).toLong()
        while (frameIndex.toDouble() < wordEndFrame) {
            val overlapFrames = minOf(wordEndFrame, frameIndex.toDouble() + 1.0) -
                maxOf(wordStartFrame, frameIndex.toDouble())
            if (overlapFrames <= 0.0) {
                frameIndex++
                continue
            }

            val localFrameIndex = frameIndex - window.firstFrameIndex
            if (localFrameIndex !in 0 until frameCount.toLong()) return UNKNOWN_CHANNEL
            val rowOffsetLong = localFrameIndex * speakerCount
            if (rowOffsetLong < 0L || rowOffsetLong + speakerCount > window.probabilities.size) {
                return UNKNOWN_CHANNEL
            }
            val rowOffset = rowOffsetLong.toInt()
            var activeCount = 0
            var uniqueActiveSpeaker = -1
            for (speaker in 0 until speakerCount) {
                val probability = window.probabilities[rowOffset + speaker].toDouble()
                if (!probability.isFinite() || probability !in 0.0..1.0) return UNKNOWN_CHANNEL

                probabilityTotals[speaker] += probability * overlapFrames
                if (probability >= MIN_ACTIVE_PROBABILITY) {
                    activeDurations[speaker] += overlapFrames
                    activeCount++
                    uniqueActiveSpeaker = speaker
                }
            }

            when (activeCount) {
                1 -> uniqueActiveDurations[uniqueActiveSpeaker] += overlapFrames
                in 2..speakerCount -> concurrentDuration += overlapFrames
            }
            frameIndex++
        }

        var bestSpeaker = -1
        var bestMeanProbability = Double.NEGATIVE_INFINITY
        var secondMeanProbability = Double.NEGATIVE_INFINITY
        for (speaker in 0 until speakerCount) {
            val meanProbability = probabilityTotals[speaker] / wordDurationFrames
            if (meanProbability > bestMeanProbability) {
                secondMeanProbability = bestMeanProbability
                bestMeanProbability = meanProbability
                bestSpeaker = speaker
            } else if (meanProbability > secondMeanProbability) {
                secondMeanProbability = meanProbability
            }
        }
        if (speakerCount == 1) secondMeanProbability = 0.0

        if (bestSpeaker < 0 || bestMeanProbability < MIN_MEAN_PROBABILITY ||
            bestMeanProbability - secondMeanProbability < MIN_MEAN_MARGIN ||
            uniqueActiveDurations[bestSpeaker] / wordDurationFrames < MIN_UNIQUE_ACTIVE_COVERAGE ||
            concurrentDuration / wordDurationFrames >= MAX_COMPETITOR_ACTIVE_COVERAGE
        ) {
            return UNKNOWN_CHANNEL
        }

        if (activeDurations.indices.any { speaker ->
                speaker != bestSpeaker && activeDurations[speaker] / wordDurationFrames >= MAX_COMPETITOR_ACTIVE_COVERAGE
            }
        ) return UNKNOWN_CHANNEL

        val channel = bestSpeaker + 1
        return channel.takeIf { it in 1..MAX_CHANNEL } ?: UNKNOWN_CHANNEL
    }

    /** Snap only Float32 cadence error around a true frame boundary; real partial overlap stays fractional. */
    private fun framePosition(position: Double): Double {
        if (!position.isFinite()) return position
        val nearestBoundary = round(position)
        val float32Error = abs(nearestBoundary) * FLOAT32_RELATIVE_BOUNDARY_TOLERANCE
        val tolerance = maxOf(MIN_FRAME_POSITION_TOLERANCE, float32Error)
        return if (abs(position - nearestBoundary) <= tolerance) nearestBoundary else position
    }
}
