package com.example

import com.example.pipeline.wipe.MaskComposite
import com.example.pipeline.wipe.WipeTier
import com.example.pipeline.wipe.WipeTierMode
import com.example.pipeline.wipe.WipeTierStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Composite contract for the learned-mask wipe (prompt 20261118, Step 1):
 * the output is the original everywhere except inside the erase mask,
 * by construction — the Commander's "original laid back on top" test.
 */
class MaskCompositeTest {

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun eraseMaskIsDilatedSegInsideBoxesOnly() {
        val w = 20; val h = 20
        val seg = BooleanArray(w * h)
        seg[5 * w + 5] = true   // inside the box
        seg[15 * w + 15] = true // outside the box
        val erase = MaskComposite.eraseMask(seg, w, h, listOf(floatArrayOf(0f, 0f, 0.5f, 0.5f)))
        assertTrue(erase[5 * w + 5])
        assertTrue(erase[5 * w + 7])  // dilation reaches 2px
        assertFalse(erase[15 * w + 15])
        assertFalse(erase[10 * w + 10])
    }

    @Test
    fun nearestCompositeLeavesOutsideMaskBitIdentical() {
        val w = 10; val h = 10
        val px = IntArray(w * h) { gray((it * 7) % 256) }
        val erase = BooleanArray(w * h)
        for (y in 3..6) for (x in 3..6) erase[y * w + x] = true
        val out = MaskComposite.compositeNearestWH(px, w, h, erase)
        for (i in px.indices) if (!erase[i]) assertEquals("outside mask changed at $i", px[i], out[i])
        // Nearest surviving colour: the centre takes a border colour, not black.
        assertTrue(out[4 * w + 4] != gray(0))
    }

    @Test
    fun nearestCompositeDoesNotMutateInput() {
        val w = 6; val h = 6
        val px = IntArray(w * h) { gray(100 + it) }
        val copy = px.copyOf()
        val erase = BooleanArray(w * h) { it == 7 }
        MaskComposite.compositeNearestWH(px, w, h, erase)
        assertTrue(px.contentEquals(copy))
    }

    @Test
    fun compositeOnlyMaskedCopiesFillOnlyInside() {
        val base = IntArray(9) { gray(10) }
        val fill = IntArray(9) { gray(200) }
        val erase = BooleanArray(9); erase[4] = true
        val out = MaskComposite.compositeOnlyMasked(base, fill, erase)
        assertEquals(gray(200), out[4])
        assertEquals(gray(10), out[0])
    }

    @Test
    fun thresholdResizeKeepsStrongSignalDropsWeak() {
        val src = floatArrayOf(0.9f, 0.1f, 0.1f, 0.9f)
        val out = MaskComposite.thresholdResize(src, 2, 2, 2, 2)
        assertTrue(out[0]); assertFalse(out[1]); assertFalse(out[2]); assertTrue(out[3])
    }

    @Test
    fun tierResolutionHonoursModeAndCapability() {
        assertEquals(WipeTier.STANDARD, WipeTierStore.resolve(WipeTierMode.STANDARD, 12_000, true))
        assertEquals(WipeTier.BEST, WipeTierStore.resolve(WipeTierMode.BEST, 2_000, false))
        assertEquals(WipeTier.BEST, WipeTierStore.resolve(WipeTierMode.AUTO, 8_000, true))
        assertEquals(WipeTier.STANDARD, WipeTierStore.resolve(WipeTierMode.AUTO, 8_000, false))
        assertEquals(WipeTier.STANDARD, WipeTierStore.resolve(WipeTierMode.AUTO, 4_000, true))
    }
}
