package com.example

import com.example.pipeline.wipe.BubbleInkMask
import com.example.pipeline.wipe.GlyphErase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the glyph-layer erase.
 *
 * The classification exists so that a bubble whose wall cannot be measured can still be wiped
 * without a guess. These cases pin the two populations apart: compact ink goes, anything attached to
 * the box edge, spanning the box, or drawn as a long hairline stays.
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
    fun `keeps a long thin stroke that lies inside the box`() {
        val ink = blank().also { m ->
            for (i in 0 until 30) paint(m, 15 + i, 15 + i, 16 + i, 16 + i) // one-pixel diagonal
            paint(m, 20, 44, 26, 50)
        }

        val result = run(ink)

        assertEquals("the hairlines are not text", 36, result.glyphPixels)
        assertEquals("the stroke spans the box, so it is structure", 30, result.structurePixels)
        assertFalse("a point on the stroke is untouched", result.wipe[30 * size + 30])
        assertTrue("the glyph is wiped", result.wipe[47 * size + 23])
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
