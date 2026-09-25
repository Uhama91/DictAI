package com.kafkasl.phonewhisper

internal object NoteCameraGeometry {
    data class Size(val width: Int, val height: Int) { val area: Long get() = width.toLong()*height }
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        init { require(right > left && bottom > top) }
        val width get() = right - left
        val height get() = bottom - top
    }

    fun zoomForProgress(progress: Int, minimum: Float, maximum: Float): Float {
        val low = minimum.takeIf { it.isFinite() && it > 0f } ?: 1f
        val high = maximum.takeIf { it.isFinite() }?.coerceAtLeast(low) ?: low
        return low + (high - low) * (progress.coerceIn(0, 100) / 100f)
    }

    /** Centered crop used on camera devices without the API 30 zoom-ratio request key. */
    fun cropForZoom(activeArray: Rect, ratio: Float): Rect {
        val safeRatio = ratio.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        val width = (activeArray.width / safeRatio).toInt().coerceIn(1, activeArray.width)
        val height = (activeArray.height / safeRatio).toInt().coerceIn(1, activeArray.height)
        val centerX = activeArray.left + activeArray.width / 2
        val centerY = activeArray.top + activeArray.height / 2
        return Rect(centerX - width / 2, centerY - height / 2,
            centerX - width / 2 + width, centerY - height / 2 + height)
    }

    fun dialogWidth(availableWidthPx: Int, density: Float, expanded: Boolean, landscape: Boolean = false): Int {
        val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val available = availableWidthPx.coerceAtLeast(1)
        val safeWidth = (available - (24 * safeDensity).toInt()).coerceAtLeast(1)
        val compactMaximum = ((if (landscape) 620 else 340) * safeDensity).toInt().coerceAtLeast(1)
        return if (expanded) safeWidth else minOf(safeWidth, compactMaximum)
    }

    fun previewHeight(availableHeightPx: Int, density: Float, expanded: Boolean, landscape: Boolean): Int {
        val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val available = availableHeightPx.coerceAtLeast(1)
        // Reserve room for zoom, the thumbnail strip, and both action rows before sizing the
        // viewfinder. A short landscape window gets a shorter preview instead of clipping controls.
        val reserve = ((if (landscape) 210 else 320) * safeDensity).toInt()
        val minimum = ((if (landscape) 48 else 72) * safeDensity).toInt().coerceAtLeast(1)
        val maximum = (available - reserve).coerceAtLeast(minimum)
        val desired = ((if (expanded) 440 else 250) * safeDensity).toInt()
        return minOf(desired, maximum).coerceAtLeast(minimum)
    }

    fun chooseSize(sizes: List<Size>, maxEdge: Int, ratio: Double? = null): Size {
        val valid = sizes.filter { it.width > 0 && it.height > 0 }
        require(valid.isNotEmpty())
        val bounded = valid.filter { maxOf(it.width, it.height) <= maxEdge }
        val candidates = bounded.ifEmpty { listOf(valid.minBy { it.area }) }
        val matching = if (ratio == null) candidates else candidates.filter { kotlin.math.abs(it.width.toDouble()/it.height - ratio) < .04 }.ifEmpty { candidates }
        return matching.maxBy { it.area }
    }
    fun previewScale(bufferWidth: Int, bufferHeight: Int, sensor: Int, display: Int, viewWidth: Int, viewHeight: Int): Pair<Float, Float> {
        require(minOf(bufferWidth, bufferHeight, viewWidth, viewHeight) > 0)
        val uprightWidth = if (sensor % 180 == 0) bufferWidth else bufferHeight
        val uprightHeight = if (sensor % 180 == 0) bufferHeight else bufferWidth
        val rotatedWidth = if (display % 180 == 0) uprightWidth else uprightHeight
        val rotatedHeight = if (display % 180 == 0) uprightHeight else uprightWidth
        val fit = minOf(viewWidth.toFloat()/rotatedWidth, viewHeight.toFloat()/rotatedHeight)
        return Pair(uprightWidth * fit / viewWidth, uprightHeight * fit / viewHeight)
    }
    fun jpegRotation(sensorDegrees: Int, displayDegrees: Int, front: Boolean): Int =
        (sensorDegrees + (if (front) displayDegrees else -displayDegrees) + 360) % 360
}
