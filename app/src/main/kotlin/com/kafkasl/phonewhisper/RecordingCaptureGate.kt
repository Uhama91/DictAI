package com.kafkasl.phonewhisper

/** Pause closes admission atomically with buffer delivery, including reads already in flight. */
internal class RecordingCaptureGate {
    private var accepting = true
    @Synchronized fun pause() { accepting = false }
    @Synchronized fun resume() { accepting = true }
    @Synchronized fun deliver(consume: () -> Unit): Boolean {
        if (!accepting) return false
        consume()
        return true
    }
}
