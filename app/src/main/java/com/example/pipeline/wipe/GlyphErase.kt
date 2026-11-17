package com.example.pipeline.wipe

import kotlin.math.max
import kotlin.math.min

/**
 * Erases text by classifying the ink rather than by fitting a shape to it.
 *
 * The geometric wipe needs a region it already knows is text: it measures a wall, insets from it,
 * and repaints what is left. When no wall closes inside the region there is nothing to measure, and
 * any inset computed without one is a guess. Both failure modes of that guess have been observed on
 * real pages — too generous and the shape is drawn straight through a scalloped or spiked wall, too
 * timid and text survives in the middle of the bubble.
 *
 * This object removes the guess. It never asks where the wall is; it asks which ink *is text*. Text
 * is drawn as small, compact glyphs. Walls, tails, speed lines and panel borders are either long, or
 * attached to the edge of the box they were detected in. Labelling the ink into connected components
 * and separating those two populations needs no shape, no inset and no threshold on a dimension the
 * detector measured, so there is no clamped value left to paint with — and ink that is entirely
 * structure is simply left alone rather than covered.
 *
 * The classification is deliberately conservative in one direction: anything touching the box edge
 * counts as structure even when it is small. A glyph clipped by a tight detection box is therefore
 * kept rather than erased. Leaving a stroke of text is recoverable; slicing a bubble outline is not.
 *
 * Components are labelled on the *core* ink when the caller supplies it, not on the full ink mask.
 * The full mask counts any pixel a hair darker than the paper as ink, so the anti-aliased fringe
 * around one glyph touches the next: a vertical column of Japanese glyphs — stacked tight, with
 * barely a paper row between them — fuses into a single tall component, and the extent rule below
 * then protects the whole column as if it were a wall (page 6 units 3, 13, 14, where 67-99% of the
 * glyph ink survived). Glyph cores carry no fringe, so the column separates back into glyphs. Each
 * core pixel then claims the loose ink nearest it, so the wipe still covers the fringe around a
 * glyph — and a glyph that bridges by fringe alone into artwork claims only its own side of the
 * bridge, because the artwork's core is claiming from the other end. A core component whose claimed
 * fringe reaches the box edge is structure after all: that is exactly the clipped glyph the rule
 * above protects, seen in the loose mask rather than the core.
 */
object GlyphErase {

    /**
     * Longest bbox side, as a fraction of the box's short side, above which a component is structure.
     *
     * A drawn wall crosses the whole box; a glyph never does. 0.45 leaves generous headroom for the
     * largest kanji in a tight box while still catching any stroke that spans the bubble.
     */
    private const val STRUCTURE_EXTENT = 0.45f

    /** Area / bbox area below which a component is a hairline rather than a solid glyph. */
    private const val MIN_GLYPH_FILL = 0.12f

    /** Bbox extent, as a fraction of the short side, above which low fill means stroke not glyph. */
    private const val HAIRLINE_EXTENT = 0.25f

    private const val UNCLASSIFIED = 0
    private const val GLYPH = 1
    private const val STRUCTURE = 2

    /**
     * @param wipe pixels to repaint: the glyph components plus a clipped halo for anti-aliasing.
     * @param glyph ink classified as text.
     * @param structure ink classified as wall, tail or border, and left untouched.
     * @param glyphPixels ink pixels classified as text.
     * @param structurePixels ink pixels classified as wall, tail or border.
     */
    data class Result(
        val wipe: BooleanArray,
        val glyph: BooleanArray,
        val structure: BooleanArray,
        val glyphPixels: Int,
        val structurePixels: Int
    )

    /** One labelled component: its pixels, and the verdict the extent rules reach on it. */
    private class Component(val pixels: IntArray, val isStructure: Boolean, val touchesEdge: Boolean)

    /**
     * Split [ink] into glyphs and structure, and return the pixels to repaint.
     *
     * @param ink content pixels inside the region being planned, row-major. This is the loose mask:
     *   every pixel that counts as ink at all, fringe included.
     * @param allowed pixels the wiper may touch at all — the detector's box.
     * @param box the detector's box inside the region; its edges define "structure attached to the
     *   edge", which is what keeps a scalloped or spiked wall out of the erase set.
     * @param dilationRadius halo, in pixels, grown around each glyph so anti-aliasing goes with it.
     *   The growth is clipped to [allowed] minus structure, so a halo can never reach the wall.
     * @param coreInk the strong-ink subset of [ink] (pixels far from the interior colour), used to
     *   label components when supplied. Must be a subset of [ink]; ignored when it is not smaller
     *   than [ink], in which case labelling runs on [ink] itself exactly as before.
     */
    fun wipe(
        ink: BooleanArray,
        allowed: BooleanArray,
        width: Int,
        height: Int,
        box: BubbleInkMask.BoxFrame,
        dilationRadius: Int,
        coreInk: BooleanArray? = null,
        clipToBoxEdge: Boolean = true
    ): Result {
        val count = width * height
        require(count > 0) { "region must not be empty" }
        require(width > 0 && height > 0) { "region must have extent" }
        require(ink.size >= count) { "need $count ink pixels, got ${ink.size}" }
        require(allowed.size >= count) { "need $count allowed pixels, got ${allowed.size}" }
        require(coreInk == null || coreInk.size >= count) { "need $count core pixels, got ${coreInk?.size}" }
        require(box.width > 0 && box.height > 0) { "box must have extent" }

        val shortSide = min(box.width, box.height).toFloat()
        val structureExtent = STRUCTURE_EXTENT * shortSide
        val hairlineExtent = HAIRLINE_EXTENT * shortSide

        fun touchesBoxEdge(x: Int, y: Int): Boolean {
            if (!clipToBoxEdge) {
                return x <= 0 || y <= 0 || x >= width - 1 || y >= height - 1
            }
            val bx = x - box.left
            val by = y - box.top
            return bx <= 0 || by <= 0 || bx >= box.width - 1 || by >= box.height - 1
        }

        /**
         * Share of the component's bounding box that is paper the component itself
         * encloses — non-ink inside the box that cannot reach the box's own border
         * without crossing ink. A wall ring scores most of its box; a glyph or a
         * glyph group scores only its counters, a few pixels each.
         */
        fun enclosedPaperFraction(pixels: List<Int>, minX: Int, maxX: Int, minY: Int, maxY: Int): Float {
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val grid = BooleanArray(bw * bh)
            for (i in pixels) grid[(i / width - minY) * bw + (i % width - minX)] = true
            val reached = BooleanArray(bw * bh)
            val queue = IntArray(bw * bh)
            var head = 0
            var tail = 0
            fun seed(x: Int, y: Int) {
                val i = y * bw + x
                if (!grid[i] && !reached[i]) {
                    reached[i] = true
                    queue[tail++] = i
                }
            }
            for (x in 0 until bw) { seed(x, 0); seed(x, bh - 1) }
            for (y in 0 until bh) { seed(0, y); seed(bw - 1, y) }
            while (head < tail) {
                val i = queue[head++]
                val x = i % bw
                val y = i / bw
                if (x > 0) seed(x - 1, y)
                if (x < bw - 1) seed(x + 1, y)
                if (y > 0) seed(x, y - 1)
                if (y < bh - 1) seed(x, y + 1)
            }
            var enclosed = 0
            for (i in grid.indices) if (!grid[i] && !reached[i]) enclosed++
            return enclosed.toFloat() / grid.size.toFloat()
        }

        class Stats(
            val minX: Int, val maxX: Int, val minY: Int, val maxY: Int,
            val touchesEdge: Boolean, val fill: Float, val extent: Float, val thin: Boolean
        )

        fun statsOf(pixels: List<Int>): Stats {
            var minX = width
            var maxX = -1
            var minY = height
            var maxY = -1
            var touchesEdge = false
            for (i in pixels) {
                val x = i % width
                val y = i / width
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (touchesBoxEdge(x, y)) touchesEdge = true
            }
            val spanX = maxX - minX + 1
            val spanY = maxY - minY + 1
            return Stats(
                minX, maxX, minY, maxY, touchesEdge,
                fill = pixels.size.toFloat() / (spanX * spanY).toFloat(),
                extent = max(spanX, spanY).toFloat(),
                thin = min(spanX, spanY).toFloat() < 0.25f * shortSide
            )
        }

        /**
         * Neck-split rescue for one extent-large component. Two glyphs whose cores
         * touch — page-6 unit 3's bottom group, 20x40 in a 56px box — are one
         * component only at a neck a pixel or two wide (its row counts measured
         * 19 across the glyphs, 1-2 at the join in one decoder, 3-5 in another).
         * Cutting at neck lines along the long axis must leave solid,
         * glyph-sized, glyph-ruled pieces carrying at least half the ink; the
         * rest of the component is the neck itself and the fragments it joins,
         * which ride with the parent verdict. A wall, an arc or a solid art
         * block has no such cut — its cross-section never narrows to a neck,
         * or what survives the cut is a stroke or a slab, not a glyph — so the
         * rescue fails and the extent verdict stands.
         */
        fun neckSplitRescue(pixels: List<Int>, stats: Stats): Boolean {
            val vertical = (stats.maxY - stats.minY) >= (stats.maxX - stats.minX)
            val lines = if (vertical) stats.maxY - stats.minY + 1 else stats.maxX - stats.minX + 1
            val counts = IntArray(lines)
            for (i in pixels) {
                counts[if (vertical) i / width - stats.minY else i % width - stats.minX]++
            }
            val neckMax = maxOf(2, (counts.max() * 0.30f).toInt())
            if (counts.none { it in 1..neckMax }) return false
            val kept = pixels.filter { i ->
                counts[if (vertical) i / width - stats.minY else i % width - stats.minX] > neckMax
            }
            if (kept.size < pixels.size / 2) return false
            // Label what survives the cut, 8-connected as everywhere else here.
            val keptSet = kept.toHashSet()
            val visited = HashSet<Int>()
            val stack = mutableListOf<Int>()
            var largePieces = 0
            for (start in kept) {
                if (start in visited) continue
                val piece = mutableListOf<Int>()
                stack.clear()
                stack += start
                visited += start
                while (stack.isNotEmpty()) {
                    val i = stack.removeAt(stack.lastIndex)
                    piece += i
                    val x = i % width
                    val y = i / width
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                        val ni = ny * width + nx
                        if (ni in keptSet && ni !in visited) {
                            visited += ni
                            stack += ni
                        }
                    }
                }
                if (piece.size < 20) continue // a dot or a neck fragment rides with the parent
                largePieces++
                val ps = statsOf(piece)
                val pieceSpanMin = min(ps.maxX - ps.minX, ps.maxY - ps.minY) + 1
                if (ps.touchesEdge || ps.extent >= structureExtent ||
                    ps.fill < MIN_GLYPH_FILL || pieceSpanMin.toFloat() < 0.15f * shortSide ||
                    enclosedPaperFraction(piece, ps.minX, ps.maxX, ps.minY, ps.maxY) > 0.25f
                ) return false
            }
            return largePieces >= 1
        }

        fun classify(pixels: List<Int>): Component {
            val stats = statsOf(pixels)
            var isStructure = stats.touchesEdge ||
                (stats.fill < MIN_GLYPH_FILL && stats.extent >= hairlineExtent) ||
                stats.extent >= structureExtent
            if (isStructure && !stats.touchesEdge && stats.extent >= structureExtent &&
                !(stats.fill < MIN_GLYPH_FILL && stats.extent >= hairlineExtent) &&
                neckSplitRescue(pixels, stats)
            ) {
                isStructure = false
            }
            return Component(pixels.toIntArray(), isStructure, stats.touchesEdge)
        }

        // Label the 8-connected components of [mask], classifying each as it closes.
        fun componentsOf(mask: BooleanArray): List<Component> {
            val visited = BooleanArray(count)
            val stack = IntArray(count)
            val components = mutableListOf<Component>()
            for (start in 0 until count) {
                if (!mask[start] || visited[start]) continue
                val pixels = mutableListOf<Int>()
                var top = 0
                visited[start] = true
                stack[top++] = start
                while (top > 0) {
                    val i = stack[--top]
                    pixels += i
                    val x = i % width
                    val y = i / width
                    // 8-connected: a glyph's diagonal strokes are one component, not several.
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny < 0 || ny >= height) continue
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx
                            if (nx < 0 || nx >= width) continue
                            val ni = ny * width + nx
                            if (mask[ni] && !visited[ni]) {
                                visited[ni] = true
                                stack[top++] = ni
                            }
                        }
                    }
                }
                components += classify(pixels)
            }
            return components
        }

        val pixelClass = IntArray(count)
        var useCore = false
        if (coreInk != null) {
            var subset = true
            var differs = false
            for (i in 0 until count) {
                if (coreInk[i] && !ink[i]) subset = false
                if (coreInk[i] != ink[i]) differs = true
            }
            useCore = subset && differs
        }

        if (useCore) {
            val core = coreInk!!
            val components = componentsOf(core)
            // Every loose pixel is claimed by the core component nearest it, flooding
            // outward through loose ink only. Queue order is component scan order, so a
            // fringe bridge between a glyph and artwork is split where the two floods meet.
            val owner = IntArray(count) { -1 }
            val queue = IntArray(count)
            var head = 0
            var tail = 0
            for ((index, component) in components.withIndex()) {
                for (i in component.pixels) {
                    owner[i] = index
                    queue[tail++] = i
                }
            }
            while (head < tail) {
                val i = queue[head++]
                val x = i % width
                val y = i / width
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val ni = ny * width + nx
                        if (ink[ni] && owner[ni] < 0) {
                            owner[ni] = owner[i]
                            queue[tail++] = ni
                        }
                    }
                }
            }
            // A glyph verdict does not survive its own fringe reaching the box edge:
            // in the loose mask that is a clipped glyph or a wall, and both stay.
            // Nor does it survive claiming a loose territory that is itself a
            // stroke: a small dark core sitting on a long light-grey hairline
            // claims the whole line by flood, and the line's own shape — thin,
            // or hairline-fill at extent, or extent-large with no neck-split
            // into glyphs — is what the loose mask was classified on before
            // cores existed. A claimed glyph column is none of those, so this
            // recheck leaves it standing.
            val claimedTouchesEdge = BooleanArray(components.size)
            for (i in 0 until count) {
                val o = owner[i]
                if (o >= 0 && touchesBoxEdge(i % width, i / width)) claimedTouchesEdge[o] = true
            }
            val claimedIsStroke = BooleanArray(components.size)
            val claimed = Array(components.size) { mutableListOf<Int>() }
            for (i in 0 until count) {
                val o = owner[i]
                if (o >= 0) claimed[o] += i
            }
            for ((index, component) in components.withIndex()) {
                if (component.isStructure || claimed[index].isEmpty()) continue
                val cs = statsOf(claimed[index])
                claimedIsStroke[index] = (cs.extent >= structureExtent && cs.thin) ||
                    (cs.fill < MIN_GLYPH_FILL && cs.extent >= hairlineExtent) ||
                    (cs.extent >= structureExtent && !neckSplitRescue(claimed[index], cs))
            }
            for (i in 0 until count) {
                val o = owner[i]
                if (o < 0) continue
                val component = components[o]
                pixelClass[i] = if (component.isStructure || claimedTouchesEdge[o] || claimedIsStroke[o]) STRUCTURE else GLYPH
            }
            // Loose ink with no core at all (a pure fringe speck) is classified on its
            // own loose component, exactly as all ink was before cores existed.
            val leftover = BooleanArray(count) { ink[it] && owner[it] < 0 }
            if (leftover.any { it }) {
                for (component in componentsOf(leftover)) {
                    val cls = if (component.isStructure) STRUCTURE else GLYPH
                    for (i in component.pixels) pixelClass[i] = cls
                }
            }
        } else {
            for (component in componentsOf(ink)) {
                val cls = if (component.isStructure) STRUCTURE else GLYPH
                for (i in component.pixels) pixelClass[i] = cls
            }
        }

        val seed = BooleanArray(count)
        val structure = BooleanArray(count)
        var glyphPixels = 0
        var structurePixels = 0
        for (i in 0 until count) {
            when (pixelClass[i]) {
                GLYPH -> { seed[i] = true; glyphPixels++ }
                STRUCTURE -> { structure[i] = true; structurePixels++ }
            }
        }

        if (glyphPixels == 0) {
            return Result(BooleanArray(count), seed, structure, 0, structurePixels)
        }

        val bounds = BooleanArray(count) { allowed[it] && !structure[it] }
        val wipe = BubbleInkMask.dilate(seed, bounds, width, height, dilationRadius)
        return Result(wipe, seed, structure, glyphPixels, structurePixels)
    }
}
