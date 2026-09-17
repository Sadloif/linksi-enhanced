package com.linksi.app.enhanced.bubble

import kotlin.math.roundToInt

/**
 * Every decision the floating bubble makes, as pure functions (specification section 12).
 *
 * The service layer only performs the platform work: inflate, add view, forward touch events, and
 * call into this object for the answers. That keeps the geometry, the size mapping and the
 * auto-dismiss timing unit testable on the plain JVM, including on integer overflow and on
 * nonsense screen sizes.
 */
object BubblePolicy {

    // ── Settings vocabulary ───────────────────────────────────────────────────

    /** Values stored under `BUBBLE_POSITION`. */
    const val POSITION_LEFT = "left"
    const val POSITION_RIGHT = "right"
    const val POSITION_REMEMBER = "remember"

    /** Values stored under `BUBBLE_SIZE`. */
    const val SIZE_SMALL = "small"
    const val SIZE_NORMAL = "normal"
    const val SIZE_LARGE = "large"

    /** The only value of `BUBBLE_AUTO_DISMISS` that means "never dismiss on its own". */
    const val AUTO_DISMISS_PERSISTENT = 0
    const val AUTO_DISMISS_FIVE_SECONDS = 5
    const val AUTO_DISMISS_TEN_SECONDS = 10
    const val AUTO_DISMISS_THIRTY_SECONDS = 30

    /** The offered auto-dismiss choices, in the order the settings screen should show them. */
    val AUTO_DISMISS_CHOICES: List<Int> =
        listOf(AUTO_DISMISS_FIVE_SECONDS, AUTO_DISMISS_TEN_SECONDS, AUTO_DISMISS_THIRTY_SECONDS, AUTO_DISMISS_PERSISTENT)

    // ── Size ──────────────────────────────────────────────────────────────────

    /**
     * Diameter of the bubble's touchable area in dp for [size].
     *
     * The touchable area is the bubble and nothing else, so the returned value is also the view's
     * exact size: the overlay must never swallow touches meant for the app underneath
     * (research note ANDROID16_REQUIREMENTS.md section 8.2).
     */
    fun diameterDp(size: String): Int = when (size.trim().lowercase()) {
        SIZE_SMALL -> 40
        SIZE_LARGE -> 64
        else -> 52
    }

    /** Icon size in dp, kept in proportion to [diameterDp]. */
    fun iconDp(size: String): Int {
        val diameter = diameterDp(size)
        return (diameter * 0.55f).roundToInt().coerceAtLeast(1)
    }

    /** Normalises an unknown or blank stored size to a known one. */
    fun normalizeSize(size: String?): String = when (size?.trim()?.lowercase()) {
        SIZE_SMALL -> SIZE_SMALL
        SIZE_LARGE -> SIZE_LARGE
        else -> SIZE_NORMAL
    }

    /** Normalises an unknown or blank stored position to a known one. */
    fun normalizePosition(position: String?): String = when (position?.trim()?.lowercase()) {
        POSITION_LEFT -> POSITION_LEFT
        POSITION_RIGHT -> POSITION_RIGHT
        else -> POSITION_REMEMBER
    }

    // ── Auto dismiss ──────────────────────────────────────────────────────────

    /**
     * How long the bubble may stay on screen, in milliseconds, or null when it is persistent.
     *
     * A negative or unknown value is treated as persistent rather than as "dismiss immediately",
     * because a bubble that flashes for a moment is worse than one that waits for the user.
     */
    fun autoDismissDelayMs(seconds: Int): Long? {
        if (seconds <= AUTO_DISMISS_PERSISTENT) return null
        return seconds.toLong() * 1000L
    }

    /** True when the stored auto-dismiss value means "stay until the user acts". */
    fun isPersistent(seconds: Int): Boolean = autoDismissDelayMs(seconds) == null

    /** Human readable label for the stored auto-dismiss value. */
    fun autoDismissLabel(seconds: Int): String {
        val delay = autoDismissDelayMs(seconds) ?: return "Persistent"
        return "${delay / 1000} sec"
    }

    // ── Geometry ──────────────────────────────────────────────────────────────

    /**
     * The side the bubble must snap to.
     *
     * [storedPosition] of `left` or `right` always wins, even when the user has dragged the bubble
     * across the screen, because "remember the side, not the exact spot" is what the settings
     * promise. Only `remember` follows the drag.
     */
    fun snapSide(
        storedPosition: String?,
        bubbleCentreX: Int,
        screenWidth: Int
    ): String = when (normalizePosition(storedPosition)) {
        POSITION_LEFT -> POSITION_LEFT
        POSITION_RIGHT -> POSITION_RIGHT
        else -> if (bubbleCentreX * 2 < screenWidth) POSITION_LEFT else POSITION_RIGHT
    }

    /**
     * Top-left x for a snapped bubble.
     *
     * The whole bubble always stays on screen, so it remains reachable on small displays and in
     * split screen where the usable width can be smaller than the bubble.
     */
    fun snappedX(side: String, bubbleWidth: Int, screenWidth: Int, marginPx: Int): Int {
        val width = bubbleWidth.coerceAtLeast(0)
        val screen = screenWidth.coerceAtLeast(0)
        val margin = marginPx.coerceAtLeast(0)
        val maxX = (screen - width).coerceAtLeast(0)
        val raw = if (normalizePosition(side) == POSITION_LEFT) margin else screen - width - margin
        return raw.coerceIn(0, maxX)
    }

    /**
     * Top-left y for a dragged bubble: kept fully on screen and never above [topInsetPx].
     *
     * [topInsetPx] is the status bar height: the overlay is drawn beneath it, so a bubble at y = 0
     * would be partly unreachable.
     */
    fun clampY(y: Int, bubbleHeight: Int, screenHeight: Int, topInsetPx: Int): Int {
        val height = bubbleHeight.coerceAtLeast(0)
        val screen = screenHeight.coerceAtLeast(0)
        val minY = topInsetPx.coerceAtLeast(0).coerceAtMost((screen - height).coerceAtLeast(0))
        val maxY = (screen - height).coerceAtLeast(minY)
        return y.coerceIn(minY, maxY)
    }

    /** Top-left x for a bubble being dragged, clamped so it stays partly under the finger. */
    fun clampX(x: Int, bubbleWidth: Int, screenWidth: Int): Int {
        val width = bubbleWidth.coerceAtLeast(0)
        val screen = screenWidth.coerceAtLeast(0)
        return x.coerceIn(0, (screen - width).coerceAtLeast(0))
    }

    /**
     * Decides whether a touch was a drag or a tap.
     *
     * A tap must survive a few pixels of finger movement, otherwise the bubble becomes almost
     * impossible to press; anything more than [slopPx] is treated as a drag so the bubble does not
     * both move and open the panel.
     */
    fun isTap(downX: Int, downY: Int, upX: Int, upY: Int, slopPx: Int): Boolean {
        val slop = slopPx.coerceAtLeast(0)
        return kotlin.math.abs(upX - downX) <= slop && kotlin.math.abs(upY - downY) <= slop
    }

    /** Whether a drag that ended at [x] should be treated as "user moved the bubble" at all. */
    fun isDrag(downX: Int, downY: Int, currentX: Int, currentY: Int, slopPx: Int): Boolean =
        !isTap(downX, downY, currentX, currentY, slopPx)
}
