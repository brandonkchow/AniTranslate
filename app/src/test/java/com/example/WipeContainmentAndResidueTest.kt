package com.example

import com.example.pipeline.wipe.BubbleInkMask
import com.example.pipeline.wipe.BubbleInterior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for:
 * - Finding 1 (wipe containment): structure ink inside a measured enclosure must not be wiped
 *   merely for being inside the enclosure, while glyph ink beside it is wiped.
 * - Finding 3 (wipe residue): interior color sampling must not pick shaded/edge pixels near
 *   box corners and paint wiped glyphs as light-grey ghosts on white paper.
 */
class WipeContainmentAndResidueTest {

    private val size = 80
    private val paper = 0xFFFFFFFF.toInt()
    private val ink = 0xFF000000.toInt()

    @Test
    fun `structure ink inside measured enclosure survives while glyph beside it is wiped`() {
        val pixels = IntArray(size * size) { paper }
        // Measured enclosure covers most of the region (simulating an enclosure that
        // over-extended past the bubble wall onto artwork, e.g. page-01 id 3).
        // Compact glyph inside the box (x=22..28, y=22..28)
        for (y in 22 until 28) {
            for (x in 22 until 28) {
                pixels[y * size + x] = ink
            }
        }

        // Long artwork/hair stroke inside the enclosure, outside the box (x=60..63, y=5..75)
        // This is unambiguously structure (spans the region and touches its edge).
        for (y in 5 until 75) {
            for (x in 60 until 63) {
                pixels[y * size + x] = ink
            }
        }

        val box = BubbleInkMask.BoxFrame(left = 10, top = 10, width = 40, height = 40, inset = 0)
        val plan = BubbleInkMask.plan(
            pixels = pixels,
            width = size,
            height = size,
            bgColor = paper,
            shape = BubbleInterior.Shape.ELLIPSE,
            densityScale = 1f,
            box = box
        )

        assertTrue("glyph inside the enclosure must be wiped", plan.wipe[25 * size + 25])
        assertFalse(
            "structure ink outside the box must NOT be wiped",
            plan.wipe[40 * size + 61]
        )
    }

    @Test
    fun `structure-classified ink inside detector box is wiped while outside-box structure and glyph survive`() {
        val pixels = IntArray(size * size) { paper }
        // Tall merged glyph column inside the detector box (x=24..28, y=18..42, height=24 > 0.45*40).
        // GlyphErase classifies this as structure due to its extent, yet it sits inside the box.
        for (y in 18 until 42) {
            for (x in 24 until 28) {
                pixels[y * size + x] = ink
            }
        }

        // Long structure stroke inside the enclosure, outside the detector box (x=60..63, y=5..75).
        for (y in 5 until 75) {
            for (x in 60 until 63) {
                pixels[y * size + x] = ink
            }
        }

        // Compact glyph inside the enclosure, outside the detector box (x=66..71, y=20..25).
        for (y in 20 until 25) {
            for (x in 66 until 71) {
                pixels[y * size + x] = ink
            }
        }

        val box = BubbleInkMask.BoxFrame(left = 10, top = 10, width = 40, height = 40, inset = 0)
        val plan = BubbleInkMask.plan(
            pixels = pixels,
            width = size,
            height = size,
            bgColor = paper,
            shape = BubbleInterior.Shape.ELLIPSE,
            densityScale = 1f,
            box = box
        )

        assertTrue(
            "structure-classified ink inside the detector box must be wiped",
            plan.wipe[30 * size + 26]
        )
        assertFalse(
            "structure ink outside the detector box must NOT be wiped",
            plan.wipe[40 * size + 61]
        )
        assertFalse(
            "glyph outside the detector box must NOT be wiped (box-only wipe)",
            plan.wipe[22 * size + 68]
        )
    }

    @Test
    fun `sampleInteriorColor ignores shaded corner probes and returns paper white`() {
        val w = 100
        val h = 100
        val pixels = IntArray(w * h) { paper }

        // Simulate a white bubble whose 12% inset corner probes (at x=12,88 y=12,50,88)
        // hit anti-aliased wall / shading pixels (~200-223 luminance), while the actual
        // interior paper is white (255).
        val shade = (0xFF shl 24) or (210 shl 16) or (210 shl 8) or 210
        val ix = (w * 0.12f).toInt()
        val iy = (h * 0.12f).toInt()
        val cornerProbes = listOf(
            ix to iy,
            (w - ix) to iy,
            ix to (h - iy),
            (w - ix) to (h - iy),
            ix to (h / 2)
        )
        for ((px, py) in cornerProbes) {
            for (dy in -1..1) {
                for (dx in -1..1) {
                    pixels[(py + dy) * w + (px + dx)] = shade
                }
            }
        }

        val sampled = BubbleInkMask.sampleInteriorColor(pixels, w, h)
        val lum = BubbleInterior.luminance(sampled)
        assertTrue(
            "sampled interior luminance ($lum) must reflect paper white (>= 0.95), not shaded corner probes",
            lum >= 0.95f
        )
    }
}
