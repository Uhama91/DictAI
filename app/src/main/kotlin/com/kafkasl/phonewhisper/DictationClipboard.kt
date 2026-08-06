package com.kafkasl.phonewhisper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log

internal object DictationClipboard {
    private const val TAG = "DictationClipboard"

    fun copy(context: Context, text: String): Boolean = try {
        val clip = ClipData.newPlainText("dictai", text)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(clip)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "event=clipboard_copy outcome=failed error=${t.javaClass.simpleName}")
        false
    }

}
