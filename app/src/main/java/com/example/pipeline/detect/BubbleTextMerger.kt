package com.example.pipeline.detect

/**
 * Fuses two sources that are each good at exactly one thing:
 *
 *  - the on-device detector is authoritative for **geometry** (measured ~1% absolute box error)
 *  - the vision LLM is authoritative for **text** (it read every glyph on the failing page correctly)
 *
 * The previous design asked the LLM for both, and because box regression is its weak suit it
 * returned three differently-sized bubbles at a uniform 12% width — roughly 60% undersized —
 * which then cascaded into the wipe cutting the bubble walls and the typesetter wrapping
 * "DELICIOUSNESS" as "DELICIOUS / NESS".
 *
 * Matching is by centre-containment rather than IoU on purpose: a VLM box can be badly sized
 * yet still sit on the right bubble, whereas an IoU test would reject exactly those cases.
 * On the real failing page all three undersized boxes still centred inside their true bubbles,
 * so this pairing recovers the correct text without a second API call.
 *
 * Pure Kotlin, no Android or ORT dependencies, so it is fully unit-testable.
 */
object BubbleTextMerger {

    /** A text region as reported by the cloud vision slot. */
    data class VlmEntry(
        val text: String,
        /** Normalized [x1, y1, x2, y2]. */
        val box: List<Float>,
        val vertical: Boolean = false
    )

    /** A bubble ready for wiping and typesetting. */
    data class MergedBubble(
        val box: List<Float>,
        val text: String,
        val vertical: Boolean,
        /** `detector` = geometry from ONNX, `floating` = text outside any detected bubble. */
        val source: String
    )

    const val SOURCE_DETECTOR = "detector"
    const val SOURCE_FLOATING = "floating"

    /**
     * Aspect ratio beyond which a text block's shape alone decides direction.
     * Below it the block is near-square and the vision model's own flag is more informative.
     */
    private const val ORIENTATION_MARGIN = 1.15f

    /** Minimum IoU for detector free-text geometry to be treated as the same region. */
    private const val FREE_TEXT_ANCHOR_IOU = 0.30f

    /**
     * Build the final bubble list.
     *
     * @param bubbles     detector bubble regions (geometry authority)
     * @param vlm         text regions from the vision slot
     * @param textBubbles detector `text_bubble` regions — used purely to read text direction
     * @param floating    detector `text_free` regions, used to re-anchor text lying on the art
     * @param srcWidth    source image width, needed to compare aspect ratios in real pixels
     * @param srcHeight   source image height
     */
    fun merge(
        bubbles: List<DetectedRegion>,
        vlm: List<VlmEntry>,
        textBubbles: List<DetectedRegion> = emptyList(),
        floating: List<DetectedRegion> = emptyList(),
        srcWidth: Int,
        srcHeight: Int
    ): List<MergedBubble> {
        if (bubbles.isEmpty() && vlm.isEmpty()) return emptyList()

        val consumed = BooleanArray(vlm.size)
        val merged = ArrayList<MergedBubble>(bubbles.size + vlm.size)

        // 1. Every detected bubble becomes an output region. Text is attached when the vision
        //    slot produced something inside it. A bubble that matched no text is still returned
        //    (blank bubbles are legitimate in manga) but with empty text; the caller decides
        //    whether to wipe it, and it can never be typeset with anything.
        for (bubble in bubbles) {
            val matches = ArrayList<Pair<Int, VlmEntry>>()
            for ((index, entry) in vlm.withIndex()) {
                if (consumed[index]) continue
                if (DetectorPostProcess.containsCenter(bubble.box, entry.box)) {
                    matches += index to entry
                }
            }
            matches.forEach { consumed[it.first] = true }

            val ordered = matches
                .sortedWith(DetectorPostProcess.readingOrderComparator { it.second.box })
                .map { it.second }

            // Direction comes from the TEXT's own shape. Using the bubble outline instead is a
            // trap: one of the three real bubbles is 327x387px (taller than wide) yet holds two
            // horizontal lines, so bubble aspect would flip it to vertical and corrupt the type.
            val textHints = textBubbles
                .filter { DetectorPostProcess.containsCenter(bubble.box, it.box) }
                .map { it.box }

            merged += MergedBubble(
                box = bubble.box,
                text = ordered.map { it.text }.filter { it.isNotBlank() }.joinToString("\n"),
                vertical = inferOrientation(
                    textBoxes = textHints,
                    srcWidth = srcWidth,
                    srcHeight = srcHeight,
                    fallback = ordered.isNotEmpty() && ordered.count { it.vertical } * 2 > ordered.size
                ),
                source = SOURCE_DETECTOR
            )
        }

        // 2. Anything the vision slot saw but no bubble claimed is text lying on the artwork.
        //    Re-anchor it to the detector's own free-text region when one overlaps, otherwise
        //    fall back to the vision model's box so no content is silently dropped.
        for ((index, entry) in vlm.withIndex()) {
            if (consumed[index]) continue
            if (entry.text.isBlank()) continue

            val anchor = floating
                .filter { DetectorPostProcess.iou(it.box, entry.box) >= FREE_TEXT_ANCHOR_IOU }
                .maxByOrNull { DetectorPostProcess.iou(it.box, entry.box) }

            val box = if (anchor != null) {
                DetectorPostProcess.unionBox(listOf(anchor.box, entry.box)) ?: entry.box
            } else {
                entry.box
            }

            merged += MergedBubble(
                box = box,
                text = entry.text,
                // Here the box describes the text directly, so it is a valid direction hint.
                vertical = inferOrientation(
                    textBoxes = listOf(box),
                    srcWidth = srcWidth,
                    srcHeight = srcHeight,
                    fallback = entry.vertical
                ),
                source = SOURCE_FLOATING
            )
        }

        return merged.sortedWith(DetectorPostProcess.readingOrderComparator { it.box })
    }

    /**
     * Decide text direction from the shape of the text block itself.
     *
     * A vertical Japanese column is markedly taller than wide; horizontal lines are the
     * opposite. When the block is near-square — or absent, as for a genuinely empty bubble —
     * we defer to what the vision model reported.
     */
    fun inferOrientation(
        textBoxes: List<List<Float>>,
        srcWidth: Int,
        srcHeight: Int,
        fallback: Boolean
    ): Boolean {
        if (srcWidth <= 0 || srcHeight <= 0) return fallback
        val union = DetectorPostProcess.unionBox(textBoxes) ?: return fallback

        val wPx = (union[2] - union[0]) * srcWidth
        val hPx = (union[3] - union[1]) * srcHeight
        if (wPx <= 0f || hPx <= 0f) return fallback

        return when {
            hPx > wPx * ORIENTATION_MARGIN -> true
            wPx > hPx * ORIENTATION_MARGIN -> false
            else -> fallback
        }
    }
}
