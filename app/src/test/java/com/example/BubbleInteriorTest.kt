package com.example

import com.example.pipeline.wipe.BubbleInkMask
import com.example.pipeline.wipe.BubbleInterior
import com.example.pipeline.wipe.BubbleInterior.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for bubble interior geometry.
 *
 * The fixture values are the ones measured off a real failing page (900x1350, grayscale manga)
 * after the on-device detector produced these three boxes:
 *
 *   bubble 1: 291 x 248 px   wall inset 14 px
 *   bubble 2: 324 x 227 px   wall inset 13 px
 *   bubble 3: 330 x 390 px   wall inset 16 px
 *
 * Before this geometry existed the wiper treated each box as a rectangle of text ink, so the
 * bubble's own stroke was classified as text and erased. Every box came back 100% white with **0**
 * of its stroke pixels surviving — 4,794 / 4,946 / 6,316 wall pixels destroyed — and the round
 * bubbles came out as flat white rectangles.
 */
class BubbleInteriorTest {

    // Real box sizes from the failing page.
    private val bubble1W = 291
    private val bubble1H = 248
    private val bubble1Inset = 14

    /**
     * Builds a box with the measured wall profile of bubble 1: page background, then a 6px stroke,
     * then a bright interior. Reads 1.0f (bright) everywhere and 0.0f in the stroke.
     */
    private fun bubble1Luminance(
        leftWall: IntRange = 8..13,
        rightWall: IntRange = (bubble1W - 13)..(bubble1W - 8),
        topWall: IntRange = 5..10,
        bottomWall: IntRange = (bubble1H - 13)..(bubble1H - 8)
    ): FloatArray {
        val lum = FloatArray(bubble1W * bubble1H) { 1.0f }
        for (y in 0 until bubble1H) {
            for (x in leftWall) lum[y * bubble1W + x] = 0.0f
            for (x in rightWall) lum[y * bubble1W + x] = 0.0f
        }
        for (y in topWall) for (x in 0 until bubble1W) lum[y * bubble1W + x] = 0.0f
        for (y in bottomWall) for (x in 0 until bubble1W) lum[y * bubble1W + x] = 0.0f
        return lum
    }

    // ---------------------------------------------------------------- shape selection

    @Test
    fun `round regions are bubbles and get an ellipse`() {
        // All three measured bubbles are round-ish by aspect: 291/248, 324/227, 330/390.
        assertEquals(Shape.ELLIPSE, BubbleInterior.shapeFor(291f / 248f, "speech"))
        assertEquals(Shape.ELLIPSE, BubbleInterior.shapeFor(324f / 227f, "speech"))
        assertEquals(Shape.ELLIPSE, BubbleInterior.shapeFor(330f / 390f, "speech"))
    }

    @Test
    fun `elongated regions are panels and get a rounded rect`() {
        assertEquals(Shape.ROUNDED_RECT, BubbleInterior.shapeFor(4.0f, "speech"))
        assertEquals(Shape.ROUNDED_RECT, BubbleInterior.shapeFor(0.25f, "speech"))
    }

    @Test
    fun `narration and floating text get a plain rect`() {
        assertEquals(Shape.RECT, BubbleInterior.shapeFor(1.0f, "narration"))
        assertEquals(Shape.RECT, BubbleInterior.shapeFor(1.0f, "floating"))
        assertEquals(Shape.RECT, BubbleInterior.shapeFor(1.0f, "side_text"))
    }

    @Test
    fun `bubble type match is case insensitive`() {
        assertEquals(Shape.RECT, BubbleInterior.shapeFor(1.0f, "Floating"))
        assertEquals(Shape.RECT, BubbleInterior.shapeFor(1.0f, "NARRATION"))
    }

    // ---------------------------------------------------------------- measuring the wall

    @Test
    fun `measures the measured wall of the real bubble`() {
        // Left ray crosses at 14, right at 13, top at 11, bottom at 13 -> max 14.
        assertEquals(bubble1Inset, BubbleInterior.measureWallInset(bubble1Luminance(), bubble1W, bubble1H))
    }

    @Test
    fun `uses the maximum of the four rays so no wall is ever clipped`() {
        // A lopsided bubble: three ordinary 2px walls hugging their own edge, but the top wall
        // sits 40px inside. The deep ray must win, or the wiper would eat 40px of the interior's
        // top — and, worse, would have already clipped that wall.
        val lum = bubble1Luminance(
            leftWall = 2..3,
            rightWall = (bubble1W - 4)..(bubble1W - 3),
            topWall = 40..45,
            bottomWall = (bubble1H - 4)..(bubble1H - 3)
        )
        assertEquals(46, BubbleInterior.measureWallInset(lum, bubble1W, bubble1H))
    }

    @Test
    fun `a region with no wall at all reports zero`() {
        val lum = FloatArray(bubble1W * bubble1H) { 1.0f }
        assertEquals(0, BubbleInterior.measureWallInset(lum, bubble1W, bubble1H))
    }

    @Test
    fun `a fully dark region reports zero rather than swallowing the box`() {
        val lum = FloatArray(bubble1W * bubble1H) { 0.0f }
        assertEquals(0, BubbleInterior.measureWallInset(lum, bubble1W, bubble1H))
    }

    @Test
    fun `a wall covering most of the box is rejected as artwork`() {
        // Dark for 90% of the width: not a stroke, so it must not be treated as one.
        val lum = FloatArray(bubble1W * bubble1H) { 1.0f }
        for (y in 0 until bubble1H) {
            for (x in 0 until (bubble1W * 9) / 10) lum[y * bubble1W + x] = 0.0f
        }
        // Clamped to min(width, height) / 3 == 82.
        assertEquals(bubble1H / 3, BubbleInterior.measureWallInset(lum, bubble1W, bubble1H))
    }

    @Test
    fun `degenerate sizes are handled without throwing`() {
        assertEquals(0, BubbleInterior.measureWallInset(FloatArray(0), 0, 0))
        assertEquals(0, BubbleInterior.measureWallInset(FloatArray(10), 5, 5))
    }

    // ---------------------------------------------------------------- the interior mask

    @Test
    fun `the mask excludes every wall pixel of the real bubble`() {
        val mask = BubbleInterior.interiorMask(bubble1W, bubble1H, Shape.ELLIPSE, bubble1Inset)
        val midY = bubble1H / 2

        // The stroke occupies x 8..13 on the centre row. Not one of those may be inside the
        // mask — this is the exact defect that turned the bubble into a white rectangle.
        for (x in 8..13) {
            assertFalse("wall pixel x=$x must be preserved", mask[midY * bubble1W + x])
        }
        // Interior begins immediately after the stroke.
        assertTrue("interior must start at the stroke's inner edge", mask[midY * bubble1W + 14])
    }

    @Test
    fun `the mask protects the box corners where artwork lives`() {
        val mask = BubbleInterior.interiorMask(bubble1W, bubble1H, Shape.ELLIPSE, bubble1Inset)
        for (corner in listOf(0, bubble1W - 1)) {
            assertFalse(mask[0 * bubble1W + corner])
            assertFalse(mask[(bubble1H - 1) * bubble1W + corner])
        }
    }

    @Test
    fun `the mask covers the centre of the bubble`() {
        val mask = BubbleInterior.interiorMask(bubble1W, bubble1H, Shape.ELLIPSE, bubble1Inset)
        assertTrue(mask[(bubble1H / 2) * bubble1W + bubble1W / 2])
    }

    @Test
    fun `an un-inset ellipse fills about pi over four of its box`() {
        val mask = BubbleInterior.interiorMask(bubble1W, bubble1H, Shape.ELLIPSE, 0)
        val ratio = mask.count { it }.toDouble() / (bubble1W * bubble1H)
        // 78.5% for a true ellipse; a rectangle would be 100%, which is what the bug produced.
        assertTrue("ellipse should fill ~78.5% of its box, got ${"%.3f".format(ratio)}", ratio in 0.77..0.80)
    }

    @Test
    fun `a bigger inset always yields a smaller mask`() {
        val counts = listOf(0, 5, 14, 30).map { inset ->
            BubbleInterior.interiorMask(bubble1W, bubble1H, Shape.ELLIPSE, inset).count { it }
        }
        assertEquals(counts.sortedDescending(), counts)
    }

    @Test
    fun `an oversized inset is clamped rather than collapsing the bubble`() {
        // The inset is clamped to a third of the short side, so a wild measurement cannot erase
        // the interior entirely and leave the wiper with nothing to work on.
        val mask = BubbleInterior.interiorMask(20, 10, Shape.ELLIPSE, 12)
        assertTrue(mask.any { it })

        // Only a genuinely degenerate box produces an empty mask.
        assertFalse(BubbleInterior.interiorMask(0, 0, Shape.ELLIPSE, 12).any { it })
    }

    @Test
    fun `rect interiors respect their inset`() {
        val mask = BubbleInterior.interiorMask(100, 60, Shape.RECT, 10)
        assertTrue(mask[30 * 100 + 50])
        assertFalse(mask[30 * 100 + 9])
        assertFalse(mask[30 * 100 + 91])
        assertFalse(mask[9 * 100 + 50])
        assertFalse(mask[51 * 100 + 50])
    }

    @Test
    fun `rounded rect interiors clear the corners but keep the edges`() {
        val mask = BubbleInterior.interiorMask(200, 120, Shape.ROUNDED_RECT, 0)
        assertTrue(mask[60 * 200 + 100])       // centre
        assertTrue(mask[60 * 200 + 0])         // mid-left edge
        assertTrue(mask[0 * 200 + 100])        // top-centre
        assertFalse(mask[0 * 200 + 0])         // corner
        assertFalse(mask[119 * 200 + 199])     // opposite corner
    }

    @Test
    fun `mask is the requested size`() {
        assertEquals(0, BubbleInterior.interiorMask(0, 0, Shape.ELLIPSE, 2).size)
        assertEquals(291 * 248, BubbleInterior.interiorMask(291, 248, Shape.ELLIPSE, 14).size)
    }

    // ---------------------------------------------------------------- luminance

    @Test
    fun `luminance maps the extremes correctly`() {
        assertEquals(1.0f, BubbleInterior.luminance(0xFFFFFFFF.toInt()), 0.001f)
        assertEquals(0.0f, BubbleInterior.luminance(0xFF000000.toInt()), 0.001f)
        assertEquals(0.299f, BubbleInterior.luminance(0xFFFF0000.toInt()), 0.001f)
        assertEquals(0.587f, BubbleInterior.luminance(0xFF00FF00.toInt()), 0.001f)
        assertEquals(0.114f, BubbleInterior.luminance(0xFF0000FF.toInt()), 0.001f)
    }

    @Test
    fun `a mid grey reads as mid grey`() {
        assertEquals(0.5f, BubbleInterior.luminance(0xFF808080.toInt()), 0.01f)
    }

    @Test
    fun `the ink threshold catches the compression noise a loose threshold leaves behind`() {
        // Sampled interior on the failing page is clean white; the text sits near black.
        val bgLum = BubbleInterior.luminance(0xFFFFFFFF.toInt())
        val textLum = BubbleInterior.luminance(0xFF101010.toInt())
        // Measured inside the bubbles of the real page: the ringing left beside the erased glyphs
        // reads 0.92 - 0.94, i.e. 16 - 20/255 off white and just visible.
        val speckleLum = 0.9373f
        // ...and the flat JPEG noise of an empty bubble reads within 1 - 2/255, i.e. invisible.
        val noiseLum = 0.99f

        val tolerance = BubbleInkMask.INK_TOLERANCE

        assertTrue("text must be caught", textLum < bgLum - tolerance)
        assertTrue("speckle must be caught or it survives as visible dirt", speckleLum < bgLum - tolerance)
        assertFalse("invisible noise must be left alone", noiseLum < bgLum - tolerance)
    }
}
