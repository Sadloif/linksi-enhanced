package com.linksi.app.enhanced.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BubblePolicy].
 *
 * The bubble is the only part of this module that draws over another app, so its geometry, its
 * screen clamping and its auto-dismiss timing are all tested here rather than on a device. Every
 * case maps onto LINKSI_ENHANCED_REVISED_SPEC.md section 12.2 or onto the overlay constraints in
 * research/ANDROID16_REQUIREMENTS.md section 8.
 */
class BubblePolicyTest {

    // ── Size ──────────────────────────────────────────────────────────────────

    @Test
    fun theThreeConfiguredSizesMapToIncreasingDiameters() {
        val small = BubblePolicy.diameterDp(BubblePolicy.SIZE_SMALL)
        val normal = BubblePolicy.diameterDp(BubblePolicy.SIZE_NORMAL)
        val large = BubblePolicy.diameterDp(BubblePolicy.SIZE_LARGE)
        assertTrue("small must be smaller than normal", small < normal)
        assertTrue("normal must be smaller than large", normal < large)
    }

    @Test
    fun anUnknownSizeFallsBackToNormal() {
        assertEquals(
            BubblePolicy.diameterDp(BubblePolicy.SIZE_NORMAL),
            BubblePolicy.diameterDp("gigantic")
        )
        assertEquals(
            BubblePolicy.diameterDp(BubblePolicy.SIZE_NORMAL),
            BubblePolicy.diameterDp("")
        )
    }

    @Test
    fun sizeMatchingIsCaseAndWhitespaceInsensitive() {
        assertEquals(BubblePolicy.diameterDp(BubblePolicy.SIZE_SMALL), BubblePolicy.diameterDp("  SMALL "))
        assertEquals(BubblePolicy.diameterDp(BubblePolicy.SIZE_LARGE), BubblePolicy.diameterDp("Large"))
    }

    @Test
    fun normalizeSizeAlwaysReturnsAKnownValue() {
        assertEquals(BubblePolicy.SIZE_SMALL, BubblePolicy.normalizeSize("small"))
        assertEquals(BubblePolicy.SIZE_LARGE, BubblePolicy.normalizeSize("LARGE"))
        assertEquals(BubblePolicy.SIZE_NORMAL, BubblePolicy.normalizeSize(null))
        assertEquals(BubblePolicy.SIZE_NORMAL, BubblePolicy.normalizeSize("weird"))
    }

    @Test
    fun normalizePositionAlwaysReturnsAKnownValue() {
        assertEquals(BubblePolicy.POSITION_LEFT, BubblePolicy.normalizePosition("left"))
        assertEquals(BubblePolicy.POSITION_RIGHT, BubblePolicy.normalizePosition(" RIGHT "))
        assertEquals(BubblePolicy.POSITION_REMEMBER, BubblePolicy.normalizePosition(null))
        assertEquals(BubblePolicy.POSITION_REMEMBER, BubblePolicy.normalizePosition("top"))
    }

    @Test
    fun theIconStaysSmallerThanTheBubbleForEverySize() {
        for (size in listOf(BubblePolicy.SIZE_SMALL, BubblePolicy.SIZE_NORMAL, BubblePolicy.SIZE_LARGE)) {
            assertTrue(
                "icon must fit inside the bubble for size '$size'",
                BubblePolicy.iconDp(size) < BubblePolicy.diameterDp(size)
            )
            assertTrue(BubblePolicy.iconDp(size) > 0)
        }
    }

    // ── Auto dismiss (section 12.2) ────────────────────────────────────────────

    @Test
    fun theFourConfiguredChoicesMapToTheExpectedDelays() {
        assertEquals(5_000L, BubblePolicy.autoDismissDelayMs(5))
        assertEquals(10_000L, BubblePolicy.autoDismissDelayMs(10))
        assertEquals(30_000L, BubblePolicy.autoDismissDelayMs(30))
        assertNull("0 means persistent", BubblePolicy.autoDismissDelayMs(0))
    }

    @Test
    fun persistentIsTheOnlyChoiceThatNeverDismisses() {
        assertTrue(BubblePolicy.isPersistent(BubblePolicy.AUTO_DISMISS_PERSISTENT))
        assertFalse(BubblePolicy.isPersistent(BubblePolicy.AUTO_DISMISS_FIVE_SECONDS))
        assertFalse(BubblePolicy.isPersistent(BubblePolicy.AUTO_DISMISS_TEN_SECONDS))
        assertFalse(BubblePolicy.isPersistent(BubblePolicy.AUTO_DISMISS_THIRTY_SECONDS))
    }

    @Test
    fun aNegativeOrNonsenseDelayIsTreatedAsPersistentRatherThanInstant() {
        // A bubble that flashes for a moment is worse than one that waits for the user.
        assertNull(BubblePolicy.autoDismissDelayMs(-1))
        assertNull(BubblePolicy.autoDismissDelayMs(Int.MIN_VALUE))
        assertTrue(BubblePolicy.isPersistent(-5))
    }

    @Test
    fun theOfferedChoicesAreTheFourFromTheSpecification() {
        assertEquals(
            listOf(5, 10, 30, 0),
            BubblePolicy.AUTO_DISMISS_CHOICES
        )
    }

    @Test
    fun everyOfferedChoiceHasALabel() {
        assertEquals("5 sec", BubblePolicy.autoDismissLabel(5))
        assertEquals("30 sec", BubblePolicy.autoDismissLabel(30))
        assertEquals("Persistent", BubblePolicy.autoDismissLabel(0))
    }

    // ── Side snapping (section 12.2) ──────────────────────────────────────────

    @Test
    fun aStoredLeftPositionAlwaysSnapsLeftEvenWhenTheBubbleWasDraggedRight() {
        assertEquals(
            BubblePolicy.POSITION_LEFT,
            BubblePolicy.snapSide(BubblePolicy.POSITION_LEFT, bubbleCentreX = 900, screenWidth = 1000)
        )
    }

    @Test
    fun aStoredRightPositionAlwaysSnapsRightEvenWhenTheBubbleWasDraggedLeft() {
        assertEquals(
            BubblePolicy.POSITION_RIGHT,
            BubblePolicy.snapSide(BubblePolicy.POSITION_RIGHT, bubbleCentreX = 10, screenWidth = 1000)
        )
    }

    @Test
    fun rememberFollowsTheDragToWhicheverHalfTheCentreIsIn() {
        assertEquals(
            BubblePolicy.POSITION_LEFT,
            BubblePolicy.snapSide(BubblePolicy.POSITION_REMEMBER, bubbleCentreX = 100, screenWidth = 1000)
        )
        assertEquals(
            BubblePolicy.POSITION_RIGHT,
            BubblePolicy.snapSide(BubblePolicy.POSITION_REMEMBER, bubbleCentreX = 900, screenWidth = 1000)
        )
    }

    @Test
    fun anUnknownStoredPositionBehavesLikeRemember() {
        assertEquals(
            BubblePolicy.POSITION_LEFT,
            BubblePolicy.snapSide("nonsense", bubbleCentreX = 100, screenWidth = 1000)
        )
    }

    @Test
    fun aCentreExactlyOnTheMidlineSnapsRightDeterministically() {
        // The tie must resolve the same way every time, otherwise the bubble jitters at the middle.
        assertEquals(
            BubblePolicy.POSITION_RIGHT,
            BubblePolicy.snapSide(BubblePolicy.POSITION_REMEMBER, bubbleCentreX = 500, screenWidth = 1000)
        )
    }

    // ── Snapped x ─────────────────────────────────────────────────────────────

    @Test
    fun leftSnappingLeavesTheConfiguredEdgeMargin() {
        assertEquals(12, BubblePolicy.snappedX(BubblePolicy.POSITION_LEFT, bubbleWidth = 100, screenWidth = 1000, marginPx = 12))
    }

    @Test
    fun rightSnappingLeavesTheConfiguredEdgeMargin() {
        assertEquals(888, BubblePolicy.snappedX(BubblePolicy.POSITION_RIGHT, bubbleWidth = 100, screenWidth = 1000, marginPx = 12))
    }

    @Test
    fun theBubbleAlwaysStaysFullyOnScreen() {
        // A narrow split-screen window with a large bubble and a generous margin.
        val x = BubblePolicy.snappedX(BubblePolicy.POSITION_RIGHT, bubbleWidth = 200, screenWidth = 150, marginPx = 12)
        assertEquals(0, x)

        val left = BubblePolicy.snappedX(BubblePolicy.POSITION_LEFT, bubbleWidth = 200, screenWidth = 150, marginPx = 12)
        assertEquals(0, left)
    }

    @Test
    fun aNegativeMarginIsIgnoredRatherThanPushingTheBubbleOffScreen() {
        assertEquals(0, BubblePolicy.snappedX(BubblePolicy.POSITION_LEFT, 100, 1000, -50))
    }

    // ── Dragging ──────────────────────────────────────────────────────────────

    @Test
    fun verticalClampingNeverLetsTheBubbleGoUnderTheStatusBar() {
        assertEquals(60, BubblePolicy.clampY(y = 0, bubbleHeight = 100, screenHeight = 2000, topInsetPx = 60))
        assertEquals(60, BubblePolicy.clampY(y = -500, bubbleHeight = 100, screenHeight = 2000, topInsetPx = 60))
    }

    @Test
    fun verticalClampingNeverLetsTheBubbleLeaveTheBottomEdge() {
        assertEquals(1900, BubblePolicy.clampY(y = 5000, bubbleHeight = 100, screenHeight = 2000, topInsetPx = 60))
    }

    @Test
    fun aPositionInsideTheScreenIsLeftAlone() {
        assertEquals(500, BubblePolicy.clampY(y = 500, bubbleHeight = 100, screenHeight = 2000, topInsetPx = 60))
        assertEquals(400, BubblePolicy.clampX(x = 400, bubbleWidth = 100, screenWidth = 1000))
    }

    @Test
    fun horizontalClampingKeepsTheBubbleOnScreen() {
        assertEquals(0, BubblePolicy.clampX(x = -100, bubbleWidth = 100, screenWidth = 1000))
        assertEquals(900, BubblePolicy.clampX(x = 5000, bubbleWidth = 100, screenWidth = 1000))
    }

    @Test
    fun aTopInsetLargerThanTheScreenDoesNotProduceAnImpossibleRange() {
        // Degenerate input must still produce a usable value, never an exception.
        val y = BubblePolicy.clampY(y = 10, bubbleHeight = 100, screenHeight = 50, topInsetPx = 100)
        assertEquals(0, y)
    }

    @Test
    fun degenerateScreenSizesNeverThrow() {
        for (screen in listOf(0, 1, -100)) {
            BubblePolicy.snappedX(BubblePolicy.POSITION_LEFT, 100, screen, 8)
            BubblePolicy.clampX(50, 100, screen)
            BubblePolicy.clampY(50, 100, screen, 24)
            BubblePolicy.snapSide(BubblePolicy.POSITION_REMEMBER, 50, screen)
        }
    }

    // ── Tap versus drag ───────────────────────────────────────────────────────

    @Test
    fun aTouchThatBarelyMovesIsStillATap() {
        assertTrue(BubblePolicy.isTap(100, 100, 105, 104, slopPx = 12))
    }

    @Test
    fun aTouchThatMovesMoreThanTheSlopIsADrag() {
        assertFalse(BubblePolicy.isTap(100, 100, 100, 200, slopPx = 12))
        assertFalse(BubblePolicy.isTap(100, 100, 200, 100, slopPx = 12))
        assertTrue(BubblePolicy.isDrag(100, 100, 100, 200, slopPx = 12))
    }

    @Test
    fun theSlopBoundaryIsInclusive() {
        assertTrue(BubblePolicy.isTap(0, 0, 12, 12, slopPx = 12))
        assertFalse(BubblePolicy.isTap(0, 0, 13, 0, slopPx = 12))
    }

    @Test
    fun aZeroSlopMakesAnyMovementADrag() {
        assertTrue(BubblePolicy.isTap(0, 0, 0, 0, slopPx = 0))
        assertFalse(BubblePolicy.isTap(0, 0, 1, 0, slopPx = 0))
    }

    @Test
    fun isDragIsTheExactNegationOfIsTap() {
        val samples = listOf(
            intArrayOf(0, 0, 0, 0),
            intArrayOf(0, 0, 5, 5),
            intArrayOf(10, 20, 300, 20),
            intArrayOf(10, 20, 10, 900)
        )
        for (sample in samples) {
            val tap = BubblePolicy.isTap(sample[0], sample[1], sample[2], sample[3], 12)
            val drag = BubblePolicy.isDrag(sample[0], sample[1], sample[2], sample[3], 12)
            assertEquals(tap, !drag)
        }
    }
}
