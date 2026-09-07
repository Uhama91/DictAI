package com.kafkasl.phonewhisper

internal object FloatingMenuPlacement {
    fun bounds(pill: Rect, screen: Rect, width: Int, height: Int, gap: Int, above: Boolean): Rect {
        val w = width.coerceIn(1, screen.width.coerceAtLeast(1))
        val beside = !above && (pill.x - gap - screen.x >= w || screen.right - pill.right - gap >= w)
        val availableHeight = if (beside) screen.height else maxOf(pill.y - screen.y - gap, screen.bottom - pill.bottom - gap)
        val h = height.coerceIn(1, availableHeight.coerceAtLeast(1))
        val point = position(pill, screen, w, h, gap, above)
        return Rect(point.x, point.y, w, h)
    }
    fun position(pill: Rect, screen: Rect, width: Int, height: Int, gap: Int, above: Boolean): Point {
        val x = if (!above && pill.x - gap - width >= screen.x) pill.x - gap - width
            else if (!above && pill.right + gap + width <= screen.right) pill.right + gap
            else pill.centerX - width / 2
        val y = if (above || (x < pill.right && x + width > pill.x)) {
            if (pill.y - gap - height >= screen.y) pill.y - gap - height else pill.bottom + gap
        } else pill.centerY - height / 2
        return Point(x.coerceIn(screen.x, (screen.right - width).coerceAtLeast(screen.x)),
            y.coerceIn(screen.y, (screen.bottom - height).coerceAtLeast(screen.y)))
    }
}
