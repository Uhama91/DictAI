package com.kafkasl.phonewhisper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import kotlin.concurrent.thread

class OverlayService : Service() {

    companion object {
        private const val TAG = "WhisperPin"
        private const val CHANNEL_ID = "whisperpin_overlay"
        private const val NOTIF_ID = 1001
        private const val SAMPLE_RATE = 16000
    }

    private var button: ImageView? = null
    private var recording = false
    private var audioRecord: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground(withMic = true)
        showButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "WhisperPin", NotificationManager.IMPORTANCE_MIN)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WhisperPin actif")
            .setContentText("Appuie sur le bouton pour dicter")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()

    private fun startAsForeground(withMic: Boolean) {
        val type = if (withMic)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        try {
            startForeground(NOTIF_ID, buildNotification(), type)
            Log.i(TAG, "startForeground OK withMic=$withMic")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground withMic=$withMic failed: ${e.javaClass.simpleName} ${e.message}")
            if (withMic) {
                startForeground(NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        }
    }

    private fun showButton() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        val size = (56 * dp).toInt()
        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xDD1C1C1E.toInt())
            }
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            setOnClickListener { onTap() }
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - (8 * dp).toInt()
            y = resources.displayMetrics.heightPixels / 2
        }
        wm.addView(img, params)
        button = img
    }

    private fun onTap() {
        if (!recording) startRec() else stopRec()
    }

    private fun startRec() {
        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: SecurityException) {
            toast("Mic refuse: ${e.message}"); return
        }
        recording = true
        toast("REC...")
        audioRecord!!.startRecording()
        thread {
            val buf = ShortArray(bufSize)
            var maxAmp = 0.0
            var samples = 0L
            while (recording) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                for (i in 0 until n) {
                    maxAmp = maxOf(maxAmp, kotlin.math.abs(buf[i].toDouble())); samples++
                }
            }
            Log.i(TAG, "SPIKE result: samples=$samples maxAmp=$maxAmp (>0 => mic OK en background)")
            android.os.Handler(mainLooper).post {
                toast("maxAmp=${maxAmp.toInt()} samples=$samples")
            }
        }
    }

    private fun stopRec() {
        recording = false
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        stopRec()
        button?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        button = null
        super.onDestroy()
    }
}
