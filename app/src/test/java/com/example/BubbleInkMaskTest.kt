package com.example

import com.example.pipeline.wipe.BubbleInkMask
import com.example.pipeline.wipe.BubbleInterior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for wiping one region the detector drew a box around.
 *
 * A component verdict is all-or-nothing, so it has to be conservative about any blob it cannot
 * split. Japanese lettering that leans on the stroke is one 8-connected blob with the wall, the blob
 * is structure, and the lettering survives inside it — a bubble wiped on the left and still Japanese
 * on the right. These cases pin the repair: ink the wall encloses is text by construction, so it is
 * promoted out of structure, while a wall is never inside its own enclosure and keeps the protection
 * it already had.
 */
class BubbleInkMaskTest {

    private val size = 80
    private val count = size * size
    private val paper = 0xFFFFFFFF.toInt()
    private val ink = 0xFF000000.toInt()

    /** Mid-grey: lighter than the paper threshold the wall is measured with, darker than ink. */
    private val grey = 0xFFBFBFBF.toInt()

    private fun region(vararg blocks: IntArray): IntArray {
        val px = IntArray(count) { paper }
        for (b in blocks) {
            for (y in b[1] until b[3]) {
                for (x in b[0] until b[2]) px[y * size + x] = b[4]
            }
        }
        return px
    }

    /** A closed wall: a square ring `left,top,right,bottom`, drawn `thickness` deep. */
    private fun ring(left: Int, top: Int, right: Int, bottom: Int, thickness: Int = 3) = arrayOf(
        intArrayOf(left, top, right, top + thickness, ink),
        intArrayOf(left, bottom - thickness, right, bottom, ink),
        intArrayOf(left, top, left + thickness, bottom, ink),
        intArrayOf(right - thickness, top, right, bottom, ink)
    )

    private fun frame(left: Int, top: Int, width: Int, height: Int) =
        BubbleInkMask.BoxFrame(left = left, top = top, width = width, height = height, inset = 0)

    private fun plan(pixels: IntArray, box: BubbleInkMask.BoxFrame) = BubbleInkMask.plan(
        pixels = pixels,
        width = size,
        height = size,
        bgColor = paper,
        shape = BubbleInterior.Shape.ELLIPSE,
        densityScale = 1f,
        box = box
    )

    /** How many of [mask]'s pixels inside `left,top,right,bottom` are set. */
    private fun countIn(mask: BooleanArray, left: Int, top: Int, right: Int, bottom: Int): Int {
        var n = 0
        for (y in top until bottom) {
            for (x in left until right) if (mask[y * size + x]) n++
        }
        return n
    }

    // A wall that closes around paper, the lettering inside it, and one free-standing glyph that
    // keeps the component classifier in charge. The column is bridged to the stroke by mid-grey
    // pixels, never by contact: the classifier reads that grey as ink, and the enclosure reads the
    // same grey as paper, which is exactly the disagreement the repair turns on.
    private fun bubbleWithLetteringAgainstTheWall(): IntArray = region(
        *ring(20, 20, 60, 60),
        intArrayOf(37, 23, 43, 26, grey), // the bridge, drawn as the lettering's own top rows
        intArrayOf(37, 26, 43, 50, ink), // the column of lettering, leaning on the wall
        intArrayOf(27, 30, 33, 42, ink) // a glyph free of the wall: the classifier's own text
    )

    @Test
    fun `lettering fused to the wall is text, and the wall is not`() {
        val result = plan(bubbleWithLetteringAgainstTheWall(), frame(8, 8, 64, 64))

        assertTrue(
            "the column inside the wall must be wiped: the wall was drawn around it",
            result.wipe[40 * size + 40]
        )
        assertTrue("the column is called text", result.glyph[40 * size + 40])
        assertTrue("the grey bridge is called text", result.glyph[24 * size + 40])
        assertFalse("the wall is never called text", result.glyph[21 * size + 30])
        assertTrue("the wall is still structure", result.structure[21 * size + 30])
        assertEquals(
            "not one wipe pixel may land on the wall",
            0,
            countIn(result.wipe, 20, 20, 60, 23) + countIn(result.wipe, 20, 57, 60, 60) +
                countIn(result.wipe, 20, 23, 23, 57) + countIn(result.wipe, 57, 23, 60, 57)
        )
        assertTrue("the free-standing glyph is wiped too", result.wipe[35 * size + 30])
    }

    @Test
    fun `lettering free of the wall is still wiped`() {
        // The same page with the bridge removed: nothing fuses, so this must not regress.
        val result = plan(
            region(
                *ring(20, 20, 60, 60),
                intArrayOf(37, 30, 43, 50, ink),
                intArrayOf(27, 30, 33, 42, ink)
            ),
            frame(8, 8, 64, 64)
        )

        assertTrue("a detached column is wiped", result.wipe[40 * size + 40])
        assertTrue("a detached glyph is wiped", result.wipe[35 * size + 30])
        assertEquals("the wall stays", 0, countIn(result.wipe, 20, 20, 60, 23))
    }

    @Test
    fun `promotion never reaches outside the box the detector drew`() {
        // A wall that the box cuts in half: the lettering on the far side is outside the region the
        // wiper owns, so the enclosure must not be allowed to reach over and take it.
        val result = plan(
            region(
                *ring(10, 10, 70, 70),
                intArrayOf(60, 30, 66, 50, ink), // inside the wall, outside the box
                intArrayOf(40, 30, 46, 50, ink) // inside the wall, inside the box
            ),
            frame(0, 0, 56, 80)
        )

        assertEquals(
            "no wipe outside the box, however good the enclosure looks",
            0,
            countIn(result.wipe, 56, 0, size, size)
        )
    }

    @Test
    fun `the fitted fallback must not eat a stroke that leaves the region`() {
        // A bar too long to be lettering, running out of the region on both sides, so its ink is
        // connected to the region's own edge: a glyph is an island in paper, a stroke leaves.
        //
        // The fitted fallback is the last guess in this chain. It is fitted from the box rather
        // than measured from the image, and its inset is floored when no stroke was found, so it is
        // the shape that can overshoot its own box. Every count in the harness agrees with the
        // shape that made the mistake — the wall it swallowed reads as interior, so the wall
        // census stays green while the stroke is repainted.
        val result = plan(
            region(intArrayOf(0, 39, size, 43, ink)),
            frame(8, 8, 64, 64)
        )

        assertEquals(
            "a stroke connected to the region's edge is not the fitted shape's to erase",
            0,
            countIn(result.wipe, 20, 38, 60, 44)
        )
    }

    /** The bubble's measured interior, as the segmenter reports it: an ellipse in region pixels. */
    private fun ellipse(cx: Int, cy: Int, rx: Int, ry: Int) = BooleanArray(count) { i ->
        val dx = ((i % size) - cx).toFloat() / rx
        val dy = ((i / size) - cy).toFloat() / ry
        dx * dx + dy * dy <= 1f
    }

    private fun planMeasured(
        pixels: IntArray,
        box: BubbleInkMask.BoxFrame,
        bubbleMask: BooleanArray
    ) = BubbleInkMask.plan(
        pixels = pixels,
        width = size,
        height = size,
        bgColor = paper,
        shape = BubbleInterior.Shape.ELLIPSE,
        densityScale = 1f,
        box = box,
        bubbleMask = bubbleMask
    )

    @Test
    fun `a measured interior bounds the wipe where the box does not`() {
        // The detector's box is not always bounded by the bubble. An over-tall box runs past the
        // bubble's floor and clips the artwork below it, and the classifier then reads that artwork
        // as strokes of its own, calls it lettering, and paints it: the bubble is eaten away from
        // the outside and redrawn at a boundary that was never the bubble's. This is the defect
        // class the measured interior exists to end.
        //
        // The measurement cannot be argued with the way a shape can. It is where the bubble
        // actually is, so nothing outside it may be repainted whatever the classifier believes, and
        // nothing inside it may be left behind. Both halves are asserted below: the strokes the
        // over-tall box clipped must survive, and the lettering inside the bubble must not.
        val pixels = region(
            *ring(20, 20, 60, 50),
            intArrayOf(30, 28, 36, 40, ink), // lettering inside the bubble
            intArrayOf(24, 58, 56, 61, ink), // artwork the over-tall box clipped: a thin stroke,
            intArrayOf(24, 64, 40, 67, ink) // too long and too straight to have been lettering
        )
        val result = planMeasured(
            pixels = pixels,
            box = frame(20, 20, 40, 50), // runs 20 rows past the bubble's floor
            bubbleMask = ellipse(cx = 40, cy = 35, rx = 18, ry = 13)
        )

        assertEquals(
            "artwork below the bubble was repainted, at a boundary the bubble never had",
            0,
            countIn(result.wipe, 20, 51, 60, 70)
        )
        assertEquals(
            "the overrun past the bubble was reported as interior",
            0,
            countIn(result.interior, 20, 51, 60, 70)
        )
        assertTrue(
            "lettering inside the measured bubble was left behind",
            countIn(result.wipe, 30, 28, 36, 40) > 0
        )
    }

    @Test
    fun `without a measured interior the box remains the whole authority`() {
        // The no-mask path must stay bit-identical to what shipped: everything the box encloses is
        // still the authority, including the overrun. This pins that the mask is additive and that
        // the previous behaviour was not quietly narrowed for callers that have no segmenter.
        val pixels = region(
            *ring(20, 20, 60, 50),
            intArrayOf(30, 28, 36, 40, ink)
        )
        val result = plan(pixels, frame(20, 20, 40, 50))

        // Every pixel of the box is interior, overrun included: with no measurement there is nothing
        // else the authority could be, so this is the exact width of the path that shipped.
        assertEquals(
            "the box stopped being the authority when no measurement was supplied",
            40 * 50,
            countIn(result.interior, 20, 20, 60, 70)
        )
    }

    @Test
    fun `two-lobe bubble wipes ink in both lobes when interior covers both, leaving wall stroke untouched`() {
        // Synthetic two-lobe page: two lobes joined by an open neck.
        // A wall stroke encloses both lobes, with glyph strokes in each lobe.
        // The detector's box covers ONLY the top lobe.
        // plan() must wipe ink in BOTH lobes when bubbleMask covers both, leaving the wall stroke untouched.
        val w = 80
        val h = 80
        val pixels = IntArray(w * h) { paper }
        val bubbleMask = BooleanArray(w * h)

        val wallPixels = mutableListOf<Int>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dTop = (x - 40) * (x - 40) + (y - 25) * (y - 25)
                val dBottom = (x - 40) * (x - 40) + (y - 55) * (y - 55)
                val isNeck = x in 33..47 && y in 25..55
                val isInside = dTop <= 16 * 16 || dBottom <= 16 * 16 || isNeck
                val isWall = (dTop in (16 * 16)..(19 * 19) || dBottom in (16 * 16)..(19 * 19) ||
                    ((x in 30..32 || x in 48..50) && y in 25..55)) && !isInside

                if (isInside) {
                    bubbleMask[y * w + x] = true
                } else if (isWall) {
                    pixels[y * w + x] = ink
                    wallPixels.add(y * w + x)
                }
            }
        }

        // Draw glyph strokes in top lobe (rows 22..28, cols 38..42)
        for (y in 22..28) {
            for (x in 38..42) pixels[y * w + x] = ink
        }
        // Draw glyph strokes in bottom lobe (rows 52..58, cols 38..42)
        for (y in 52..58) {
            for (x in 38..42) pixels[y * w + x] = ink
        }

        // The detector's box covers ONLY the top lobe
        val box = BubbleInkMask.BoxFrame(left = 20, top = 10, width = 40, height = 30, inset = 0)
        val result = BubbleInkMask.plan(
            pixels = pixels,
            width = w,
            height = h,
            bgColor = paper,
            shape = BubbleInterior.Shape.ROUNDED_RECT,
            densityScale = 1f,
            box = box,
            bubbleMask = bubbleMask
        )

        assertTrue("must use ink mask", result.usedInkMask)

        // Ink in top lobe must be wiped
        assertTrue("top lobe glyph must be wiped", result.wipe[25 * w + 40])
        // Ink in bottom lobe must be wiped
        assertTrue("bottom lobe glyph must be wiped", result.wipe[55 * w + 40])

        // Wall stroke must be untouched
        for (wp in wallPixels) {
            assertFalse("wall stroke at pixel $wp must be left untouched", result.wipe[wp])
        }

        // Dilate bounds and wipe must never reach outside bubbleMask
        for (i in 0 until w * h) {
            if (!bubbleMask[i]) {
                assertFalse("wipe must never reach outside bubbleMask at $i", result.wipe[i])
            }
        }
    }

    @Test
    fun `when bubbleMask is null plan output is identical to pre-change algorithm`() {
        val testRegions = listOf(
            bubbleWithLetteringAgainstTheWall() to frame(8, 8, 64, 64),
            region(*ring(20, 20, 60, 60), intArrayOf(37, 30, 43, 50, ink), intArrayOf(27, 30, 33, 42, ink)) to frame(8, 8, 64, 64),
            region(*ring(20, 20, 60, 50), intArrayOf(30, 28, 36, 40, ink)) to frame(20, 20, 40, 50)
        )

        for ((px, box) in testRegions) {
            val resultWithDefaultNull = plan(px, box)
            val resultWithExplicitNull = BubbleInkMask.plan(
                pixels = px,
                width = size,
                height = size,
                bgColor = paper,
                shape = BubbleInterior.Shape.ELLIPSE,
                densityScale = 1f,
                box = box,
                bubbleMask = null
            )

            assertEquals(resultWithDefaultNull.wallInset, resultWithExplicitNull.wallInset)
            assertEquals(resultWithDefaultNull.usedInkMask, resultWithExplicitNull.usedInkMask)
            assertEquals(resultWithDefaultNull.glyphPixels, resultWithExplicitNull.glyphPixels)
            assertEquals(resultWithDefaultNull.structurePixels, resultWithExplicitNull.structurePixels)
            assertTrue(resultWithDefaultNull.wipe.contentEquals(resultWithExplicitNull.wipe))
            assertTrue(resultWithDefaultNull.interior.contentEquals(resultWithExplicitNull.interior))
            assertTrue(resultWithDefaultNull.glyph.contentEquals(resultWithExplicitNull.glyph))
            assertTrue(resultWithDefaultNull.structure.contentEquals(resultWithExplicitNull.structure))
        }
    }
}
