package com.example.pipeline.wipe

/**
 * Erases text inside a bubble by connectivity alone (wipe 1b, Commander 2026-11-17).
 *
 * The bubble outline touches the border of the detector box drawn around the
 * bubble; lettering floats inside it. So a dark connected component that does
 * not touch the box border is text and is erased whole, and a component that
 * touches the border is the outline (or ink fused to it) and survives. There
 * is no glyph-vs-structure classifier left: no extent, fill, hairline or
 * neck-split rule judges a component's shape.
 *
 * Components are labelled on the *core* ink when the caller supplies it, not
 * on the full ink mask. The full mask counts any pixel a hair darker than the
 * paper as ink, so the anti-aliased fringe around one glyph touches the next
 * and can bridge lettering to the outline. Glyph cores carry no fringe, so
 * they stay separate components. Each core pixel then claims the loose ink
 * nearest it, so the wipe still covers the fringe around a glyph — and a
 * glyph that bridges by fringe alone into the outline claims only its own
 * side of the bridge, because the outline's core is claiming from the other
 * end. A component whose claimed fringe reaches the box border is border-
 * touching after all and survives.
 */
object GlyphErase {

    private const val UNCLASSIFIED = 0
    private const val GLYPH = 1
    private const val STRUCTURE = 2

    /**
     * @param wipe pixels to repaint: the floating components plus a clipped halo.
     * @param glyph ink in floating (text) components.
     * @param structure ink in border-touching components, left untouched.
     * @param glyphPixels ink pixels in floating components.
     * @param structurePixels ink pixels in border-touching components.
     */
    data class Result(
        val wipe: BooleanArray,
        val glyph: BooleanArray,
        val structure: BooleanArray,
        val glyphPixels: Int,
        val structurePixels: Int
    )

    /** One labelled component: its pixels, and whether it touches the box border. */
    private class Component(val pixels: IntArray, val touchesEdge: Boolean)

    /**
     * Split [ink] into floating and border-touching components.
     *
     * @param ink content pixels inside the region being planned, row-major (loose mask).
     * @param allowed pixels the wiper may touch at all — the detector's box.
     * @param box the detector's box inside the region; its border defines border-touching.
     * @param dilationRadius halo grown around each floating component, clipped to
     *   [allowed] minus border-touching ink, so a halo can never reach the outline.
     * @param coreInk the strong-ink subset of [ink], used to label components when
     *   supplied. Must be a subset of [ink]; ignored when it is not smaller than [ink].
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

        fun touchesBoxEdge(x: Int, y: Int): Boolean {
            if (!clipToBoxEdge) {
                return x <= 0 || y <= 0 || x >= width - 1 || y >= height - 1
            }
            val bx = x - box.left
            val by = y - box.top
            return bx <= 0 || by <= 0 || bx >= box.width - 1 || by >= box.height - 1
        }

        // Label the 8-connected components of [mask].
        fun componentsOf(mask: BooleanArray): List<Component> {
            val visited = BooleanArray(count)
            val stack = IntArray(count)
            val components = mutableListOf<Component>()
            for (start in 0 until count) {
                if (!mask[start] || visited[start]) continue
                val pixels = mutableListOf<Int>()
                var touchesEdge = false
                var top = 0
                visited[start] = true
                stack[top++] = start
                while (top > 0) {
                    val i = stack[--top]
                    pixels += i
                    val x = i % width
                    val y = i / width
                    if (touchesBoxEdge(x, y)) touchesEdge = true
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
                components += Component(pixels.toIntArray(), touchesEdge)
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
            // fringe bridge between lettering and the outline is split where the two
            // floods meet.
            val owner = IntArray(count) { -1 }
            val queue = IntArray(count)
            var head = 0
            var tail = 0
            // Floating components seed first: a fringe bridge between lettering
            // and the outline is claimed by the lettering's side, so the wipe
            // covers the glyph's own anti-aliasing instead of leaving it as
            // outline-owned fringe.
            val seedOrder = components.indices.sortedBy { components[it].touchesEdge }
            for (index in seedOrder) {
                for (i in components[index].pixels) {
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
            // A floating verdict does not survive its own claimed fringe reaching
            // the box border: in the loose mask that is ink fused to the outline.
            val claimedTouchesEdge = BooleanArray(components.size)
            for (i in 0 until count) {
                val o = owner[i]
                if (o >= 0 && touchesBoxEdge(i % width, i / width)) claimedTouchesEdge[o] = true
            }
            for (i in 0 until count) {
                val o = owner[i]
                if (o < 0) continue
                val component = components[o]
                pixelClass[i] =
                    if (component.touchesEdge || claimedTouchesEdge[o]) STRUCTURE else GLYPH
            }
            // Loose ink with no core at all (a pure fringe speck) is labelled on
            // its own loose component, exactly as all ink is without cores.
            val leftover = BooleanArray(count) { ink[it] && owner[it] < 0 }
            if (leftover.any { it }) {
                for (component in componentsOf(leftover)) {
                    val cls = if (component.touchesEdge) STRUCTURE else GLYPH
                    for (i in component.pixels) pixelClass[i] = cls
                }
            }
        } else {
            for (component in componentsOf(ink)) {
                val cls = if (component.touchesEdge) STRUCTURE else GLYPH
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
