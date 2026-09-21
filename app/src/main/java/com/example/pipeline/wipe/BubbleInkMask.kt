package com.example.pipeline.wipe

import kotlin.math.max
import kotlin.math.min

/**
 * The wipe decision, with no Android types involved.
 *
 * This is deliberately pure so the *same* code that runs on the phone can be exercised on a
 * development machine against real pages. Android's `Bitmap` only ever hands it an `IntArray` of
 * ARGB pixels, so a desktop harness can load a JPEG with `ImageIO`, call straight into here, and
 * be testing the shipped logic rather than a reimplementation of it.
 */
object BubbleInkMask {

    /**
     * How far a pixel must sit from the interior colour before it counts as content.
     *
     * Set just above the visibility floor: a 0.02 deviation is 5/255, which on white is
     * indistinguishable from white. A looser value buys no safety — the bubble wall is protected
     * by the interior mask, not by this threshold — it only leaves the JPEG ringing around every
     * erased glyph behind as speckle inside the bubble.
     */
    const val INK_TOLERANCE = 0.02f

    /** Below this, there is no text worth masking and the caller should use a shape wipe. */
    private const val MIN_TEXT_PIXELS = 10

    /** Luminance at or below which a sampled interior is treated as a dark (inverted) bubble. */
    private const val INVERTED_BG_LUMINANCE = 0.40f

    /**
     * What the wiper should do with one region.
     *
     * @param wallInset measured distance from the region edge to the inside of the stroke.
     * @param interior pixels the wiper is allowed to touch at all.
     * @param wipe pixels to repaint with the sampled interior colour.
     * @param usedInkMask false when no text was found, in which case `wipe` is empty and the
     *   caller should fall back to wiping a shape.
     */
    data class Plan(
        val wallInset: Int,
        val interior: BooleanArray,
        val wipe: BooleanArray,
        val usedInkMask: Boolean
    )

    /**
     * The detector's own box, and where that box sits inside the region being planned.
     *
     * The region can be wider than the box, because a stroke clipped by a tight box cannot close
     * and so cannot be recognised as a wall at all. Anything that belongs to the *detector's* box —
     * the fill colour, the fitted-shape fallback — must therefore be decided in this frame rather
     * than the region's. A box-relative inset applied to a widened region silently produces a
     * larger shape; that is what cost one bubble the bottom arc of its outline.
     */
    data class BoxFrame(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val inset: Int
    )

    /**
     * Decide the wipe for a single region of ARGB pixels laid out row-major.
     *
     * @param pixels the region, `width * height` ARGB values.
     * @param bgColor the sampled interior colour, used as both the ink reference and the fill.
     * @param shape the interior shape chosen for this region.
     * @param densityScale scales kernel sizes with image resolution.
     */
    fun plan(
        pixels: IntArray,
        width: Int,
        height: Int,
        bgColor: Int,
        shape: BubbleInterior.Shape,
        densityScale: Float,
        box: BoxFrame
    ): Plan {
        val count = width * height
        require(count > 0) { "region must not be empty" }
        require(pixels.size >= count) { "need $count pixels, got ${pixels.size}" }

        val luminance = FloatArray(count) { BubbleInterior.luminance(pixels[it]) }

        // The fitted-shape fallback is measured against the box the detector drew, never against the
        // region this plan runs on. The caller widens the region so that a stroke clipped by a tight
        // box can still be recognised as a wall — but an inset re-measured out there is not the same
        // inset at all: the ray walks further before it meets the stroke, and it can meet different
        // ink on the way in. That difference is not academic; it silently enlarged the fallback shape
        // and ate the bottom arc off a bubble that the unwidened measurement had framed correctly.
        val wallInset = if (box.inset > 0) {
            box.inset
        } else {
            // No wall found — text sitting straight on artwork. Keep a scaled floor so the
            // geometric fallback below still clears its own edge cleanly.
            max(4f * densityScale, 0.05f * min(box.width, box.height)).toInt()
        }

        // Only the interior may be modified. The stroke, the bubble tail, and whatever artwork the
        // box corners clipped all live outside this mask and are never touched.
        //
        // Prefer the interior the image itself encloses: it follows a spiked or scalloped wall that
        // a fitted ellipse slices straight through, and it cannot contain the stroke at all. When no
        // wall closes inside the region this falls back to the fitted shape, so behaviour degrades
        // to exactly what shipped before rather than to a guess.
        val enclosed = EnclosedInterior.measure(luminance, width, height)
        val interior = if (enclosed.found) {
            enclosed.mask
        } else {
            // Fitted in the detector's own box and then lifted into the region. An inset is only
            // meaningful against the box it was measured in: fitting it to the wider region scales
            // the shape up along with the widening, and wipes straight over the stroke it exists to
            // protect. A box-relative inset is not a region-relative one, and the difference is not
            // the margin — the ray also stops on whatever ink it happens to meet on the way in.
            val fitted = BubbleInterior.interiorMask(box.width, box.height, shape, wallInset)
            BooleanArray(count) { i ->
                val x = (i % width) - box.left
                val y = (i / width) - box.top
                x in 0 until box.width && y in 0 until box.height && fitted[y * box.width + x]
            }
        }

        val bgLum = BubbleInterior.luminance(bgColor)
        val isInverted = bgLum <= INVERTED_BG_LUMINANCE

        val ink = BooleanArray(count)
        var inkCount = 0
        for (i in 0 until count) {
            if (!interior[i]) continue
            val lum = luminance[i]
            val isInk = if (isInverted) {
                lum > bgLum + INK_TOLERANCE
            } else {
                lum < bgLum - INK_TOLERANCE
            }
            if (isInk) {
                ink[i] = true
                inkCount++
            }
        }

        if (inkCount < MIN_TEXT_PIXELS) {
            return Plan(wallInset, interior, BooleanArray(count), false)
        }

        // Grow the ink by 1-2px to absorb anti-aliasing and furigana. Growth is bounded by the
        // interior mask so it can never bleed onto the wall.
        val radius = (1.5f * densityScale).toInt().coerceIn(1, 3)
        return Plan(wallInset, interior, dilate(ink, interior, width, height, radius), true)
    }

    /** Repaint every set pixel of [mask] with [fillColor]. */
    fun apply(pixels: IntArray, mask: BooleanArray, fillColor: Int) {
        for (i in mask.indices) {
            if (mask[i]) pixels[i] = fillColor
        }
    }

    /**
     * The flat colour of the region's interior, used as both the ink reference and the repaint
     * fill. Sampled 12% in from the edges so the stroke is never picked up, and limited to bright
     * pixels so the text is never picked up either.
     */
    fun sampleInteriorColor(
        pixels: IntArray,
        width: Int,
        height: Int,
        fallback: Int = 0xFFFFFFFF.toInt()
    ): Int {
        if (width <= 0 || height <= 0) return fallback

        val insetX = ((width) * 0.12f).toInt().coerceAtLeast(2)
        val insetY = ((height) * 0.12f).toInt().coerceAtLeast(2)

        val samples = listOf(
            insetX to insetY,
            (width - insetX) to insetY,
            insetX to (height - insetY),
            (width - insetX) to (height - insetY),
            insetX to (height / 2),
            (width - insetX) to (height / 2)
        )

        val rValues = ArrayList<Int>(samples.size)
        val gValues = ArrayList<Int>(samples.size)
        val bValues = ArrayList<Int>(samples.size)

        for ((x, y) in samples) {
            val cx = x.coerceIn(0, width - 1)
            val cy = y.coerceIn(0, height - 1)
            val pixel = pixels[cy * width + cx]
            if (BubbleInterior.luminance(pixel) >= 0.65f) {
                rValues.add((pixel shr 16) and 0xFF)
                gValues.add((pixel shr 8) and 0xFF)
                bValues.add(pixel and 0xFF)
            }
        }

        if (rValues.isEmpty()) return fallback

        rValues.sort()
        gValues.sort()
        bValues.sort()
        val mid = rValues.size / 2
        return (0xFF shl 24) or (rValues[mid] shl 16) or (gValues[mid] shl 8) or bValues[mid]
    }

    /** Square-kernel dilation of [seed], clipped to [bounds]. */
    fun dilate(
        seed: BooleanArray,
        bounds: BooleanArray,
        width: Int,
        height: Int,
        radius: Int
    ): BooleanArray {
        val out = BooleanArray(seed.size)
        if (radius <= 0) return seed.copyOf()
        for (y in 0 until height) {
            val rowOffset = y * width
            val y0 = (y - radius).coerceAtLeast(0)
            val y1 = (y + radius).coerceAtMost(height - 1)
            for (x in 0 until width) {
                if (!seed[rowOffset + x]) continue
                val x0 = (x - radius).coerceAtLeast(0)
                val x1 = (x + radius).coerceAtMost(width - 1)
                for (ny in y0..y1) {
                    val nRow = ny * width
                    for (nx in x0..x1) {
                        val nIdx = nRow + nx
                        if (bounds[nIdx]) out[nIdx] = true
                    }
                }
            }
        }
        return out
    }
}
