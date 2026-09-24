package com.example

import com.example.pipeline.detect.BubbleTextMerger
import com.example.pipeline.detect.DetectedRegion
import com.example.pipeline.detect.DetectorPostProcess
import com.example.pipeline.detect.OnnxBubbleDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests for the pure detection geometry — no Robolectric, no model file, no network.
 *
 * The first two tests are a regression lock on a real defect. On the failing page the vision
 * model returned three *identically sized* boxes of 0.120 x 0.105 normalized — about 60%
 * undersized — for three bubbles of visibly different size, and flagged all three `vertical`
 * even though every line of text was horizontal. Those bad boxes then sheared the bubble walls
 * during wiping and truncated the typeset text.
 *
 * The fixture values below are measured, not invented: the `bubble` / `text_bubble` boxes are
 * verbatim output from `detector-v4-s_int8.onnx` on that exact 900x1350 page, and the vision
 * boxes are what the vision slot actually returned.
 */
class BubbleGeometryTest {

    private companion object {
        const val PAGE_W = 900
        const val PAGE_H = 1350

        // --- measured detector output on the real failing page -------------------------
        val BUBBLE_TOP_RIGHT = DetectedRegion("bubble", 0.9521f, listOf(0.6092f, 0.0461f, 0.9298f, 0.2281f))
        val BUBBLE_TOP_LEFT = DetectedRegion("bubble", 0.9628f, listOf(0.0713f, 0.0529f, 0.4296f, 0.2200f))
        val BUBBLE_BOTTOM_RIGHT = DetectedRegion("bubble", 0.9406f, listOf(0.5679f, 0.5423f, 0.9313f, 0.8290f))

        val TEXT_TOP_RIGHT = DetectedRegion("text_bubble", 0.9097f, listOf(0.6401f, 0.0930f, 0.8585f, 0.1712f))
        val TEXT_TOP_LEFT = DetectedRegion("text_bubble", 0.9382f, listOf(0.0993f, 0.0934f, 0.3720f, 0.1717f))
        val TEXT_BOTTOM_RIGHT = DetectedRegion("text_bubble", 0.8663f, listOf(0.5968f, 0.6000f, 0.8963f, 0.6802f))

        const val OCR_TOP_RIGHT = "うますぎ警報\n発令ーー！！"
        const val OCR_TOP_LEFT = "なんだよそれ\n主なんじゃね！？"
        const val OCR_BOTTOM_RIGHT = "肉のことはいいから\nみんな逃げろ！"

        // --- job-15 regression lock (floating text displaced from its bubble) ---------------
        // Job-15 page: 1448x2048. Values measured on the real failing page.
        const val J15_W = 1448
        const val J15_H = 2048

        // The detector typed the region "floating" with this box — displaced ~0.2 page-widths
        // right of the real bubble, which is why the shipped pipeline missed it entirely.
        val J15_FLOATING = DetectedRegion("text_free", 0.8f, listOf(0.28f, 0.80f, 0.45f, 0.88f))

        // The real bubble's enclosure, measured from the page pixels (norm 0.010,0.704,0.140,0.875).
        val J15_ENCLOSURE = com.example.pipeline.wipe.EnclosureMap.Enclosure(
            id = 1,
            area = 48930,
            pixelBounds = intArrayOf(14, 1442, 203, 1792),
            normalizedBounds = listOf(0.010f, 0.704f, 0.140f, 0.875f)
        )

        const val J15_TEXT = "なるほどこれは重症ですね"

        // --- what the vision slot actually returned: uniform, undersized, all "vertical" ---
        val VISION_OUTPUT = listOf(
            BubbleTextMerger.VlmEntry(OCR_TOP_RIGHT, listOf(0.640f, 0.070f, 0.760f, 0.175f), vertical = true),
            BubbleTextMerger.VlmEntry(OCR_TOP_LEFT, listOf(0.240f, 0.070f, 0.360f, 0.175f), vertical = true),
            BubbleTextMerger.VlmEntry(OCR_BOTTOM_RIGHT, listOf(0.570f, 0.650f, 0.710f, 0.760f), vertical = true)
        )

        val DETECTED_BUBBLES = listOf(BUBBLE_TOP_RIGHT, BUBBLE_TOP_LEFT, BUBBLE_BOTTOM_RIGHT)
        val DETECTED_TEXT = listOf(TEXT_TOP_RIGHT, TEXT_TOP_LEFT, TEXT_BOTTOM_RIGHT)
    }

    private fun mergeRealPage() = BubbleTextMerger.merge(
        bubbles = DETECTED_BUBBLES,
        vlm = VISION_OUTPUT,
        textBubbles = DETECTED_TEXT,
        floating = emptyList(),
        srcWidth = PAGE_W,
        srcHeight = PAGE_H
    )

    // ------------------------------------------------------------------ regression lock

    @Test
    fun `undersized vision boxes still land their text on the correct bubbles`() {
        val merged = mergeRealPage()

        assertEquals("all three bubbles should survive", 3, merged.size)

        // Reading order for manga is right-to-left, top-to-bottom: the top-right bubble is
        // read first even though the top-left one sits a hair lower.
        assertEquals(BUBBLE_TOP_RIGHT.box, merged[0].box)
        assertEquals(OCR_TOP_RIGHT, merged[0].text)

        assertEquals(BUBBLE_TOP_LEFT.box, merged[1].box)
        assertEquals(OCR_TOP_LEFT, merged[1].text)

        assertEquals(BUBBLE_BOTTOM_RIGHT.box, merged[2].box)
        assertEquals(OCR_BOTTOM_RIGHT, merged[2].text)

        assertTrue(merged.all { it.source == BubbleTextMerger.SOURCE_DETECTOR })
    }

    @Test
    fun `geometry is the full detector box, not the shrunken vision box`() {
        val merged = mergeRealPage()

        // The vision box covered only 0.640..0.760 of the page width; the real bubble spans
        // 0.609..0.930. The merged result must carry the detector's width, which is the whole
        // point of the change — the wipe needs the real bubble, not a 12%-wide sliver.
        val topRightWidth = merged[0].box[2] - merged[0].box[0]
        assertEquals(0.3206f, topRightWidth, 0.001f)

        val visionWidth = 0.760f - 0.640f
        assertTrue("detector box must be far wider than the vision box", topRightWidth > visionWidth * 2f)
    }

    @Test
    fun `direction comes from the text block, not the bubble outline`() {
        val merged = mergeRealPage()

        assertTrue("every line of text is horizontal", merged.none { it.vertical })

        // This is the trap that makes the text signal necessary. The bottom bubble is *taller*
        // than it is wide, so inferring direction from the bubble shape — the intuitive choice
        // — would flip it to vertical and wreck the typesetting, even though its two lines are
        // unambiguously horizontal.
        val wPx = (BUBBLE_BOTTOM_RIGHT.box[2] - BUBBLE_BOTTOM_RIGHT.box[0]) * PAGE_W
        val hPx = (BUBBLE_BOTTOM_RIGHT.box[3] - BUBBLE_BOTTOM_RIGHT.box[1]) * PAGE_H
        assertTrue("precondition: this bubble is taller than wide", hPx > wPx)

        // ...and its text block is the opposite way around, which is what we trust.
        val twPx = (TEXT_BOTTOM_RIGHT.box[2] - TEXT_BOTTOM_RIGHT.box[0]) * PAGE_W
        val thPx = (TEXT_BOTTOM_RIGHT.box[3] - TEXT_BOTTOM_RIGHT.box[1]) * PAGE_H
        assertTrue("precondition: its text block is wider than tall", twPx > thPx)
    }

    // ------------------------------------------------------------------ orientation

    @Test
    fun `orientation reads a tall text column as vertical`() {
        val vertical = BubbleTextMerger.inferOrientation(
            textBoxes = listOf(listOf(0.50f, 0.10f, 0.62f, 0.60f)),
            srcWidth = PAGE_W,
            srcHeight = PAGE_H,
            fallback = false
        )
        assertTrue(vertical)
    }

    @Test
    fun `orientation reads a wide text block as horizontal`() {
        val vertical = BubbleTextMerger.inferOrientation(
            textBoxes = listOf(listOf(0.10f, 0.10f, 0.50f, 0.20f)),
            srcWidth = PAGE_W,
            srcHeight = PAGE_H,
            fallback = true
        )
        assertFalse("a wide block must override a 'vertical' fallback", vertical)
    }

    @Test
    fun `orientation defers to the fallback when the text block is square`() {
        // 0.25 x 900 = 225px wide, 0.1667 x 1350 = 225px tall — no decisive aspect.
        val square = listOf(listOf(0.10f, 0.10f, 0.35f, 0.2667f))

        assertTrue(BubbleTextMerger.inferOrientation(square, PAGE_W, PAGE_H, fallback = true))
        assertFalse(BubbleTextMerger.inferOrientation(square, PAGE_W, PAGE_H, fallback = false))
    }

    @Test
    fun `orientation defers to the fallback with no text hints at all`() {
        assertTrue(BubbleTextMerger.inferOrientation(emptyList(), PAGE_W, PAGE_H, fallback = true))
        assertFalse(BubbleTextMerger.inferOrientation(emptyList(), PAGE_W, PAGE_H, fallback = false))
    }

    // ------------------------------------------------------------------ merge edge cases

    @Test
    fun `merge returns nothing for empty input`() {
        assertTrue(BubbleTextMerger.merge(emptyList(), emptyList(), srcWidth = PAGE_W, srcHeight = PAGE_H).isEmpty())
    }

    @Test
    fun `a bubble with no text inside is kept but not given any`() {
        val bubble = DetectedRegion("bubble", 0.9f, listOf(0.05f, 0.05f, 0.25f, 0.25f))
        val elsewhere = BubbleTextMerger.VlmEntry("free text", listOf(0.70f, 0.70f, 0.80f, 0.80f))

        val merged = BubbleTextMerger.merge(
            bubbles = listOf(bubble),
            vlm = listOf(elsewhere),
            srcWidth = PAGE_W,
            srcHeight = PAGE_H
        )

        assertEquals(2, merged.size)
        assertEquals(BubbleTextMerger.SOURCE_DETECTOR, merged[0].source)
        assertEquals("", merged[0].text)

        // The unmatched text is not thrown away — it survives as its own region.
        assertEquals(BubbleTextMerger.SOURCE_FLOATING, merged[1].source)
        assertEquals("free text", merged[1].text)
    }

    @Test
    fun `floating text is re-anchored to the detector free-text region`() {
        val entry = BubbleTextMerger.VlmEntry("ドドド", listOf(0.10f, 0.80f, 0.30f, 0.88f))
        val free = DetectedRegion("text_free", 0.77f, listOf(0.12f, 0.82f, 0.32f, 0.90f))

        val merged = BubbleTextMerger.merge(
            bubbles = emptyList(),
            vlm = listOf(entry),
            floating = listOf(free),
            srcWidth = PAGE_W,
            srcHeight = PAGE_H
        )

        assertEquals(1, merged.size)
        assertEquals(BubbleTextMerger.SOURCE_FLOATING, merged[0].source)
        // Union of the vision box and the detector's text_free box, so the wipe covers both.
        assertEquals(listOf(0.10f, 0.80f, 0.32f, 0.90f), merged[0].box)
        assertFalse(merged[0].vertical)
    }

    @Test
    fun `blank vision text never becomes a bubble`() {
        val merged = BubbleTextMerger.merge(
            bubbles = emptyList(),
            vlm = listOf(BubbleTextMerger.VlmEntry("   ", listOf(0.10f, 0.10f, 0.30f, 0.20f))),
            srcWidth = PAGE_W,
            srcHeight = PAGE_H
        )
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `floating entry inside enclosure of area 4x or greater is reclassified to detector bubble`() {
        // Defect 2 (job 15): text emitted as floating with a small box around caption area
        // when sitting inside a closed white speech bubble enclosure >= 4x the text box area.
        val textEntry = BubbleTextMerger.VlmEntry("なるほどこれは重症ですね", listOf(0.45f, 0.55f, 0.55f, 0.65f))
        val textBoxAreaPx = (0.55f - 0.45f) * PAGE_W * (0.65f - 0.55f) * PAGE_H // 0.1 * 800 * 0.1 * 1200 = 9600 px

        val largeEnclosure = com.example.pipeline.wipe.EnclosureMap.Enclosure(
            id = 1,
            area = (textBoxAreaPx * 4.5f).toInt(),
            pixelBounds = intArrayOf(320, 600, 480, 840),
            normalizedBounds = listOf(0.40f, 0.50f, 0.60f, 0.70f)
        )

        val merged = BubbleTextMerger.merge(
            bubbles = emptyList(),
            vlm = listOf(textEntry),
            enclosures = listOf(largeEnclosure),
            srcWidth = PAGE_W,
            srcHeight = PAGE_H
        )

        assertEquals(1, merged.size)
        assertEquals(BubbleTextMerger.SOURCE_DETECTOR, merged[0].source)
        assertEquals(largeEnclosure.normalizedBounds, merged[0].box)
        assertEquals("なるほどこれは重症ですね", merged[0].text)
    }

    @Test
    fun `floating entry inside small enclosure under 4x area stays floating`() {
        // The centre-containment primary path must keep its 4x area guard: a small enclosure
        // that hugs the text box is the text itself (caption lettering), not a missed bubble.
        // NOTE: this test passes NO floating region — with one, the displaced-box fallback
        // (job-15 path) would legitimately claim the nearest unclaimed enclosure regardless
        // of area, because a displaced floating box's area proves nothing.
        val textEntry = BubbleTextMerger.VlmEntry("なるほどこれは重症ですね", listOf(0.45f, 0.55f, 0.55f, 0.65f))
        val textBoxAreaPx = (0.55f - 0.45f) * PAGE_W * (0.65f - 0.55f) * PAGE_H

        val smallEnclosure = com.example.pipeline.wipe.EnclosureMap.Enclosure(
            id = 1,
            area = (textBoxAreaPx * 2.0f).toInt(), // < 4x area
            pixelBounds = intArrayOf(320, 600, 480, 840),
            normalizedBounds = listOf(0.40f, 0.50f, 0.60f, 0.70f)
        )

        val merged = BubbleTextMerger.merge(
            bubbles = emptyList(),
            vlm = listOf(textEntry),
            enclosures = listOf(smallEnclosure),
            srcWidth = PAGE_W,
            srcHeight = PAGE_H
        )

        assertEquals(1, merged.size)
        assertEquals(BubbleTextMerger.SOURCE_FLOATING, merged[0].source)
        assertEquals(listOf(0.45f, 0.55f, 0.55f, 0.65f), merged[0].box)
    }

    // ------------------------------------------------------------------ post-processing

    @Test
    fun `raw tensors become normalized regions and weak detections are dropped`() {
        val regions = DetectorPostProcess.regionsFromRawOutput(
            labels = longArrayOf(0, 1, 0),
            // flat xyxy in source pixels, stride 4
            boxes = floatArrayOf(
                90f, 135f, 450f, 675f,
                0f, 0f, 10f, 10f,
                0f, 0f, 10f, 10f
            ),
            scores = floatArrayOf(0.90f, 0.80f, 0.30f),
            numQueries = 3,
            labelNames = arrayOf("bubble", "text_bubble", "text_free"),
            srcWidth = PAGE_W,
            srcHeight = PAGE_H,
            scoreThreshold = 0.50f
        )

        assertEquals("the 0.30 detection is below threshold", 2, regions.size)

        // Post-processing returns reading order, and the text_bubble sits higher on the page
        // than the bubble, so it comes first. Assert on identity, not on position.
        val bubble = regions.single { it.label == "bubble" }
        val textBlock = regions.single { it.label == "text_bubble" }

        assertEquals(listOf(0.1f, 0.1f, 0.5f, 0.5f), bubble.box)
        assertEquals(0.90f, bubble.score, 0.001f)

        // 10 source pixels across a 900px page.
        assertEquals(0.0111f, textBlock.box[2], 0.001f)

        // ...and confirm the ordering intent: higher on the page wins the first slot.
        assertEquals("text_bubble", regions[0].label)
    }

    @Test
    fun `out-of-bounds boxes are clamped rather than trusted`() {
        val regions = DetectorPostProcess.regionsFromRawOutput(
            labels = longArrayOf(0),
            boxes = floatArrayOf(-50f, -50f, 5000f, 9000f),
            scores = floatArrayOf(0.9f),
            numQueries = 1,
            labelNames = arrayOf("bubble", "text_bubble", "text_free"),
            srcWidth = PAGE_W,
            srcHeight = PAGE_H,
            scoreThreshold = 0.5f
        )

        assertEquals(listOf(0.0f, 0.0f, 1.0f, 1.0f), regions[0].box)
    }

    @Test
    fun `an unknown label index is ignored`() {
        val regions = DetectorPostProcess.regionsFromRawOutput(
            labels = longArrayOf(9),
            boxes = floatArrayOf(0f, 0f, 100f, 100f),
            scores = floatArrayOf(0.9f),
            numQueries = 1,
            labelNames = arrayOf("bubble", "text_bubble", "text_free"),
            srcWidth = PAGE_W,
            srcHeight = PAGE_H,
            scoreThreshold = 0.5f
        )
        assertTrue(regions.isEmpty())
    }

    @Test
    fun `duplicate detections collapse to the highest scoring one`() {
        val strong = DetectedRegion("bubble", 0.90f, listOf(0.100f, 0.100f, 0.300f, 0.300f))
        val weakerTwin = DetectedRegion("bubble", 0.60f, listOf(0.105f, 0.105f, 0.305f, 0.305f))
        val distant = DetectedRegion("bubble", 0.70f, listOf(0.600f, 0.600f, 0.800f, 0.800f))

        val kept = DetectorPostProcess.suppressDuplicates(listOf(weakerTwin, strong, distant))

        assertEquals(2, kept.size)
        assertEquals(0.90f, kept[0].score, 0.001f)
        assertEquals(0.70f, kept[1].score, 0.001f)
    }

    @Test
    fun `containsCenter accepts a bad box that still sits on the right bubble`() {
        val outer = listOf(0.20f, 0.20f, 0.60f, 0.60f)

        // Deliberately wrong size, correct position — exactly the vision failure mode.
        assertTrue(DetectorPostProcess.containsCenter(outer, listOf(0.35f, 0.35f, 0.45f, 0.45f)))
        assertFalse(DetectorPostProcess.containsCenter(outer, listOf(0.70f, 0.10f, 0.80f, 0.20f)))
    }

    @Test
    fun `region accessors partition the detector output by label`() {
        val all = listOf(BUBBLE_TOP_RIGHT, TEXT_TOP_RIGHT, DetectedRegion("text_free", 0.7f, listOf(0.1f, 0.7f, 0.3f, 0.8f)))

        assertEquals(1, DetectorPostProcess.bubbleRegions(all).size)
        assertEquals(1, DetectorPostProcess.textBubbleRegions(all).size)
        assertEquals(1, DetectorPostProcess.freeTextRegions(all).size)
        assertEquals("bubble", DetectorPostProcess.bubbleRegions(all)[0].label)
    }

    @Test
    fun `union box spans every input and is null for none`() {
        assertNotNull(DetectorPostProcess.unionBox(listOf(listOf(0.1f, 0.2f, 0.3f, 0.4f))))
        assertEquals(
            listOf(0.1f, 0.1f, 0.5f, 0.5f),
            DetectorPostProcess.unionBox(listOf(listOf(0.1f, 0.1f, 0.3f, 0.3f), listOf(0.3f, 0.3f, 0.5f, 0.5f)))
        )
        assertTrue(DetectorPostProcess.unionBox(emptyList()) == null)
    }

    @Test
    fun `the detector label contract matches the model config`() {
        assertEquals("bubble", OnnxBubbleDetector.LABEL_BUBBLE)
        assertEquals("text_bubble", OnnxBubbleDetector.LABEL_TEXT_BUBBLE)
        assertEquals("text_free", OnnxBubbleDetector.LABEL_TEXT_FREE)
    }

    // --- regression locks for the job-15 class: floating text displaced from its bubble -------

    @Test
    fun `a floating entry displaced from its bubble reclassifies onto the nearest unclaimed enclosure`() {
        val merged = BubbleTextMerger.merge(
            bubbles = emptyList(),
            vlm = listOf(BubbleTextMerger.VlmEntry(text = J15_TEXT, box = J15_FLOATING.box, vertical = true)),
            floating = listOf(J15_FLOATING),
            enclosures = listOf(J15_ENCLOSURE),
            srcWidth = J15_W,
            srcHeight = J15_H
        )

        assertEquals(1, merged.size)
        val bubble = merged[0]
        assertEquals(BubbleTextMerger.SOURCE_DETECTOR, bubble.source)
        assertEquals(J15_ENCLOSURE.normalizedBounds, bubble.box)
        assertEquals(J15_TEXT, bubble.text)
    }

    @Test
    fun `a floating entry with no nearby enclosure stays floating`() {
        // Same page geometry, but the only enclosure is a detector-claimed bubble far away.
        val merged = BubbleTextMerger.merge(
            bubbles = listOf(
                DetectedRegion(
                    "bubble",
                    0.9f,
                    listOf(0.80f, 0.02f, 0.98f, 0.18f)
                )
            ),
            vlm = listOf(BubbleTextMerger.VlmEntry(text = "スンッ…", box = listOf(0.02f, 0.03f, 0.1f, 0.1f), vertical = false)),
            floating = listOf(DetectedRegion("text_free", 0.7f, listOf(0.02f, 0.03f, 0.1f, 0.1f))),
            enclosures = listOf(J15_ENCLOSURE),
            srcWidth = J15_W,
            srcHeight = J15_H
        )

        assertEquals(2, merged.size)
        val floatingOut = merged.first { it.text == "スンッ…" }
        assertEquals(BubbleTextMerger.SOURCE_FLOATING, floatingOut.source)
    }
}
