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
     * @param glyphPixels ink pixels classified as text, for telemetry. Zero on the enclosed path,
     *   which has no need to separate text from structure.
     * @param structurePixels ink pixels classified as wall, tail or border and left untouched.
     * @param glyph mask of the pixels classified as text; empty on the enclosed path.
     * @param structure mask of the pixels classified as structure; empty on the enclosed path. Both
     *   exist so the desktop harness can hold the decision against the image directly, rather than
     *   inferring it from a luminance threshold that deliberately preserved structure would trip.
     */
    data class Plan(
        val wallInset: Int,
        val interior: BooleanArray,
        val wipe: BooleanArray,
        val usedInkMask: Boolean,
        val glyphPixels: Int = 0,
        val structurePixels: Int = 0,
        val glyph: BooleanArray = BooleanArray(0),
        val structure: BooleanArray = BooleanArray(0)
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

    /** Pixels inside a bound that deviate from the interior colour by more than [INK_TOLERANCE]. */
    private class Ink(val mask: BooleanArray, val count: Int)

    private fun inkWithin(
        luminance: FloatArray,
        bounds: BooleanArray,
        bgLum: Float,
        isInverted: Boolean
    ): Ink {
        val mask = BooleanArray(luminance.size)
        var count = 0
        for (i in bounds.indices) {
            if (!bounds[i]) continue
            val lum = luminance[i]
            val isInk = if (isInverted) {
                lum > bgLum + INK_TOLERANCE
            } else {
                lum < bgLum - INK_TOLERANCE
            }
            if (isInk) {
                mask[i] = true
                count++
            }
        }
        return Ink(mask, count)
    }

    /**
     * Decide the wipe for a single region of ARGB pixels laid out row-major.
     *
     * @param pixels the region, `width * height` ARGB values.
     * @param bgColor the sampled interior colour, used as both the ink reference and the fill.
     * @param shape the interior shape chosen for this region, used only by the fitted fallback.
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

        val bgLum = BubbleInterior.luminance(bgColor)
        val isInverted = bgLum <= INVERTED_BG_LUMINANCE
        val radius = (1.5f * densityScale).toInt().coerceIn(1, 3)

        // The detector's box, lifted into the region's coordinates. Only pixels inside it are ever
        // candidates for repainting: the stroke, the bubble tail, and whatever artwork the box
        // corners clipped all live outside this mask and are never touched.
        val boxMask = BooleanArray(count) { i ->
            val x = (i % width) - box.left
            val y = (i / width) - box.top
            x in 0 until box.width && y in 0 until box.height
        }

        // Preferred: classify the ink inside the detector's box. The box is where the detector says
        // the text is, and the classifier will not paint a pixel it did not identify as a glyph — so
        // the box needs no shape inset from a wall in order to be safe.
        //
        // This has to come ahead of any measured or fitted interior. An interior is a shape *around*
        // the text, and a shape around a text block clips its corners: a plain ellipse leaves the
        // ends of a vertical column outside itself, while every count the wipe can make agrees with
        // the shape that made the mistake, so the wipe reports clean. A fitted interior, reached
        // when no wall closes, is that same guess with nothing to check it. The ink's own answer
        // needs neither, so both shapes now run only when classification finds nothing to act on.
        val boxInk = inkWithin(luminance, boxMask, bgLum, isInverted)
        if (boxInk.count >= MIN_TEXT_PIXELS) {
            val glyph = GlyphErase.wipe(boxInk.mask, boxMask, width, height, box, radius)
            if (glyph.glyphPixels >= MIN_TEXT_PIXELS) {
                // A component verdict is all-or-nothing, so it has to be conservative about any
                // blob it cannot split: Japanese that leans on the stroke is one 8-connected blob
                // with the wall, the blob is structure, and the lettering rides along with the wall
                // that has to be kept. That is a bubble wiped white on the left and still Japanese
                // on the right, with every count in this file agreeing the wipe came out clean.
                //
                // Topology splits what connectivity cannot. The wall is contiguous with the page,
                // so the paper it encloses cannot contain it — ink inside that enclosure, inside
                // the detector's box, is text by construction. Promoting it can therefore only add
                // lettering, never a stroke: the wall is not in the mask being promoted from, it
                // keeps the protection it already had, and the halo below still stops at it.
                val enclosed = EnclosedInterior.measure(luminance, width, height)
                if (enclosed.found) {
                    val inside = inkWithin(luminance, enclosed.mask, bgLum, isInverted)
                    val extra = (0 until count).count { i ->
                        inside.mask[i] && boxMask[i] && !glyph.glyph[i]
                    }
                    if (extra > 0) {
                        val glyphMask = BooleanArray(count) { i ->
                            glyph.glyph[i] || (inside.mask[i] && boxMask[i])
                        }
                        val structureMask = BooleanArray(count) { i ->
                            glyph.structure[i] && !inside.mask[i]
                        }
                        val bounds = BooleanArray(count) { i -> boxMask[i] && !structureMask[i] }
                        return Plan(
                            wallInset = wallInset,
                            interior = boxMask,
                            wipe = dilate(glyphMask, bounds, width, height, radius),
                            usedInkMask = true,
                            glyphPixels = glyphMask.count { it },
                            structurePixels = structureMask.count { it },
                            glyph = glyphMask,
                            structure = structureMask
                        )
                    }
                }
                return Plan(
                    wallInset = wallInset,
                    interior = boxMask,
                    wipe = glyph.wipe,
                    usedInkMask = true,
                    glyphPixels = glyph.glyphPixels,
                    structurePixels = glyph.structurePixels,
                    glyph = glyph.glyph,
                    structure = glyph.structure
                )
            }
        }

        // Next: the interior the image itself encloses. It cannot contain the stroke at all, but it
        // is still a shape around the text, so it is reached only when there was no ink to classify.
        val enclosed = EnclosedInterior.measure(luminance, width, height)
        if (enclosed.found) {
            val ink = inkWithin(luminance, enclosed.mask, bgLum, isInverted)
            if (ink.count < MIN_TEXT_PIXELS) {
                return Plan(wallInset, enclosed.mask, BooleanArray(count), false)
            }
            return Plan(
                wallInset,
                enclosed.mask,
                dilate(ink.mask, enclosed.mask, width, height, radius),
                true
            )
        }

        // Last resort, unchanged from what shipped: the fitted shape in the detector's own box. It
        // is still a guess — the inset is floored when no stroke was found — so it is reached only
        // when the ink itself gave the classifier nothing to work with, and never in preference to
        // a classification that succeeded.
        //
        // A guess is only as good as whatever can be held against it, and this is the one path with
        // nothing: the wall did not close, so no enclosure says where the bubble ends, and reaching
        // here at all means the classifier found under MIN_TEXT_PIXELS of text. Every count that
        // could disagree with the shape is taken from the shape. So hold it against the one thing
        // in this file that was not guessed — connectivity. Ink that runs from the region's own
        // edge into the shape is a stroke, not lettering: a glyph is an island in paper, while a
        // stroke leaves. The fit is therefore clipped back against that ink rather than trusted, so
        // an oversized shape can only ever erase less. Without this, a shape that overshot its box
        // swallowed the stroke, the swallowed ink then read as interior, and the wall census went
        // green while the stroke was repainted — the over-wipe reported as a clean wipe.
        val fitted = BubbleInterior.interiorMask(box.width, box.height, shape, wallInset)
        val unbounded = borderConnectedInk(luminance, width, height, bgLum, isInverted)
        val interior = BooleanArray(count) { i ->
            val x = (i % width) - box.left
            val y = (i / width) - box.top
            x in 0 until box.width && y in 0 until box.height &&
                fitted[y * box.width + x] && !unbounded[i]
        }
        val ink = inkWithin(luminance, interior, bgLum, isInverted)
        if (ink.count < MIN_TEXT_PIXELS) {
            return Plan(wallInset, interior, BooleanArray(count), false)
        }
        return Plan(wallInset, interior, dilate(ink.mask, interior, width, height, radius), true)
    }

    /**
     * Ink that is 8-connected to the region's own edge.
     *
     * This is the one statement about a region that neither a shape nor the classifier can talk it
     * out of. A glyph sits in the middle of a bubble and is an island in paper; a stroke, a tail or
     * a piece of artwork runs from one side of the region to the other. So whatever the flood
     * reaches from the edge is not lettering, and no fitted shape is entitled to repaint it.
     *
     * Seeded from the edge pixels only, so it needs no threshold beyond the ink test the rest of
     * this file already uses, and it never consults the shape — which is the point: it is the one
     * check that a shape which overshot cannot satisfy by construction.
     */
    private fun borderConnectedInk(
        luminance: FloatArray,
        width: Int,
        height: Int,
        bgLum: Float,
        isInverted: Boolean
    ): BooleanArray {
        val mask = BooleanArray(luminance.size)
        val queue = ArrayDeque<Int>()

        fun isInk(i: Int): Boolean = if (isInverted) {
            luminance[i] > bgLum + INK_TOLERANCE
        } else {
            luminance[i] < bgLum - INK_TOLERANCE
        }

        fun seed(i: Int) {
            if (!mask[i] && isInk(i)) {
                mask[i] = true
                queue.addLast(i)
            }
        }

        for (x in 0 until width) {
            seed(x)
            seed((height - 1) * width + x)
        }
        for (y in 0 until height) {
            seed(y * width)
            seed(y * width + width - 1)
        }

        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % width
            val y = i / width
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val n = ny * width + nx
                    if (!mask[n] && isInk(n)) {
                        mask[n] = true
                        queue.addLast(n)
                    }
                }
            }
        }
        return mask
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
