package com.example

import com.example.data.models.Bubble
import com.example.pipeline.PagePipeline
import com.example.pipeline.detect.BubbleTextMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Page-identity regression (2026-11-14, BACKLOG:11): a rendered page 5 carried
 * page 1's bubbles because the pipeline reused `working_<id>.jpg` whenever the
 * file existed, with no binding to the page's own original image. A page id
 * that ever maps to a different original (rerun, reimport, subset render)
 * silently detected/translated the previous page's image.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PageIdentityTest {

    @Test
    fun workingFileNameBindsPageIdToOriginalContent() {
        val a = PagePipeline.workingFileName(900L, "aaaa1111aaaa1111aaaa1111")
        val b = PagePipeline.workingFileName(900L, "bbbb2222bbbb2222bbbb2222")
        val aAgain = PagePipeline.workingFileName(900L, "aaaa1111aaaa1111aaaa1111")
        assertNotEquals("same page id, different original must not share a working file", a, b)
        assertEquals("same page id, same original reuses its working file", a, aAgain)
    }

    @Test
    fun contentHashMatchesKnownDigest() {
        val f = File.createTempFile("page-identity", ".bin")
        try {
            f.writeBytes("abc".toByteArray(Charsets.UTF_8))
            // sha256("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                PagePipeline.contentHash(f)
            )
        } finally {
            f.delete()
        }
    }

    @Test
    fun detectorAnchoredFreeTextStaysFloatingWithItsOwnExtent() {
        // BACKLOG:13 / page 4: detector text_free [0.24,0.61,0.33,0.69] anchored the
        // long side sentence; it must keep that extent as a floating unit and
        // never be adopted into a neighbouring enclosure's box.
        val entry = BubbleTextMerger.VlmEntry(
            box = listOf(0.25f, 0.62f, 0.32f, 0.68f), text = "long side text", vertical = true
        )
        val free = com.example.pipeline.detect.DetectedRegion(
            label = com.example.pipeline.detect.OnnxBubbleDetector.LABEL_TEXT_FREE,
            score = 0.77f, box = listOf(0.2445f, 0.6177f, 0.3261f, 0.6843f)
        )
        val merged = BubbleTextMerger.merge(
            bubbles = emptyList(), textBubbles = emptyList(), floating = listOf(free),
            vlm = listOf(entry), enclosures = emptyList(), srcWidth = 1000, srcHeight = 2000
        )
        assertEquals(1, merged.size)
        assertEquals(BubbleTextMerger.SOURCE_FLOATING, merged[0].source)
        // extent = union of detector text_free region and the text block
        assertEquals(0.2445f, merged[0].box[0], 0.001f)
        assertEquals(0.6177f, merged[0].box[1], 0.001f)
        assertEquals(0.3261f, merged[0].box[2], 0.001f)
        assertEquals(0.6843f, merged[0].box[3], 0.001f)
    }

    @Test
    fun freeTextIsRenderableButSideTextIsNot() {
        // Commander ruling 2026-11-14 (BACKLOG:13): text_free units are
        // translated, wiped and written inside their own extent.
        val free = Bubble(id = 1, text = "x", box = listOf(0.1f, 0.1f, 0.2f, 0.3f), type = "floating")
        assertFalse("text_free (floating) must flow through wipe + typeset", free.isNonBalloon)
        val side = Bubble(id = 2, text = "x", box = listOf(0.1f, 0.1f, 0.2f, 0.3f), type = "side_text")
        assertTrue("side_text stays out of scope", side.isNonBalloon)
        val balloon = Bubble(id = 3, text = "x", box = listOf(0.1f, 0.1f, 0.2f, 0.3f), type = "bubble")
        assertFalse(balloon.isNonBalloon)
    }
}
