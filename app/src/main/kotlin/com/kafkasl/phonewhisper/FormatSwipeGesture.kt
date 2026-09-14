package com.kafkasl.phonewhisper

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * State machine for the format shortcut on the pill.
 *
 * The first upward pull opens the menu and keeps the currently selected format highlighted. A
 * second, deliberate movement is required before the finger can change that selection. This
 * separation lets a short upward swipe reveal the normal, tappable menu while a held gesture can
 * choose an item without a second tap.
 */
internal class FormatSwipeGesture(
    private val touchSlop: Float,
    private val openDistance: Float,
    private val selectionActivationDistance: Float,
    private val selectionStep: Float,
) {
    enum class ReleaseAction { NONE, OPEN_MENU, COMMIT }

    data class Update(
        val openedNow: Boolean,
        val opened: Boolean,
        val selecting: Boolean,
        val selectedIndex: Int,
    )

    data class Release(
        val action: ReleaseAction,
        val selectedIndex: Int,
    )

    private var enabled = false
    private var invalidated = false
    private var opened = false
    private var selecting = false
    private var initialIndex = 0
    private var selectedIndex = 0
    private var itemCount = 0
    private var openingUpward = 0f

    fun begin(enabled: Boolean, initialIndex: Int, itemCount: Int) {
        this.itemCount = itemCount.coerceAtLeast(0)
        this.enabled = enabled && this.itemCount > 0
        invalidated = false
        opened = false
        selecting = false
        this.initialIndex = initialIndex.coerceIn(0, (this.itemCount - 1).coerceAtLeast(0))
        selectedIndex = this.initialIndex
        openingUpward = 0f
    }

    fun move(dx: Float, dy: Float): Update {
        if (!enabled || invalidated) return update(openedNow = false)

        val upward = -dy
        // A horizontal redirect before the menu opens belongs to the pill's other gestures. Once
        // the menu is open, keep accepting the finger's vertical return path so the user can move
        // to an item below the currently selected one.
        if (!opened && abs(dx) > touchSlop && upward < 2f * abs(dx)) {
            invalidated = true
            return update(openedNow = false)
        }

        val openedNow = !opened && upward >= openDistance && upward >= 2f * abs(dx)
        if (openedNow) {
            opened = true
            openingUpward = upward
            // The move that reveals the menu is an opening gesture. A later MOVE must carry the
            // finger farther before selection is armed; this also makes a coalesced DOWN/UP
            // swipe reveal the menu instead of silently committing an item.
            selecting = false
            selectedIndex = initialIndex
            return update(openedNow = true)
        }
        if (!opened) return update(openedNow = false)

        val fromOpening = upward - openingUpward
        selecting = abs(fromOpening) >= selectionActivationDistance
        if (selecting) {
            val step = selectionStep.coerceAtLeast(1f)
            selectedIndex = (initialIndex + (fromOpening / step).roundToInt())
                .coerceIn(0, (itemCount - 1).coerceAtLeast(0))
        } else {
            selectedIndex = initialIndex
        }
        return update(openedNow)
    }

    fun release(dx: Float, dy: Float): Release {
        // A release is not a selection MOVE. If the platform coalesced the whole gesture into
        // DOWN/UP, use the final coordinates only to reveal the menu; once it is already open,
        // commit only what a preceding ACTION_MOVE explicitly selected.
        val update = if (!opened) move(dx, dy) else update(openedNow = false)
        val result = when {
            !enabled || invalidated || !update.opened -> Release(ReleaseAction.NONE, initialIndex)
            update.selecting -> Release(ReleaseAction.COMMIT, update.selectedIndex)
            else -> Release(ReleaseAction.OPEN_MENU, update.selectedIndex)
        }
        reset()
        return result
    }

    fun cancel() {
        reset()
    }

    private fun reset() {
        enabled = false
        invalidated = false
        opened = false
        selecting = false
        selectedIndex = initialIndex
        openingUpward = 0f
    }

    private fun update(openedNow: Boolean) = Update(
        openedNow = openedNow,
        opened = opened,
        selecting = selecting,
        selectedIndex = selectedIndex,
    )
}
