package com.example

import com.example.pipeline.wipe.BubbleInkMask
import com.example.pipeline.wipe.GlyphErase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the wipe-1b erase.
 *
 * Inside a bubble there is no shape judgement: a dark component that floats
 * (does not touch the box border) is text and is erased whole, whatever its
 * extent or fill; a component that touches the border is the outline and
 * stays. These cases pin that single rule.
 */
class GlyphEraseTest {

    private val size = 60
    private val count = size * size

    private fun box(width: Int = size, height: Int = size, left: Int = 0, top: Int = 0) =
        BubbleInkMask.BoxFrame(left = left, top = top, width = width, height = height, inset = 0)

    private fun blank() = BooleanArray(count)

    private fun paint(mask: BooleanArray, left: Int, top: Int, right: Int, bottom: Int) {
        for (y in top until bottom) {
            for (x in left until right) mask[y * size + x] = true
        }
    }

    /** Plans exactly as the shipping wiper does: the box is also the region the wiper may touch. */
    private fun run(
        ink: BooleanArray,
        frame: BubbleInkMask.BoxFrame = box(),
        radius: Int = 1
    ): GlyphErase.Result {
        val allowed = BooleanArray(count) { i ->
            val x = (i % size) - frame.left
            val y = (i / size) - frame.top
            x in 0 until frame.width && y in 0 until frame.height
        }
        return GlyphErase.wipe(ink, allowed, size, size, frame, radius)
    }

    private fun wipedIn(mask: BooleanArray, left: Int, top: Int, right: Int, bottom: Int): Int {
        var n = 0
        for (y in top until bottom) {
            for (x in left until right) if (mask[y * size + x]) n++
        }
        return n
    }

    @Test
    fun `erases compact glyphs and keeps a wall that crosses the box`() {
        val ink = blank().also {
            paint(it, 0, 56, size, size) // wall along the bottom edge, reaching both sides
            paint(it, 20, 20, 26, 26) // glyph
            paint(it, 34, 22, 40, 28) // glyph
        }

        val result = run(ink)

        assertEquals("both glyphs are text", 72, result.glyphPixels)
        assertEquals("the wall is structure", 4 * size, result.structurePixels)
        assertEquals("no wipe pixel lands on the wall", 0, wipedIn(result.wipe, 0, 56, size, size))
        assertTrue("first glyph is wiped", result.wipe[22 * size + 22])
        assertTrue("second glyph is wiped", result.wipe[25 * size + 37])
    }

    @Test
    fun `keeps a scalloped wall that runs along the edge`() {
        val ink = blank().also { m ->
            // A drawn arc: three pixels thick and never stepping more than one row per column, which
            // is what makes a scalloped outline a single component on a real page rather than a row
            // of separate bumps the classifier would mistake for glyphs.
            for (x in 0 until size) {
                val y = 52 + (x % 10) / 5
                paint(m, x, y, x + 1, y + 3)
            }
            paint(m, 24, 20, 30, 26)
        }

        val result = run(ink)

        assertEquals("only the glyph is text", 36, result.glyphPixels)
        assertEquals("the scalloped outline is structure", 3 * size, result.structurePixels)
        assertEquals("no scallop is sliced", 0, wipedIn(result.wipe, 0, 52, size, size))
        assertTrue("the glyph is still wiped", result.wipe[23 * size + 27])
    }

    @Test
    fun `keeps small ink that is clipped by the box edge`() {
        val ink = blank().also {
            paint(it, 0, 0, 6, 6) // glyph-sized, but the box cut it — treat as structure
            paint(it, 20, 20, 26, 26)
        }

        val result = run(ink)

        assertEquals(36, result.glyphPixels)
        assertEquals(36, result.structurePixels)
        assertEquals("clipped ink is left alone", 0, wipedIn(result.wipe, 0, 0, 6, 6))
    }

    @Test
    fun `a floating component is erased whole however long and thin it is`() {
        // Wipe 1b: no extent or hairline rule protects a floating component.
        // A long thin stroke floating inside the box is erased whole, like
        // any glyph; only border-touching ink survives.
        val ink = blank().also { m ->
            for (i in 0 until 30) paint(m, 15 + i, 15 + i, 16 + i, 16 + i) // one-pixel diagonal, floating
            paint(m, 20, 44, 26, 50)
        }

        val result = run(ink)

        assertEquals("both floating components are text", 66, result.glyphPixels)
        assertEquals("nothing floats and survives", 0, result.structurePixels)
        assertTrue("a point on the floating stroke is wiped", result.wipe[30 * size + 30])
        assertTrue("the glyph is wiped", result.wipe[47 * size + 23])
    }

    @Test
    fun `a floating component is erased whole while a border-touching one survives`() {
        val ink = blank().also {
            paint(it, 0, 10, 4, 40) // touches the left border: outline-side ink
            paint(it, 24, 18, 30, 42) // tall floating bar: text under the old extent rule
        }

        val result = run(ink)

        assertEquals("the floating bar is erased whole", 24 * 6, result.glyphPixels)
        assertEquals("the border-touching bar survives whole", 30 * 4, result.structurePixels)
        assertTrue("floating bar centre is wiped", result.wipe[30 * size + 27])
        assertEquals("no wipe pixel lands on the border-touching bar", 0, wipedIn(result.wipe, 0, 10, 4, 40))
    }

    @Test
    fun `keeps a floating component in the perimeter ring and erases an interior glyph`() {
        // Bubble fix (Commander 2026-11-17), measured on page-08 id1: a spiky
        // bubble's corner strokes are floating components in the box's outer
        // band, glyph-sized and glyph-shaped; the glyphs sit deep inside.
        // Ring width here is 6% of 60 = 3px.
        val ink = blank().also {
            paint(it, 3, 24, 9, 30) // floating, 3px from the left border: a corner spoke
            paint(it, 24, 24, 30, 30) // interior glyph, 24px from the nearest border
        }

        val result = run(ink)

        assertEquals("only the interior glyph is text", 36, result.glyphPixels)
        assertEquals("the perimeter-ring spoke is structure", 36, result.structurePixels)
        assertEquals("no wipe pixel lands on the spoke", 0, wipedIn(result.wipe, 3, 24, 9, 30))
        assertTrue("the interior glyph is wiped", result.wipe[27 * size + 27])
    }

    @Test
    fun `erases nothing when every component is structure`() {
        val ink = blank().also { paint(it, 0, 0, size, 3) }

        val result = run(ink)

        assertEquals(0, result.glyphPixels)
        assertFalse("no glyphs means no wipe", result.wipe.any { it })
    }

    @Test
    fun `a glyph halo never lands on structure`() {
        val ink = blank().also {
            paint(it, 0, 40, size, 45) // wall
            paint(it, 20, 34, 26, 39) // glyph, one clear row above the wall
        }

        // A 2px halo from the glyph's last row reaches the wall's first row. The clip must hold.
        val result = run(ink, radius = 2)

        assertEquals("the glyph is 6 x 5 pixels of text", 30, result.glyphPixels)
        assertEquals(5 * size, result.structurePixels)
        assertTrue("the halo is active one row below the glyph", result.wipe[39 * size + 22])
        assertEquals("the halo is clipped at the wall", 0, wipedIn(result.wipe, 0, 40, size, 45))
    }

    @Test
    fun `never paints outside the detector's box`() {
        val ink = blank().also {
            paint(it, 2, 2, 8, 8) // ink well outside the box
            paint(it, 24, 24, 30, 30) // a glyph inside it
        }

        val result = run(ink, frame = box(width = 20, height = 20, left = 20, top = 20))

        assertEquals("only the inside glyph is text", 36, result.glyphPixels)
        assertEquals("ink outside the box is structure", 36, result.structurePixels)
        assertEquals("nothing is painted outside the box", 0, wipedIn(result.wipe, 0, 0, 20, size))
        assertTrue("the inside glyph is wiped", result.wipe[26 * size + 26])
    }
}
