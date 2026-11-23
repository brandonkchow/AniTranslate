package com.example.pipeline.wipe

import com.example.data.models.Bubble
import com.example.data.models.TextClass

/**
 * Per-unit gates for the mask wipe (prompt 20261118, Parts 1-2).
 *
 * Pure pixel math, no Android types, so unit tests and render harnesses
 * exercise the shipped code.
 *
 * Part 1 - SKIP pixel gate: a unit classified [TextClass.SKIP] must reach
 * the final render bit-identical to the original. Classification is
 * computed once (TextClassifier at detect) and carried on the Bubble; this
 * gate only consumes it, it never re-derives it.
 *
 * Part 2 - coverage flag: a dark, glyph-shaped component still present
 * inside a processed unit after masking is a known miss. Report-only:
 * flagged units block presentation as PASS and wait for a human verdict;
 * nothing here self-accepts a unit on its own numbers.
 */
object WipeGate {

    /** Dark at or below this luminance counts as ink. */
    private const val DARK_LUMINANCE = 0.40f

    /** A component smaller than this is speckle, not a glyph. Matches the
     *  MIN_TEXT_PIXELS floor already used by [BubbleInkMask]. */
    private const val MIN_GLYPH_PIXELS = 10

    data class SkipViolation(val unitId: Int, val changedPixels: Int)

    data class GlyphComponent(val pixels: Int, val width: Int, val height: Int)

    data class CoverageFlag(val unitId: Int, val components: List<GlyphComponent>)

    private fun boxPx(b: Bubble, w: Int, h: Int): IntArray {
        val x1 = (b.x1 * w).toInt().coerceIn(0, w)
        val x2 = (b.x2 * w).toInt().coerceIn(0, w)
        val y1 = (b.y1 * h).toInt().coerceIn(0, h)
        val y2 = (b.y2 * h).toInt().coerceIn(0, h)
        return intArrayOf(x1, y1, x2, y2)
    }

    /** SKIP units whose pixels differ between [original] and [rendered]. */
    fun skipViolations(
        original: IntArray, rendered: IntArray, w: Int, h: Int, bubbles: List<Bubble>
    ): List<SkipViolation> {
        val out = mutableListOf<SkipViolation>()
        for (b in bubbles) {
            if (b.textClass != TextClass.SKIP) continue
            val (x1, y1, x2, y2) = boxPx(b, w, h)
            var changed = 0
            for (y in y1 until y2) for (x in x1 until x2) {
                if (original[y * w + x] != rendered[y * w + x]) changed++
            }
            if (changed > 0) out += SkipViolation(b.id, changed)
        }
        return out
    }

    /** One gate line per SKIP unit, printed by render reports. */
    fun skipGateLines(
        original: IntArray, rendered: IntArray, w: Int, h: Int, bubbles: List<Bubble>
    ): List<String> {
        val violations = skipViolations(original, rendered, w, h, bubbles).associateBy { it.unitId }
        return bubbles.filter { it.textClass == TextClass.SKIP }.map { b ->
            val v = violations[b.id]
            if (v == null) "SKIP-GATE unit ${b.id}: PASS bit-identical"
            else "SKIP-GATE unit ${b.id}: FAIL changedPixels=${v.changedPixels}"
        }
    }

    /**
     * Restore SKIP boxes in [rendered] from [original], in place. The wipe
     * already excludes SKIP boxes from its erase mask; this covers the case
     * where a neighbouring processed box overlaps a SKIP unit. SKIP means
     * leave-as-drawn, so the original pixels win.
     */
    fun restoreSkipPixels(original: IntArray, rendered: IntArray, w: Int, h: Int, bubbles: List<Bubble>) {
        for (b in bubbles) {
            if (b.textClass != TextClass.SKIP) continue
            val (x1, y1, x2, y2) = boxPx(b, w, h)
            for (y in y1 until y2) for (x in x1 until x2) {
                rendered[y * w + x] = original[y * w + x]
            }
        }
    }

    /**
     * Dark glyph-shaped components remaining in [wiped] inside processed
     * (visible, non-SKIP) units. A component counts when it does not touch
     * the unit box edge (edge-touching ink is the bubble outline or art
     * fused to it), has at least [MIN_GLYPH_PIXELS] pixels, was also dark
     * in [original] (not a fill artefact), and is glyph-scaled: no wider
     * than 5% of the page or taller than 8% of it. The page-09 id11 dash
     * (2px wide, tall thin stroke) is the calibration case.
     */
    fun coverageFlags(
        original: IntArray, wiped: IntArray, w: Int, h: Int, bubbles: List<Bubble>
    ): List<CoverageFlag> {
        val maxGlyphW = maxOf(24, (w * 0.05f).toInt())
        val maxGlyphH = maxOf(32, (h * 0.08f).toInt())
        val out = mutableListOf<CoverageFlag>()
        for (b in bubbles) {
            if (!b.visible || b.isNonBalloon) continue
            val (x1, y1, x2, y2) = boxPx(b, w, h)
            if (x2 <= x1 || y2 <= y1) continue
            val bw = x2 - x1; val bh = y2 - y1
            val seen = BooleanArray(bw * bh)
            val comps = mutableListOf<GlyphComponent>()
            val queue = IntArray(bw * bh)
            for (sy in 0 until bh) for (sx in 0 until bw) {
                val start = sy * bw + sx
                if (seen[start]) continue
                seen[start] = true
                if (BubbleInterior.luminance(wiped[(y1 + sy) * w + (x1 + sx)]) > DARK_LUMINANCE) continue
                var head = 0; var tail = 0
                queue[tail++] = start
                var count = 0; var darkInOriginal = 0
                var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
                var touchesEdge = false
                while (head < tail) {
                    val cur = queue[head++]
                    val cx = cur % bw; val cy = cur / bw
                    count++
                    if (BubbleInterior.luminance(original[(y1 + cy) * w + (x1 + cx)]) <= DARK_LUMINANCE) darkInOriginal++
                    if (cx < minX) minX = cx; if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
                    if (cx == 0 || cy == 0 || cx == bw - 1 || cy == bh - 1) touchesEdge = true
                    val neighbours = intArrayOf(
                        if (cx + 1 < bw) cur + 1 else -1,
                        if (cx > 0) cur - 1 else -1,
                        if (cy + 1 < bh) cur + bw else -1,
                        if (cy > 0) cur - bw else -1
                    )
                    for (n in neighbours) {
                        if (n < 0 || seen[n]) continue
                        seen[n] = true
                        val nx = n % bw; val ny = n / bw
                        if (BubbleInterior.luminance(wiped[(y1 + ny) * w + (x1 + nx)]) <= DARK_LUMINANCE) {
                            queue[tail++] = n
                        }
                    }
                }
                val cw = maxX - minX + 1; val ch = maxY - minY + 1
                if (!touchesEdge && count >= MIN_GLYPH_PIXELS &&
                    cw <= maxGlyphW && ch <= maxGlyphH && darkInOriginal * 2 >= count
                ) {
                    comps += GlyphComponent(count, cw, ch)
                }
            }
            if (comps.isNotEmpty()) {
                out += CoverageFlag(b.id, comps.sortedByDescending { it.pixels })
            }
        }
        return out
    }

    /** Report lines for [coverageFlags]; empty means no known misses. */
    fun coverageLines(flags: List<CoverageFlag>): List<String> =
        flags.map { f ->
            val parts = f.components.joinToString(",") { "${it.pixels}px(${it.width}x${it.height})" }
            "COVERAGE-FLAG unit ${f.unitId}: ${f.components.size} uncovered glyph component(s): $parts"
        }
}
