package com.example

import com.example.pipeline.wipe.BubbleInterior
import com.example.pipeline.wipe.EnclosedInterior
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The interior must come from what the image encloses, not from a shape fitted to the box.
 *
 * A fitted ellipse is only right when the bubble is an ellipse. Real pages carry spiked, scalloped
 * and starburst bubbles, and for those the fit either slices through the stroke or stops short of
 * the text. These tests pin both failures, and pin the property that replaces them: the stroke is
 * contiguous with the page, so a measured interior can never contain it.
 */
class EnclosedInteriorTest {

    private val size = 141
    private val centreX = size / 2
    private val centreY = size / 2

    private fun paper() = FloatArray(size * size) { 1f }

    /** Draws a closed stroke at the distance `radius(theta)` from the centre; returns its pixels. */
    private fun stroke(lum: FloatArray, thickness: Double, radius: (Double) -> Double): BooleanArray {
        val wall = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = (x - centreX).toDouble()
                val dy = (y - centreY).toDouble()
                val distance = hypot(dx, dy)
                if (distance == 0.0) continue
                if (abs(distance - radius(atan2(dy, dx))) <= thickness / 2.0) {
                    wall[y * size + x] = true
                    lum[y * size + x] = 0f
                }
            }
        }
        return wall
    }

    /** Draws an ink glyph and returns its pixels. */
    private fun glyph(lum: FloatArray, left: Int, top: Int, right: Int, bottom: Int): BooleanArray {
        val ink = BooleanArray(size * size)
        for (y in top..bottom) {
            for (x in left..right) {
                ink[y * size + x] = true
                lum[y * size + x] = 0f
            }
        }
        return ink
    }

    private fun overlaps(a: BooleanArray, b: BooleanArray) = a.indices.any { a[it] && b[it] }

    private fun count(mask: BooleanArray) = mask.count { it }

    @Test
    fun `encloses the glyph and never the stroke`() {
        val lum = paper()
        val wall = stroke(lum, thickness = 5.0) { 50.0 }
        glyph(lum, left = 64, top = 64, right = 76, bottom = 76)

        val interior = EnclosedInterior.measure(lum, size, size)

        assertTrue("a closed oval must be measurable", interior.found)
        assertFalse("the stroke must never be inside the interior", overlaps(interior.mask, wall))
        assertTrue(
            "paper at the centre belongs to the interior",
            interior.mask[centreY * size + centreX]
        )
        assertTrue("a glyph the wall closes around must be wiped", interior.mask[70 * size + 70])
    }

    @Test
    fun `no fitted inset can survive a spiked wall`() {
        val lum = paper()
        // Six lobes: a shout bubble, spiked in every direction.
        val wall = stroke(lum, thickness = 5.0) { 34.0 + 14.0 * sin(6.0 * it) }

        // Whatever inset the measurement returns, a shape fitted to the box pokes through either the
        // peaks or the valleys. This is the failure a centre-ray inset cannot see, and the reason
        // the fitted shape is now only a fallback.
        for (inset in 5..45) {
            val fitted = BubbleInterior.interiorMask(size, size, BubbleInterior.Shape.ELLIPSE, inset)
            assertTrue(
                "a fitted ellipse at inset $inset should cut this wall",
                overlaps(fitted, wall)
            )
        }

        val interior = EnclosedInterior.measure(lum, size, size)
        assertTrue("a closed spiked wall must be measurable", interior.found)
        assertFalse(
            "the measured interior must never touch a spiked stroke",
            overlaps(interior.mask, wall)
        )
        assertTrue(
            "the interior must still hold the middle of the bubble",
            interior.mask[centreY * size + centreX]
        )
    }

    @Test
    fun `seals a hairline break and wipes the bubble, not the page`() {
        val lum = paper()
        stroke(lum, thickness = 5.0) { 50.0 }
        // Cut a one-pixel paper channel straight out of the bubble and off the edge of the region,
        // so the inside is no longer sealed off from the page.
        for (x in centreX until size) lum[centreY * size + x] = 1f

        val interior = EnclosedInterior.measure(lum, size, size)

        // A break this narrow is a wall defect, not an absent wall: sealing it and measuring is
        // better than refusing and letting a fitted shape guess. This is the case that failed at
        // every measurement margin from 13 to 80 px on a real page.
        assertTrue("a hairline break must be sealed, not surrendered", interior.found)
        assertTrue("the bubble's own paper must be wiped", interior.mask[centreY * size + centreX])
        assertFalse(
            "page paper outside the wall must survive the sealed measurement",
            interior.mask[centreY * size + centreX + 60]
        )
    }

    @Test
    fun `still refuses an opening too wide to be a wall defect`() {
        val lum = paper()
        val wall = stroke(lum, thickness = 5.0) { 50.0 }
        // A ten-pixel doorway: far wider than any seal in the ladder, so the bubble genuinely is
        // open to the page and there is no interior to measure.
        for (y in centreY - 5..centreY + 4) {
            for (x in centreX until size) lum[y * size + x] = 1f
        }

        val interior = EnclosedInterior.measure(lum, size, size)

        assertFalse("an open bubble has no interior to measure", interior.found)
        assertFalse("a failed measurement must hand back no mask", interior.mask.any { it })
        assertFalse("the seal must not have been used to rescue it", overlaps(interior.mask, wall))
    }

    @Test
    fun `never swallows a neighbouring enclosed area`() {
        val lum = paper()
        stroke(lum, thickness = 5.0) { 50.0 }

        // A second bubble elsewhere in the region: a square stroke with paper and a glyph inside.
        val neighbour = BooleanArray(size * size)
        for (y in 100..130) {
            for (x in 100..130) {
                if (y == 100 || y == 130 || x == 100 || x == 130) {
                    neighbour[y * size + x] = true
                    lum[y * size + x] = 0f
                }
            }
        }
        val neighbourGlyph = glyph(lum, left = 112, top = 112, right = 118, bottom = 118)

        val interior = EnclosedInterior.measure(lum, size, size)

        assertTrue(interior.found)
        // The neighbour's paper is unreachable from this bubble's seed, so it is *enclosed* — but it
        // is not this bubble's interior. Only the stroke's link to the page keeps it out, which is
        // exactly the property a cheaper "everything the flood could not reach" rule would drop.
        assertFalse(
            "a neighbouring bubble's paper is not this bubble's interior",
            interior.mask[115 * size + 115]
        )
        assertFalse(
            "a neighbouring bubble's glyph must not be wiped",
            overlaps(interior.mask, neighbourGlyph)
        )
        assertFalse("the neighbouring stroke must not be wiped", overlaps(interior.mask, neighbour))
    }

    @Test
    fun `reports not found when there is no paper at all`() {
        val lum = FloatArray(size * size) { 0f }

        assertFalse(EnclosedInterior.measure(lum, size, size).found)
    }

    @Test
    fun `never swallows artwork that reaches the edge of the region`() {
        val lum = paper()
        stroke(lum, thickness = 5.0) { 45.0 }
        // Character art running in from the left edge and past the bubble.
        val art = glyph(lum, left = 0, top = 60, right = 30, bottom = 80)

        val interior = EnclosedInterior.measure(lum, size, size)

        assertTrue(interior.found)
        assertFalse("art touching the page is not interior fill", overlaps(interior.mask, art))
    }
}
