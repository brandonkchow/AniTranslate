package com.example.pipeline.wipe

import kotlin.math.max

/**
 * The interior of a bubble, measured from the image rather than assumed.
 *
 * [BubbleInterior.interiorMask] fits a rigid ellipse inside the bounding box. That is only correct
 * for a bubble that genuinely is an ellipse. Real pages are full of scalloped, spiked and starburst
 * bubbles, and [BubbleInterior.measureWallInset] casts only four rays along the centre lines: on a
 * spiked wall one of those rays slips down a valley, reports the stroke far outside where it really
 * is, and the fitted ellipse then cuts straight through the spurs.
 *
 * This object assumes no shape at all. It floods the paper the bubble encloses, starting from the
 * region's centre, then fills in the glyphs that paper surrounds. Two properties fall out of that
 * construction, and both are the reason to prefer it:
 *
 *  - **The wall cannot be wiped.** The stroke is contiguous with the page around it, so it is
 *    reachable from the region border and is therefore never part of the filled interior. Over-wipe
 *    stops being something a threshold has to prevent and becomes something the topology cannot do.
 *  - **Any wall shape works.** Spiky, scalloped, tailed, lopsided — the interior is whatever the
 *    stroke actually encloses, so there is no inset to measure, clamp, or get wrong.
 *
 * When the stroke does not close inside the region — a box that clips the bubble, a wall broken by
 * a light source, a dark (inverted) bubble — [Interior.found] is false and the caller is expected
 * to fall back. Reporting that honestly is the point: the previous measurement let a clamped
 * give-up be indistinguishable from a success, which is how a 9.9%-of-box interior came to look
 * like a legitimate answer.
 *
 * When the paper simply *leaks* — the stroke is opened by a tail, a highlight, or JPEG noise, so the
 * interior is connected to the page around it — then no amount of widening the region helps. Every
 * margin from 13 to 80 px leaked on the box that exposed this: the flood's size barely grew while
 * the region doubled, which is the signature of a thin leak rather than a box that is too tight. It
 * is a *wall* problem, not a sizing one, and the remedy is to seal the gap before flooding: erode
 * the paper by a pixel or two (dilating the stroke, closing a break up to twice that wide), flood
 * inside the sealed wall, then grow the interior back so the wipe still reaches the original stroke.
 * That ladder lives here, not in the caller, so the shipping path and the desktop harness cannot
 * diverge on it.
 */
object EnclosedInterior {

    /**
     * Luminance at or above which a pixel counts as paper for the flood.
     *
     * Matches the stroke threshold [BubbleInterior.measureWallInset] uses, so the two agree on what
     * the wall is. Deliberately a fixed luminance rather than a distance from the sampled interior
     * colour: JPEG ringing around the text would fragment the paper region and shrink the interior
     * to nothing on an otherwise healthy bubble.
     */
    const val PAPER_LUMINANCE = 0.55f

    /**
     * An interior covering less than this fraction of the region is not credible. Guards the case
     * where a wall closes around a few pixels of noise, which would silently wipe nothing.
     */
    private const val MIN_COVERAGE = 0.05f

    private const val MARGIN_FRACTION = 0.08f
    private const val MIN_MARGIN = 6
    private const val MAX_MARGIN = 32

    /**
     * Gap radii the flood will try to seal through, in pixels, in order.
     *
     * `0` is the unsealed flood and is tried first, so a bubble whose stroke already closes is
     * measured exactly as it was before this ladder existed — sealing can only ever add interiors
     * that previously failed, never reshape one that already worked. Sealing is not free: it eats a
     * one-pixel band along the inside of the stroke, so it is used only after the honest attempt has
     * failed. Two pixels of radius closes breaks up to four wide, which covers the tail and
     * highlight gaps seen in practice; deeper radii start closing openings that are really there.
     */
    private val SEAL_RADII = intArrayOf(0, 1, 2)

    /**
     * Margin to widen a detection box by before measuring, in pixels.
     *
     * A detection box is tight by construction, so the stroke is frequently clipped by the box edge
     * and can no longer be recognised as a wall that closes — the paper inside simply runs off the
     * side. Widening the look by this much is what turns a clipped box into a measurable bubble;
     * without it, most real boxes on a busy page report [Interior.found] false and give up the
     * measured interior entirely.
     *
     * Measurement only. The caller must keep the detector's own box for anything it samples or
     * draws, because this one reaches out onto the artwork.
     */
    fun measurementMargin(boxWidth: Int, boxHeight: Int): Int =
        (MARGIN_FRACTION * max(boxWidth, boxHeight)).toInt().coerceIn(MIN_MARGIN, MAX_MARGIN)

    /**
     * @param mask pixels the wiper may touch, in row-major order. Empty when [found] is false.
     * @param found false when no closed wall encloses the region centre.
     */
    data class Interior(val mask: BooleanArray, val found: Boolean) {
        companion object {
            fun notFound(count: Int) = Interior(BooleanArray(count), false)
        }
    }

    /**
     * Measure the enclosed interior of one region.
     *
     * Tries an unsealed flood first, then progressively seals a broken stroke (see [SEAL_RADII]).
     *
     * @param luminance per-pixel luminance, row-major, `width * height` long.
     */
    fun measure(luminance: FloatArray, width: Int, height: Int): Interior {
        val count = width * height
        require(width > 0 && height > 0) { "region must not be empty" }
        require(luminance.size >= count) { "need $count luminance values, got ${luminance.size}" }

        val paper = BooleanArray(count) { luminance[it] >= PAPER_LUMINANCE }

        for (seal in SEAL_RADII) {
            attempt(paper, width, height, seal)?.let { return it }
        }

        return Interior.notFound(count)
    }

    /**
     * One flood attempt, with the paper eroded by [seal] pixels first.
     *
     * @return null when this attempt found no closed wall, so the caller can try a wider seal.
     */
    private fun attempt(
        paper: BooleanArray,
        width: Int,
        height: Int,
        seal: Int
    ): Interior? {
        val count = width * height
        val passable = if (seal == 0) paper else erode(paper, width, height, seal)

        val seed = nearestPaperToCentre(passable, width, height) ?: return null

        var region = flood(listOf(seed), passable, width, height)

        // Paper that reaches the border is not enclosed: either the stroke is broken or the region
        // clips the bubble. Either way there is no wall to trust, so fall back rather than guess.
        if (touchesBorder(region, width, height)) return null

        if (seal > 0) {
            // Undo the seal so the wipe reaches the stroke. Bounded by the original paper, so
            // growing the interior back can never reach into the stroke itself.
            region = grow(region, paper, width, height, seal)
            if (touchesBorder(region, width, height)) return null
        }

        // Fill the glyphs: the complement pixels that the border cannot reach are exactly the ones
        // this paper surrounds. The stroke is excluded because it borders the page directly.
        val complement = BooleanArray(count) { !region[it] }
        val complementOutside = flood(borderIndices(width, height), complement, width, height)

        val interior = BooleanArray(count) { region[it] || !complementOutside[it] }

        var covered = 0
        for (i in 0 until count) if (interior[i]) covered++
        if (covered < count * MIN_COVERAGE) return null

        return Interior(interior, true)
    }

    /**
     * Shrink paper by [radius] pixels, which dilates the stroke by the same amount and so closes a
     * break in it up to `2 * radius` wide.
     *
     * Pixels on the region border are never eroded (out-of-bounds counts as paper). Eroding them
     * would make the border unreachable and hide a genuine leak instead of reporting it, which is
     * the one failure this guard exists to surface.
     */
    private fun erode(
        paper: BooleanArray,
        width: Int,
        height: Int,
        radius: Int
    ): BooleanArray {
        var current = paper
        var next = BooleanArray(paper.size)
        repeat(radius) {
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    val i = row + x
                    next[i] = current[i] &&
                        (x == 0 || current[i - 1]) &&
                        (x == width - 1 || current[i + 1]) &&
                        (y == 0 || current[i - width]) &&
                        (y == height - 1 || current[i + width])
                }
            }
            val swap = current
            current = next
            next = swap
        }
        return current
    }

    /**
     * Grow [region] outward by [radius] pixels, but only into pixels that are paper in the
     * original image, so the seal is undone without the interior ever reaching the stroke.
     */
    private fun grow(
        region: BooleanArray,
        paper: BooleanArray,
        width: Int,
        height: Int,
        radius: Int
    ): BooleanArray {
        var current = region.copyOf()
        var next = current.copyOf()
        repeat(radius) {
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    val i = row + x
                    next[i] = when {
                        current[i] -> true
                        !paper[i] -> false
                        else ->
                            (x > 0 && current[i - 1]) ||
                                (x < width - 1 && current[i + 1]) ||
                                (y > 0 && current[i - width]) ||
                                (y < height - 1 && current[i + width])
                    }
                }
            }
            val swap = current
            current = next
            next = swap
        }
        return current
    }

    /** The paper pixel closest to the region centre, or null when the region has no paper at all. */
    private fun nearestPaperToCentre(paper: BooleanArray, width: Int, height: Int): Int? {
        val centreX = (width - 1) / 2
        val centreY = (height - 1) / 2
        var best = -1
        var bestDistance = Long.MAX_VALUE
        for (y in 0 until height) {
            val row = y * width
            val dy = (y - centreY).toLong()
            for (x in 0 until width) {
                val i = row + x
                if (!paper[i]) continue
                val dx = (x - centreX).toLong()
                val distance = dx * dx + dy * dy
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = i
                }
            }
        }
        return if (best >= 0) best else null
    }

    /** Four-connected flood over [passable], seeded from [seeds]. */
    private fun flood(
        seeds: List<Int>,
        passable: BooleanArray,
        width: Int,
        height: Int
    ): BooleanArray {
        val seen = BooleanArray(passable.size)
        val stack = ArrayDeque<Int>()
        for (seed in seeds) {
            if (seed in passable.indices && passable[seed] && !seen[seed]) {
                seen[seed] = true
                stack.addLast(seed)
            }
        }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val x = i % width
            val y = i / width

            if (x > 0) visit(i - 1, passable, seen, stack)
            if (x < width - 1) visit(i + 1, passable, seen, stack)
            if (y > 0) visit(i - width, passable, seen, stack)
            if (y < height - 1) visit(i + width, passable, seen, stack)
        }
        return seen
    }

    private fun visit(i: Int, passable: BooleanArray, seen: BooleanArray, stack: ArrayDeque<Int>) {
        if (passable[i] && !seen[i]) {
            seen[i] = true
            stack.addLast(i)
        }
    }

    private fun touchesBorder(mask: BooleanArray, width: Int, height: Int): Boolean {
        for (x in 0 until width) {
            if (mask[x] || mask[(height - 1) * width + x]) return true
        }
        for (y in 0 until height) {
            if (mask[y * width] || mask[y * width + width - 1]) return true
        }
        return false
    }

    private fun borderIndices(width: Int, height: Int): List<Int> {
        val out = ArrayList<Int>(2 * width + 2 * height)
        for (x in 0 until width) {
            out.add(x)
            out.add((height - 1) * width + x)
        }
        for (y in 0 until height) {
            out.add(y * width)
            out.add(y * width + width - 1)
        }
        return out
    }
}
