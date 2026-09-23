package com.example

import com.example.pipeline.detect.BubbleTextAnchor
import com.example.pipeline.detect.BubbleTextMerger
import com.example.pipeline.detect.DetectedRegion
import com.example.pipeline.detect.DetectorPostProcess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests for text anchoring — no Robolectric, no model file, no network.
 *
 * The fixture is **not synthetic**. It is the complete detection output of the real page that
 * exposed the dropped-bubble defect (1080x1507, run 20260923_job9), reproduced byte-exactly on the
 * PC with the same preprocessing the phone uses:
 *
 *  - all **9** `bubble` regions and all **9** `text_bubble` regions the detector found, and
 *  - the **8** text boxes the reading slot returned, with the boxes exactly as the run recorded
 *    them (the 7 matched bubbles carry detector geometry because the merge rewrote them; the 8th
 *    still carries the raw vision box — which is the whole point, it is the misplaced one).
 *
 * The defect, in numbers: the reading slot read the bubble at x5.9%..29.3% y80.9%..96.8% correctly
 * but returned a box centred at x36.4% — outside that bubble, in the gutter. Its centre missed, so
 * that bubble kept its Japanese, its translation was typeset into empty artwork as a `floating`
 * region, and a second bubble (x31.5%..49.8% y61.9%..74.6%) whose text the slot never read at all
 * was dropped with the same false "no text inside" log. Two bubbles of detected text, silently
 * lost. This test locks both halves of the fix.
 */
class BubbleTextAnchorTest {

    /** The real page. Coordinates are the ones the on-device detector produces. */
    private val srcW = 1080
    private val srcH = 1507

    // ---- the two bubbles that were dropped, and the text regions inside them ------------------
    /** Bubble the slot read but misplaced the box for (score 0.9248). */
    private val bubbleReadButMisplaced = listOf(0.0589f, 0.8088f, 0.2925f, 0.9681f)
    /** Text region inside [bubbleReadButMisplaced] (score 0.9174) — where the read text really is. */
    private val textOfMisplacedBubble = listOf(0.0892f, 0.8393f, 0.2487f, 0.9527f)
    /** Bubble whose text the slot never read at all (score 0.8489). */
    private val bubbleNeverRead = listOf(0.3145f, 0.6192f, 0.4978f, 0.7463f)
    /** Text region inside [bubbleNeverRead] (score 0.7352) — still unread after the fix. */
    private val textOfUnreadBubble = listOf(0.3889f, 0.6304f, 0.4742f, 0.7292f)

    /** The orphaned vision box: raw, un-rewritten, and centred outside any bubble. */
    private val orphanBox = listOf(0.288f, 0.820f, 0.440f, 0.918f)
    private val orphanText = "飲(の)めるドブじゃア!!"

    /** All 9 real `bubble` regions, in the detector's reading order. */
    private val bubbles = listOf(
        DetectedRegion("bubble", 0.9410f, listOf(0.6374f, 0.0364f, 0.9064f, 0.2265f)),
        DetectedRegion("bubble", 0.9526f, listOf(0.5024f, 0.2650f, 0.7265f, 0.4273f)),
        DetectedRegion("bubble", 0.9324f, listOf(0.0599f, 0.3482f, 0.2869f, 0.4961f)),
        DetectedRegion("bubble", 0.8431f, listOf(0.3454f, 0.5103f, 0.5001f, 0.6141f)),
        DetectedRegion("bubble", 0.9532f, listOf(0.5023f, 0.5105f, 0.7401f, 0.6948f)),
        DetectedRegion("bubble", 0.8489f, listOf(0.3145f, 0.6192f, 0.4978f, 0.7463f)),
        DetectedRegion("bubble", 0.9332f, listOf(0.7323f, 0.7667f, 0.9439f, 0.9674f)),
        DetectedRegion("bubble", 0.9427f, listOf(0.4637f, 0.7988f, 0.7548f, 0.9579f)),
        DetectedRegion("bubble", 0.9248f, listOf(0.0589f, 0.8088f, 0.2925f, 0.9681f))
    )

    /** All 9 real `text_bubble` regions. */
    private val textBubbles = listOf(
        DetectedRegion("text_bubble", 0.8288f, listOf(0.6845f, 0.0618f, 0.8644f, 0.2051f)),
        DetectedRegion("text_bubble", 0.9179f, listOf(0.5209f, 0.2862f, 0.6985f, 0.4003f)),
        DetectedRegion("text_bubble", 0.8901f, listOf(0.0921f, 0.3705f, 0.2374f, 0.4820f)),
        DetectedRegion("text_bubble", 0.8851f, listOf(0.3891f, 0.5208f, 0.4822f, 0.6096f)),
        DetectedRegion("text_bubble", 0.9499f, listOf(0.5218f, 0.5255f, 0.7097f, 0.6704f)),
        DetectedRegion("text_bubble", 0.7352f, listOf(0.3889f, 0.6304f, 0.4742f, 0.7292f)),
        DetectedRegion("text_bubble", 0.9491f, listOf(0.7620f, 0.7805f, 0.9231f, 0.9438f)),
        DetectedRegion("text_bubble", 0.9513f, listOf(0.5095f, 0.8272f, 0.7272f, 0.9328f)),
        DetectedRegion("text_bubble", 0.9174f, listOf(0.0892f, 0.8393f, 0.2487f, 0.9527f))
    )

    /** The 7 entries the slot got right — boxes as the run recorded them (detector geometry). */
    private val matchedEntries = listOf(
        BubbleTextMerger.VlmEntry("この店(みせ)はコーヒーが美味(うま)いんだ", listOf(0.6374f, 0.0364f, 0.9064f, 0.2265f), true),
        BubbleTextMerger.VlmEntry("ふ〜ん コーヒー初(はじ)めて飲(の)むぜ", listOf(0.5024f, 0.2650f, 0.7265f, 0.4273f), true),
        BubbleTextMerger.VlmEntry("うわっ こりゃドロ水(みず)だ！", listOf(0.0599f, 0.3482f, 0.2869f, 0.4961f), true),
        BubbleTextMerger.VlmEntry("愚(おろ)かじゃなガキにはわからん味(あじ)なんじゃ！", listOf(0.5023f, 0.5105f, 0.7401f, 0.6948f), true),
        BubbleTextMerger.VlmEntry("うっええ ドブス!!", listOf(0.3454f, 0.5103f, 0.5001f, 0.6141f), true),
        BubbleTextMerger.VlmEntry("うるさい！ 店(みせ)では静(しず)かにしろ！", listOf(0.7323f, 0.7667f, 0.9439f, 0.9674f), true),
        BubbleTextMerger.VlmEntry("やがって騙(だま)しドロじゃねえか！", listOf(0.4637f, 0.7988f, 0.7548f, 0.9579f), false)
    )

    /** The 8th entry: read correctly, located wrongly — its centre (36.4%, 86.9%) is in the gutter. */
    private val misplacedEntry = BubbleTextMerger.VlmEntry(
        orphanText, listOf(0.288f, 0.820f, 0.440f, 0.918f), true
    )

    private val realRun = matchedEntries + misplacedEntry

    private fun anchor(vlm: List<BubbleTextMerger.VlmEntry>) =
        BubbleTextAnchor.snapOrphans(bubbles, vlm, textBubbles, srcW, srcH)

    private fun merge(vlm: List<BubbleTextMerger.VlmEntry>) = BubbleTextMerger.merge(
        bubbles = bubbles,
        vlm = vlm,
        textBubbles = textBubbles,
        floating = emptyList(),
        srcWidth = srcW,
        srcHeight = srcH
    )

    // ---- the defect: two bubbles' text lost silently -------------------------------------------

    @Test
    fun `before anchoring both dropped bubbles are flagged unread`() {
        // This is the pre-fix state: B7's text region and B8's text region both have no entry
        // whose centre lands inside their bubble. Two regions of detected text, no content.
        val unread = BubbleTextAnchor.unreadText(bubbles, realRun, textBubbles)
        assertEquals(2, unread.size)
        assertTrue(unread.any { it.box == textOfMisplacedBubble })
        assertTrue(unread.any { it.box == textOfUnreadBubble })
    }

    // ---- fix half 1: the misplaced box is re-anchored onto the bubble it actually read ---------

    @Test
    fun `misplaced vision box is anchored onto the bubble it read`() {
        val result = anchor(realRun)

        assertEquals(1, result.snappedCount)
        assertEquals(orphanText, result.entries.last().text)
        // Snapped to the text region inside the bubble it read — not the neighbour's text region
        // 75.1 px away, which belongs to a bubble that was already read correctly.
        assertEquals(textOfMisplacedBubble, result.entries.last().box)
    }

    @Test
    fun `anchored text lands on its bubble and the phantom floating region disappears`() {
        val merged = merge(anchor(realRun).entries)

        val fixed = merged.first { it.box == bubbleReadButMisplaced }
        assertEquals(orphanText, fixed.text)
        assertEquals(BubbleTextMerger.SOURCE_DETECTOR, fixed.source)

        // The translation is no longer typeset into empty artwork beside the bubble.
        assertFalse(merged.any { it.source == BubbleTextMerger.SOURCE_FLOATING })
        assertFalse(merged.any { it.box == orphanBox })
    }

    // ---- fix half 2: the bubble nobody read is reported, never dropped silently -----------------

    @Test
    fun `text the slot never read stays visible as an unread region`() {
        val unread = anchor(realRun).unread

        assertEquals(1, unread.size)
        assertEquals(textOfUnreadBubble, unread[0].box)
        assertEquals(0.7352f, unread[0].score, 0.0001f)
        // …and it is a region inside a real detected bubble, i.e. we know text is there.
        val owner = bubbles.first { DetectorPostProcess.containsCenter(it.box, unread[0].box) }
        assertEquals(bubbleNeverRead, owner.box)
    }

    // ---- the fix must not fire where it has no business firing ---------------------------------

    @Test
    fun `a page with no orphaned box is passed through untouched`() {
        val result = anchor(matchedEntries)

        assertEquals(0, result.snappedCount)
        assertEquals(matchedEntries, result.entries)
    }

    @Test
    fun `an orphan far from every text region keeps its own geometry`() {
        val faraway = BubbleTextMerger.VlmEntry("ドドド", listOf(0.85f, 0.30f, 0.95f, 0.40f), false)

        val result = anchor(listOf(faraway))

        assertEquals(0, result.snappedCount)
        assertEquals(faraway, result.entries.single())
    }

    @Test
    fun `a bubble with no detected text is never reported as unread`() {
        // Bubbles 1 and 2 in the fixture have text of their own; strip every entry and confirm
        // only bubbles that really contain a text region are reported.
        val unread = BubbleTextAnchor.unreadText(bubbles, emptyList(), textBubbles)

        assertEquals(textBubbles.size, unread.size) // every text region sits inside a bubble here
        assertTrue(unread.all { region ->
            bubbles.any { DetectorPostProcess.containsCenter(it.box, region.box) }
        })
    }

    // ---- the measurement the cap is derived from -----------------------------------------------

    @Test
    fun `measured gaps match the real page`() {
        // Edge-to-edge, in source pixels: 42.4 px to the region it read, 136.9 px to the next
        // candidate past it. The cap (5% of the 1854 px diagonal = 92.7 px) separates them.
        assertEquals(42.4f, BubbleTextAnchor.gapPx(textOfMisplacedBubble, orphanBox, srcW, srcH), 0.5f)
        assertEquals(136.9f, BubbleTextAnchor.gapPx(textOfUnreadBubble, orphanBox, srcW, srcH), 0.5f)
        assertEquals(0f, BubbleTextAnchor.gapPx(textOfMisplacedBubble, textOfMisplacedBubble, srcW, srcH), 0f)
        assertTrue(BubbleTextAnchor.MAX_ANCHOR_GAP_FRACTION * 1854f in 42.4f..136.9f)
    }
}
