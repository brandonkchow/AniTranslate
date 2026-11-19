package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.data.models.Bubble
import com.example.pipeline.typeset.Typesetter
import com.example.utils.FontHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ShoutEmphasisTest {

    private fun page06Bubbles(): List<Bubble> = listOf(
        Bubble(1, "いやこれあれだよ！きっと名のある牛だよ！", listOf(0.75104606f, 0.0006120205f, 0.9454278f, 0.18918839f), true, "No, this is, you know! It's definitely some famous cow!", 14f, true, "bubble"),
        Bubble(2, "主なんじゃね！？", listOf(0.66012156f, 0.00847201f, 0.75628763f, 0.1906928f), true, "Could it be the guardian beast of the land!?", 14f, true, "bubble"),
        Bubble(3, "なんだよそれ", listOf(0.6076989f, 0f, 0.66761565f, 0.09202761f), true, "What's that?", 14f, true, "bubble"),
        Bubble(4, "うますぎ警報発令ーー！！", listOf(0.5512303f, 0.20530997f, 0.74310267f, 0.549765f), true, "Deliciousness alert issued!!", 14f, true, "bubble"),
        Bubble(5, "やべぇ！うますぎ警報だ！", listOf(0.3313618f, 0.2046203f, 0.46160892f, 0.45351687f), true, "Oh crap! It's a deliciousness alert!", 14f, true, "bubble"),
        Bubble(6, "肉のことはいいからみんな逃げろ！", listOf(0.19568028f, 0.20419954f, 0.33347467f, 0.43494344f), true, "Forget the meat, everyone run!", 14f, true, "bubble"),
        Bubble(7, "うるさいなあ", listOf(0.03568524f, 0.22463463f, 0.14605941f, 0.33324993f), true, "So noisy...", 14f, true, "bubble"),
        Bubble(8, "ひかえろ！やんだ！", listOf(0.8502908f, 0.5626634f, 0.96641517f, 0.7339706f), true, "Hold back! Stop it!", 14f, true, "bubble"),
        Bubble(9, "このうますぎる肉はそのお子様達の家からの差し入れだぞ！", listOf(0.70434934f, 0.57271504f, 0.86540955f, 0.7834431f), true, "This unbelievably delicious meat is a gift from those kids' family!", 14f, true, "bubble"),
        Bubble(10, "なにぃ！？", listOf(0.1831088f, 0.5635452f, 0.29893088f, 0.7304791f), true, "What?!", 14f, true, "bubble"),
        Bubble(11, "ごめん！", listOf(0.058091857f, 0.56412524f, 0.1799967f, 0.7358437f), true, "Sorry!", 14f, true, "bubble"),
        Bubble(12, "そしてありがとう！", listOf(0.77247787f, 0.7958641f, 0.90172195f, 0.96374696f), true, "And thank you!", 14f, true, "bubble"),
        Bubble(13, "昼間は蛇で泣かせてごめんな！", listOf(0.62357056f, 0.7954455f, 0.7768156f, 0.9718221f), true, "Sorry for making you cry with the snake earlier today!", 14f, true, "bubble"),
        Bubble(14, "泣いてねーし", listOf(0.5067425f, 0.7949687f, 0.60325146f, 0.9213563f), true, "I'm not crying!", 14f, true, "bubble"),
        Bubble(15, "大人うるせえ", listOf(0.3531842f, 0.80323285f, 0.41928187f, 0.8837574f), true, "Adults are so loud.", 14f, true, "floating"),
        Bubble(16, "おいしいね", listOf(0.26184246f, 0.8016938f, 0.33130732f, 0.8753924f), true, "It's delicious, isn't it?", 14f, true, "floating")
    )

    @Test
    fun `deliberate shout signal isolates page 06 shouts from calm and long exclamations`() {
        val bubbles = page06Bubbles()
        val shoutIds = bubbles.filter { Typesetter.isShoutBubble(it, it.translated) }.map { it.id }.toSet()
        assertEquals(setOf(8, 10, 11, 12, 14), shoutIds)
        // Calm units 1/7 and long exclamation units 4/5/9/13 are deliberately excluded.
        for (nonShoutId in listOf(1, 4, 5, 7, 9, 13, 15, 16)) {
            assertFalse("id $nonShoutId should not be classified as shout", nonShoutId in shoutIds)
        }
    }

    @Test
    fun `page 06 shouts meet or exceed page median speech size without hyphenation or clipping`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bmp = Bitmap.createBitmap(800, 1184, Bitmap.Config.ARGB_8888)
        val bubbles = page06Bubbles()
        val linesById = mutableMapOf<Int, List<String>>()
        Typesetter.onLayoutDecided = { id, _, _, l, _, _, _, _, _ -> linesById[id] = l }
        val result = try {
            runBlocking { Typesetter.typesetBubbles(context, bmp, bubbles) }
        } finally {
            Typesetter.onLayoutDecided = null
        }

        val calmIds = listOf(1, 2, 3, 4, 5, 6, 7, 9, 13)
        val calmSizes = calmIds.map { result.outcomes.getValue(it).fontSizePx }.sorted()
        val pageMedian = calmSizes[calmSizes.size / 2]

        val shoutIds = listOf(8, 10, 11, 12, 14)
        for (id in shoutIds) {
            val outcome = result.outcomes.getValue(id)
            assertTrue(
                "shout id=$id (${outcome.fontSizePx}px) must be >= page median (${pageMedian}px)",
                outcome.fontSizePx >= pageMedian
            )
            assertFalse("shout id=$id must not hyphenate", outcome.hyphenated)
            assertFalse("shout id=$id must not clip", outcome.clipped)
            // Whole-word at the available box ("shrink") or the grown
            // interior ("grow") — never hyphenate/floor-resort.
            assertTrue("shout id=$id phase=${outcome.phase}", outcome.phase in setOf("shrink", "grow"))
        }

        // Stacked one-word-per-line shaping on narrow shout bubbles:
        assertEquals(listOf("HOLD", "BACK!", "STOP", "IT!"), linesById[8])
        assertEquals(listOf("WHAT?!"), linesById[10])
        assertEquals(listOf("SORRY!"), linesById[11])
        assertEquals(listOf("AND", "THANK", "YOU!"), linesById[12])
        assertEquals(listOf("I'M", "NOT", "CRYING!"), linesById[14])
        assertTrue("id 14 must rise above baseline 16.96px", result.outcomes.getValue(14).fontSizePx >= 19.5f)
    }

    @Test
    fun `narrow shout stacks one word per line and holds fake-bold weight`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val typeface = FontHelper.getComicTypeface(context)
        val choice = Typesetter.calculateBestLayout(
            text = "I'M NOT CRYING!",
            availableWidth = 58,
            availableHeight = 119f,
            grownWidth = 69,
            grownHeight = 134f,
            maxSizePx = 23.7f,
            minSizePx = 18.0f,
            typeface = typeface,
            maxLines = 8,
            isShout = true,
            narrowBubble = true,
            fallbackMinSizePx = 10.6f
        )
        assertTrue("shout paint must use fake-bold weight", choice.paint.isFakeBoldText)
        assertEquals(listOf("I'M", "NOT", "CRYING!"), choice.lines)
        assertFalse(choice.hyphenated)
        assertTrue("font size ${choice.paint.textSize}px must meet 18.0px floor", choice.paint.textSize >= 18.0f)
    }
}
