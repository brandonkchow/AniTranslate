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
}
