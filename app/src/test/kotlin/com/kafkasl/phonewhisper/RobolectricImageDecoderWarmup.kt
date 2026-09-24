package com.kafkasl.phonewhisper

import java.io.File

/** Initialize Robolectric's native ImageDecoder on the test thread before parallel thumbnail work. */
internal object RobolectricImageDecoderWarmup {
    fun decode(file: File) {
        val bitmap = NoteImageStore.decode(file, 64)
        bitmap.recycle()
    }
}
