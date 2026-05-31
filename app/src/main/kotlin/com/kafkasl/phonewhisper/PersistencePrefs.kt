package com.kafkasl.phonewhisper

import android.content.Context

class PersistencePrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)

    var buttonX: Int
        get() = p.getInt("btn_x", -1)
        set(v) { p.edit().putInt("btn_x", v).apply() }
    var buttonY: Int
        get() = p.getInt("btn_y", -1)
        set(v) { p.edit().putInt("btn_y", v).apply() }

    var lastError: String?
        get() = p.getString("last_error", null)
        set(v) { p.edit().putString("last_error", v).apply() }

    companion object {
        fun clampX(x: Int, w: Int, screenW: Int) = x.coerceIn(0, (screenW - w).coerceAtLeast(0))
        fun clampY(y: Int, h: Int, screenH: Int) = y.coerceIn(0, (screenH - h).coerceAtLeast(0))
    }
}
