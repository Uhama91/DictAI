package com.kafkasl.phonewhisper

internal object NoteCameraGeometry {
    data class Size(val width: Int, val height: Int) { val area: Long get() = width.toLong()*height }
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
