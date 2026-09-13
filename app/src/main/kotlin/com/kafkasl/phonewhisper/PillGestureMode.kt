package com.kafkasl.phonewhisper

/** Movement before the hold is a shortcut; movement after the haptic hold is placement. */
internal class PillGestureMode(private val touchSlop: Float) {
    enum class Mode { WAITING, SHORTCUT, DRAG, CANCELLED }
    var mode = Mode.WAITING
        private set
    fun begin() { mode = Mode.WAITING }
    fun move(dx: Float, dy: Float) {
        if (mode == Mode.WAITING && kotlin.math.abs(dx) + kotlin.math.abs(dy) > touchSlop) {
            mode = Mode.SHORTCUT
        }
    }
    fun hold(): Boolean {
        if (mode != Mode.WAITING) return false
        mode = Mode.DRAG
        return true
    }
    fun cancel() { mode = Mode.CANCELLED }
}
