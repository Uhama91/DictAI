package com.kafkasl.phonewhisper

/** Process-wide exclusion between a live dictation and a latency measurement. */
internal object LocalMeasurementAdmission {
    internal enum class Owner { DICTATION, BENCHMARK }

    internal class Lease internal constructor(val owner: Owner) : AutoCloseable {
        private var closed = false

        override fun close() {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            LocalMeasurementAdmission.release(this)
        }
    }

    private val lock = Any()
    private var active: Lease? = null

    fun tryAcquire(owner: Owner): Lease? = synchronized(lock) {
        if (active != null) return@synchronized null
        Lease(owner).also { active = it }
    }

    private fun release(lease: Lease) {
        synchronized(lock) {
            if (active === lease) active = null
        }
    }
}
