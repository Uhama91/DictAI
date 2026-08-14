package com.kafkasl.phonewhisper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!OverlayRestartPolicy.decide(intent?.action).shouldStart) return

        try {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        } catch (t: Throwable) {
            Log.w(TAG, "overlay restart refused: ${t.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "WhisperPin"
    }
}
