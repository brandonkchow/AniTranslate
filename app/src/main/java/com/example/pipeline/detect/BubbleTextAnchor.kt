package com.example.pipeline.detect

import kotlin.math.hypot

/**
 * Re-anchors misplaced vision text boxes onto the detector's `text_bubble` geometry, and reports
 * text that the reading slot never produced content for.
 *
 * Why this exists — a real defect, not a hypothetical:
 *
 * [BubbleTextMerger] attaches text to a bubble by centre-containment of the *vision* box. The
 * vision slot is not a localiser; on the page that exposed this it read a bubble's line correctly
 * but returned a box ~42 px to the right, centred in the gap between two bubbles. Its centre fell
 * outside the bubble it had read, so:
 *
 *  - that bubble kept its Japanese and was never wiped (silently "left untouched, no text inside"),
 *  - the orphaned box became a `floating` region, and the translation was typeset into empty
 *    artwork beside the bubble,
 *  - and a second bubble whose text the slot never read at all was dropped the same way.
 *
 * The detector's `text_bubble` region is the authoritative answer to *where the text is* — it
 * measures the glyphs, which is exactly what the vision box failed at. So an orphan box is snapped
 * onto the text region of a bubble that has no text yet, when one lies within
 * [MAX_ANCHOR_GAP_FRACTION] of the page diagonal: the text lands on the right bubble, and the
 * phantom floating region disappears because the box is no longer orphaned.
 *
 * Two deliberate restrictions:
 *
 *  - **Only unfilled bubbles are anchor targets.** A bubble already claimed by the reading slot is
 *    never a target, so a snap cannot duplicate text onto a bubble that was read correctly. This
 *    is what makes the choice structural rather than a distance coin-flip: on the page that
 *    exposed the defect the correct target was 42.5 px away and a *correctly read* bubble's text
 *    was 75.1 px away — nearest-wins alone would have depended on a 1.8x margin, whereas
 *    "unfilled bubble only" excludes the runner-up by construction.
 *  - **Nothing heuristic about content.** Text is never invented, modified, or dropped here; only
 *    geometry is corrected. A box that is far from every target region is returned unchanged, so
 *    genuine on-artwork text (sound effects, signage) keeps its own geometry, and a page with no
 *    orphans passes through untouched.
 */
object BubbleTextAnchor {

    /**
     * Largest orphan→text-region gap accepted, as a fraction of the page diagonal.
     *
     * Measured on the real 1080x1507 page that exposed the defect (diagonal 1854 px, cap 92.7 px):
     * the orphan sat **42.5 px** from the region it had actually read, **75.1 px** from a
     * correctly-read neighbour's text, and **136.8 px** from the next one after that. The cap
     * admits the first two and rejects the rest; the unfilled-bubble rule is what chooses between
     * the first two. It is a guard against nonsense assignments, not the discriminator.
     */
    const val MAX_ANCHOR_GAP_FRACTION = 0.05f

    /** Text the detector found inside a bubble, with no reading-slot content to go with it. */
    data class UnreadText(val box: List<Float>, val score: Float)

    data class Result(
        /** The reading-slot entries, with any orphaned box snapped onto detector text geometry. */
        val entries: List<BubbleTextMerger.VlmEntry>,
        /** How many boxes were re-anchored (0 on a healthy page). */
        val snappedCount: Int,
        /** Detected text inside bubbles that no entry accounted for. Never silently drop. */
        val unread: List<UnreadText>
    )

    /**
     * @param bubbles detector `bubble` regions — the authoritative wipe/typeset geometry.
     * @param vlm text boxes as returned by the reading slot, in reading order.
     * @param textBubbles detector `text_bubble` regions — where the glyphs actually are.
     */
    fun snapOrphans(
        bubbles: List<DetectedRegion>,
        vlm: List<BubbleTextMerger.VlmEntry>,
        textBubbles: List<DetectedRegion>,
        srcWidth: Int,
        srcHeight: Int
    ): Result {
        val capPx = MAX_ANCHOR_GAP_FRACTION *
            hypot(srcWidth.toDouble(), srcHeight.toDouble()).toFloat()

        // Which bubbles already have text located inside them? Those are not anchor targets.
        val filled = BooleanArray(bubbles.size) { i ->
            vlm.any {
                it.text.isNotBlank() && DetectorPostProcess.containsCenter(bubbles[i].box, it.box)
            }
        }

        var snapped = 0
        val entries = ArrayList<BubbleTextMerger.VlmEntry>(vlm.size)
        for (entry in vlm) {
            // Already inside a bubble? The merge's own matching handles it — leave it alone.
            if (bubbles.any { DetectorPostProcess.containsCenter(it.box, entry.box) }) {
                entries += entry
                continue
            }

            var bestGap = Float.MAX_VALUE
            var bestBubble = -1
            var bestBox: List<Float>? = null
            for (i in bubbles.indices) {
                if (filled[i]) continue
                for (text in textBubbles) {
                    if (!DetectorPostProcess.containsCenter(bubbles[i].box, text.box)) continue
                    val gap = gapPx(text.box, entry.box, srcWidth, srcHeight)
                    if (gap <= capPx && gap < bestGap) {
                        bestGap = gap
                        bestBubble = i
                        bestBox = text.box
                    }
                }
            }
            if (bestBox == null) {
                entries += entry
                continue
            }
            filled[bestBubble] = true
            snapped++
            entries += entry.copy(box = bestBox)
        }

        return Result(entries, snapped, unreadText(bubbles, entries, textBubbles))
    }

    /**
     * Detected text regions sitting inside a bubble for which no entry's centre falls inside that
     * same bubble — i.e. the reading slot returned nothing for text we know exists.
     *
     * These are the cases that must never pass silently: the caller logs them, and (absent an OCR
     * stage) leaves the artwork untouched rather than wiping a bubble it cannot fill.
     */
    fun unreadText(
        bubbles: List<DetectedRegion>,
        vlm: List<BubbleTextMerger.VlmEntry>,
        textBubbles: List<DetectedRegion>
    ): List<UnreadText> {
        val out = ArrayList<UnreadText>()
        for (bubble in bubbles) {
            val inside = textBubbles.filter { DetectorPostProcess.containsCenter(bubble.box, it.box) }
            if (inside.isEmpty()) continue
            val read = vlm.any {
                it.text.isNotBlank() && DetectorPostProcess.containsCenter(bubble.box, it.box)
            }
            if (!read) inside.forEach { out += UnreadText(it.box, it.score) }
        }
        return out
    }

    /**
     * Edge-to-edge gap between two normalized boxes, in source pixels; 0 when they overlap.
     *
     * Edge distance rather than centre distance, because a misplaced vision box is usually the
     * right size in the wrong place: the centres of two abutting boxes can be 200 px apart while
     * their edges touch, and it is the edge that says "this is the same text".
     */
    fun gapPx(a: List<Float>, b: List<Float>, srcWidth: Int, srcHeight: Int): Float {
        if (a.size != 4 || b.size != 4) return Float.MAX_VALUE
        val dx = maxOf(0f, maxOf(a[0] - b[2], b[0] - a[2])) * srcWidth
        val dy = maxOf(0f, maxOf(a[1] - b[3], b[1] - a[3])) * srcHeight
        return hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }
}
