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
        enclosures: List<com.example.pipeline.wipe.EnclosureMap.Enclosure> = emptyList(),
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
        //
        //    Defect 2 reclassification: A floating entry is only legitimate when its text lies on
        //    open artwork; when the text centre sits inside a closed white enclosure whose area is
        //    at least 4x the text box area, it is a missed speech bubble. It is reclassified to
        //    SOURCE_DETECTOR with the enclosure's bounding box so the existing wipe machinery handles it.
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

            val textBoxAreaPx = (box[2] - box[0]) * srcWidth * (box[3] - box[1]) * srcHeight

            // Primary: the text sits inside a closed white enclosure (centre-containment).
            // The 4x area guard is applied to this path only — a centre hit can be a caption
            // inside a huge panel-sized enclosure. For the displaced-box fallback below the
            // guard is meaningless (the box is precisely what's wrong), so it is skipped.
            val matchedEnclosure = enclosures.firstOrNull { enc ->
                enc.containsCenter(box) || enc.containsCenter(entry.box)
            }?.let { enc ->
                if (textBoxAreaPx <= 0f || enc.area >= 4f * textBoxAreaPx) enc else null
            } ?: run {
                // Fallback (job-15 class): the detector typed the region "floating" but the box it
                // returned is displaced from the real bubble — the text's *enclosure* is the
                // nearest one that no detector bubble claimed. Only entries the detector itself
                // anchored as floating take this path; with no detector region at all the VLM box
                // is the only evidence, so no enclosure is adopted. Edge-gap distance and
                // mutual-nearest keep this from stealing a genuine caption's neighbour. The
                // primary path's area guard is deliberately dropped here: a displaced floating
                // box can be far larger than its true text, so its area proves nothing.
                val unclaimed = if (anchor != null) {
                    enclosures.filter { enc ->
                        bubbles.none { DetectorPostProcess.containsCenter(it.box, enc.normalizedBounds) }
                    }
                } else {
                    emptyList()
                }
                fun edgeGap(a: List<Float>, b: List<Float>): Float {
                    val dx = maxOf(0f, maxOf(a[0], b[0]) - minOf(a[2], b[2]))
                    val dy = maxOf(0f, maxOf(a[1], b[1]) - minOf(a[3], b[3]))
                    return dx * dx + dy * dy
                }
                val candidate = unclaimed.minByOrNull { edgeGap(it.normalizedBounds, box) }
                // edgeGap is in normalized squared units; keep the radius normalized too
                // (0.25 of the page's larger dimension).
                val radius = 0.25f
                candidate?.takeIf { enc ->
                    edgeGap(enc.normalizedBounds, box) <= radius * radius &&
                        unclaimed.none { other ->
                            other !== enc && edgeGap(other.normalizedBounds, box) < edgeGap(enc.normalizedBounds, box)
                        }
                }
            }
            val isMissedBubble = matchedEnclosure != null

            if (isMissedBubble) {
                merged += MergedBubble(
                    box = matchedEnclosure!!.normalizedBounds,
                    text = entry.text,
                    vertical = inferOrientation(
                        textBoxes = listOf(box),
                        srcWidth = srcWidth,
                        srcHeight = srcHeight,
                        fallback = entry.vertical
                    ),
                    source = SOURCE_DETECTOR
                )
            } else {
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
