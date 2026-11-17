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
 * Wipe 1b: a dark component floating inside the box is text and is erased
 * whole; a component touching the box border is the bubble outline and
 * survives. Lettering bridged to the wall only by anti-alias fringe still
 * separates at its dark core, so the column goes and the wall stays.
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
        // The detector box is drawn around the bubble, so the wall touches
        // its border (frame coincides with the ring here).
        val result = plan(bubbleWithLetteringAgainstTheWall(), frame(20, 20, 40, 40))

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
            frame(20, 20, 40, 40)
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

    @Test
    fun `a leaking enclosure paints zero pixels outside the detector box`() {
        // Wipe-reset falsifier (Commander 2026-11-16): an enclosure measured from
        // the page leaks past the bubble wall onto artwork (page-01 id 3 shape).
        // The leaking enclosure is constructed here as the area it would have
        // covered; plan() no longer consumes any enclosure, so none of that
        // outside-box area may be painted, while glyph ink inside the box is.
        val leakingEnclosure = BooleanArray(count) { i ->
            val x = i % size; val y = i / size
            x in 5 until 75 && y in 5 until 75
        }
        val pixels = region(
            intArrayOf(22, 22, 28, 28, ink), // glyph inside the box
            intArrayOf(60, 5, 63, 75, ink), // artwork stroke the leak would cover
            intArrayOf(66, 20, 71, 25, ink) // glyph-shaped ink outside the box, inside the leak
        )
        val result = plan(pixels, frame(10, 10, 40, 40))
        assertTrue("glyph inside the box must be wiped", result.wipe[25 * size + 25])
        var outside = 0
        for (i in 0 until count) {
            val x = i % size; val y = i / size
            val inBox = x in 10 until 50 && y in 10 until 50
            if (!inBox && (result.wipe[i] || result.interior[i])) outside++
            if (leakingEnclosure[i] && !inBox && result.wipe[i]) outside++
        }
        assertEquals("zero painted pixels outside the detector box", 0, outside)
        assertFalse("artwork inside the leaking enclosure must survive", result.wipe[40 * size + 61])
    }

    @Test
    fun `a vertical glyph column bridged only by grey is wiped, and art it bridges to is not`() {
        // Page-6 shape in miniature (units 3, 13, 14): a tall narrow box, dark
        // glyph blocks stacked vertically, joined to each other and to a dark
        // artwork block at the box edge only by mid-grey anti-alias pixels.
        // The grey counts as ink at the wipe tolerance, so the loose mask is
        // one tall component the extent rule protects as structure; the dark
        // cores are separate glyphs, and the artwork core touches the edge.
        val result = plan(
            region(
                intArrayOf(37, 8, 43, 14, ink), // glyph block
                intArrayOf(39, 14, 41, 18, grey), // grey bridge
                intArrayOf(37, 18, 43, 24, ink), // glyph block
                intArrayOf(39, 24, 41, 28, grey), // grey bridge
                intArrayOf(37, 28, 43, 34, ink), // glyph block
                intArrayOf(35, 34, 41, 38, grey), // grey bridge down to the art
                intArrayOf(30, 38, 44, 48, ink), // artwork, reaching the box bottom edge
                intArrayOf(45, 8, 49, 12, ink) // a small free glyph: keeps the classifier in charge, as the specks do on the page
            ),
            frame(30, 5, 20, 43)
        )

        assertTrue("top glyph of the column is wiped", result.wipe[11 * size + 40])
        assertTrue("middle glyph of the column is wiped", result.wipe[21 * size + 40])
        assertTrue("bottom glyph of the column is wiped", result.wipe[31 * size + 40])
        assertTrue("the grey bridge between glyphs is wiped with them", result.glyph[16 * size + 40])
        assertFalse("the artwork block is not wiped", result.wipe[43 * size + 37])
        assertTrue("the artwork block is structure", result.structure[43 * size + 37])
    }

    @Test
    fun `a glyph over a coloured field is filled in that colour, not white`() {
        // Wipe 1b fill test: the field under the glyph is a flat colour that
        // is not white. The wiped glyph pixels must take their nearest
        // surviving colour — the field — never a sampled white.
        val field = (0xFF shl 24) or (0xC8 shl 16) or (0xD8 shl 8) or 0xE8
        val n = 40
        val pixels = IntArray(n * n) { field }
        for (y in 16 until 24) {
            for (x in 16 until 24) pixels[y * n + x] = ink
        }
        val result = BubbleInkMask.plan(
            pixels = pixels,
            width = n,
            height = n,
            bgColor = field,
            shape = BubbleInterior.Shape.ELLIPSE,
            densityScale = 1f,
            box = BubbleInkMask.BoxFrame(0, 0, n, n, inset = 0)
        )
        assertTrue("the floating glyph must be in the wipe mask", result.wipe[20 * n + 20])

        BubbleInkMask.repaintNearest(pixels, n, n, result.wipe, paper)
        assertEquals("glyph centre is filled with the field colour", field, pixels[20 * n + 20])
        assertTrue("no wiped pixel was filled white", pixels.none { it == paper })
    }

    @Test
    fun `a floating dark component is erased whole while a border-touching one survives`() {
        val pixels = region(
            intArrayOf(10, 30, 14, 55, ink), // touches the box's left border
            intArrayOf(35, 25, 41, 55, ink) // tall floating bar inside the box
        )
        val result = plan(pixels, frame(10, 10, 50, 60))
        assertTrue("floating bar centre is wiped", result.wipe[40 * size + 38])
        assertFalse("border-touching bar is not wiped", result.wipe[40 * size + 12])
        assertTrue("border-touching bar is structure", result.structure[40 * size + 12])
    }
}
