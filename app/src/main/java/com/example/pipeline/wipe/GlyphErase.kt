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

    /**
     * Split [ink] into glyphs and structure, and return the pixels to repaint.
     *
     * @param ink content pixels inside the region being planned, row-major.
     * @param allowed pixels the wiper may touch at all — the detector's box.
     * @param box the detector's box inside the region; its edges define "structure attached to the
     *   edge", which is what keeps a scalloped or spiked wall out of the erase set.
     * @param dilationRadius halo, in pixels, grown around each glyph so anti-aliasing goes with it.
     *   The growth is clipped to [allowed] minus structure, so a halo can never reach the wall.
     */
    fun wipe(
        ink: BooleanArray,
        allowed: BooleanArray,
        width: Int,
        height: Int,
        box: BubbleInkMask.BoxFrame,
        dilationRadius: Int
    ): Result {
        val count = width * height
        require(count > 0) { "region must not be empty" }
        require(width > 0 && height > 0) { "region must have extent" }
        require(ink.size >= count) { "need $count ink pixels, got ${ink.size}" }
        require(allowed.size >= count) { "need $count allowed pixels, got ${allowed.size}" }
        require(box.width > 0 && box.height > 0) { "box must have extent" }

        val visited = BooleanArray(count)
        val component = IntArray(count)
        val stack = IntArray(count)
        val seed = BooleanArray(count)
        val structure = BooleanArray(count)
        var glyphPixels = 0
        var structurePixels = 0

        val shortSide = min(box.width, box.height).toFloat()
        val structureExtent = STRUCTURE_EXTENT * shortSide
        val hairlineExtent = HAIRLINE_EXTENT * shortSide

        for (start in 0 until count) {
            if (!ink[start] || visited[start]) continue

            var size = 0
            var top = 0
            var minX = width
            var maxX = -1
            var minY = height
            var maxY = -1
            var touchesEdge = false

            visited[start] = true
            component[size++] = start
            stack[top++] = start

            while (top > 0) {
                val i = stack[--top]
                val x = i % width
                val y = i / width
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                val bx = x - box.left
                val by = y - box.top
                if (bx <= 0 || by <= 0 || bx >= box.width - 1 || by >= box.height - 1) {
                    touchesEdge = true
                }

                // 8-connected: a glyph's diagonal strokes are one component, not several.
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val ni = ny * width + nx
                        if (ink[ni] && !visited[ni]) {
                            visited[ni] = true
                            component[size++] = ni
                            stack[top++] = ni
                        }
                    }
                }
            }

            val spanX = maxX - minX + 1
            val spanY = maxY - minY + 1
            val extent = max(spanX, spanY).toFloat()
            val fill = size.toFloat() / (spanX * spanY).toFloat()

            val isStructure = touchesEdge ||
                extent >= structureExtent ||
                (fill < MIN_GLYPH_FILL && extent >= hairlineExtent)

            if (isStructure) {
                for (k in 0 until size) structure[component[k]] = true
                structurePixels += size
            } else {
                for (k in 0 until size) seed[component[k]] = true
                glyphPixels += size
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
