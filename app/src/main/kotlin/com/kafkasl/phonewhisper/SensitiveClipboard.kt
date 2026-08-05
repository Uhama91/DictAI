package com.kafkasl.phonewhisper

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log

private const val COMPAT_SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"

internal fun sensitiveClipboardExtraKey(apiLevel: Int, platformKey: String): String =
    if (apiLevel >= 33) platformKey else COMPAT_SENSITIVE_EXTRA

internal object SensitiveClipboard {
    private const val TAG = "SensitiveClipboard"

    fun copy(context: Context, text: String): Boolean = try {
        val clip = ClipData.newPlainText("whisperpin", text)
        val platformKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ClipDescription.EXTRA_IS_SENSITIVE
        } else {
            COMPAT_SENSITIVE_EXTRA
        }
        clip.description.extras = PersistableBundle().apply {
            putBoolean(sensitiveClipboardExtraKey(Build.VERSION.SDK_INT, platformKey), true)
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(clip)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "event=clipboard_copy outcome=failed error=${t.javaClass.simpleName}")
        false
    }
}
