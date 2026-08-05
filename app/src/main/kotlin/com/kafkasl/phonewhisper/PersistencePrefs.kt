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

    private var savedAnchor: Anchor?
        get() {
            val edge = edgeFromPreference(p.getString(KEY_ANCHOR_EDGE, null)) ?: return null
            if (!p.contains(KEY_ANCHOR_OFFSET)) return null
            val offset = try { p.getFloat(KEY_ANCHOR_OFFSET, Float.NaN) } catch (_: ClassCastException) { return null }
            return if (offset.isFinite() && offset in 0f..1f) Anchor(edge, offset) else null
        }
        set(value) {
            if (value == null) p.edit().remove(KEY_ANCHOR_EDGE).remove(KEY_ANCHOR_OFFSET).apply()
            else p.edit().putString(KEY_ANCHOR_EDGE, value.edge.name)
                .putFloat(KEY_ANCHOR_OFFSET, value.offset.coerceIn(0f, 1f)).apply()
        }

    fun loadAnchor(buttonW: Int, buttonH: Int, screenW: Int, screenH: Int): Anchor {
        savedAnchor?.let { return it }
        return migrateLegacyAnchor(buttonX, buttonY, buttonW, buttonH, screenW, screenH).also { savedAnchor = it }
    }

    fun saveAnchor(anchor: Anchor) { savedAnchor = anchor }

    var lastError: String?
        get() = p.getString("last_error", null)
        set(v) { p.edit().putString("last_error", v).apply() }

    /** Ajoute automatiquement une espace à la fin de chaque transcription insérée. */
    var trailingSpace: Boolean
        get() = p.getBoolean("trailing_space", false)
        set(v) { p.edit().putBoolean("trailing_space", v).apply() }

    companion object {
        private const val KEY_ANCHOR_EDGE = "btn_anchor_edge"
        private const val KEY_ANCHOR_OFFSET = "btn_anchor_offset"

        fun clampX(x: Int, w: Int, screenW: Int) = x.coerceIn(0, (screenW - w).coerceAtLeast(0))
        fun clampY(y: Int, h: Int, screenH: Int) = y.coerceIn(0, (screenH - h).coerceAtLeast(0))

        fun edgeFromPreference(value: String?): Edge? =
            Edge.entries.firstOrNull { it.name == value }

        fun migrateLegacyAnchor(buttonX: Int, buttonY: Int, buttonW: Int, buttonH: Int, screenW: Int, screenH: Int): Anchor {
            val screen = Rect(0, 0, screenW.coerceAtLeast(0), screenH.coerceAtLeast(0))
            val pill = Rect(0, 0, buttonW.coerceAtLeast(0), buttonH.coerceAtLeast(0))
            val legacy = Point(
                if (buttonX >= 0) clampX(buttonX, pill.width, screen.width) else screen.width - pill.width,
                if (buttonY >= 0) clampY(buttonY, pill.height, screen.height) else (screen.height - pill.height) / 2,
            )
            return OverlayPlacement.snap(legacy, pill, screen)
        }
    }
}
