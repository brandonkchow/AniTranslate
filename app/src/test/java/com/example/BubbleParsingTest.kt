package com.example

import com.example.data.models.Bubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleParsingTest {

    @Test
    fun testBubbleCoordinateNormalization() {
        val bubble = Bubble(
            id = 1,
            box = listOf(0.1f, 0.2f, 0.4f, 0.6f), // x1=0.1, y1=0.2, x2=0.4, y2=0.6
            text = "こんにちは",
            translated = "Hello",
            fontSizeSp = 14f,
            visible = true
        )

        assertEquals(0.1f, bubble.x1, 0.001f)
        assertEquals(0.2f, bubble.y1, 0.001f)
        assertEquals(0.4f, bubble.x2, 0.001f)
        assertEquals(0.6f, bubble.y2, 0.001f)
        assertEquals(0.3f, bubble.width, 0.001f)
        assertEquals(0.4f, bubble.height, 0.001f)
    }

    @Test
    fun testMangaReadingOrderSorting() {
        // Traditional manga order: Right-to-Left, Top-to-Bottom
        val bubbleTopRight = Bubble(
            id = 99,
            box = listOf(0.7f, 0.1f, 0.9f, 0.3f), // x1=0.7, y1=0.1 (top right)
            text = "最初",
            translated = "First"
        )
        val bubbleTopLeft = Bubble(
            id = 88,
            box = listOf(0.1f, 0.1f, 0.3f, 0.3f), // x1=0.1, y1=0.1 (top left)
            text = "二番目",
            translated = "Second"
        )
        val bubbleBottomRight = Bubble(
            id = 77,
            box = listOf(0.7f, 0.6f, 0.9f, 0.8f), // x1=0.7, y1=0.6 (bottom right)
            text = "三番目",
            translated = "Third"
        )
        val bubbleBottomLeft = Bubble(
            id = 66,
            box = listOf(0.1f, 0.6f, 0.3f, 0.8f), // x1=0.1, y1=0.6 (bottom left)
            text = "四番目",
            translated = "Fourth"
        )

        val unsortedList = listOf(bubbleBottomLeft, bubbleTopLeft, bubbleBottomRight, bubbleTopRight)
        val sortedList = Bubble.sortByMangaReadingOrder(unsortedList)

        assertEquals(4, sortedList.size)
        assertEquals("最初", sortedList[0].text) // Top right
        assertEquals(1, sortedList[0].id)
        assertEquals("二番目", sortedList[1].text) // Top left
        assertEquals(2, sortedList[1].id)
        assertEquals("三番目", sortedList[2].text) // Bottom right
        assertEquals(3, sortedList[2].id)
        assertEquals("四番目", sortedList[3].text) // Bottom left
        assertEquals(4, sortedList[3].id)
    }

    @Test
    fun testBubbleVisibilityToggle() {
        val bubble = Bubble(
            id = 1,
            box = listOf(0.1f, 0.1f, 0.3f, 0.3f),
            text = "テスト",
            translated = "Test",
            visible = true
        )

        val hiddenBubble = bubble.copy(visible = !bubble.visible)
        assertEquals(false, hiddenBubble.visible)

        val restoredBubble = hiddenBubble.copy(visible = !hiddenBubble.visible)
        assertEquals(true, restoredBubble.visible)
    }

    @Test
    fun testBubbleParsingGeminiFormats() {
        // Standard [x1, y1, x2, y2]
        val json1 = org.json.JSONObject("""
            {"id": 1, "text": "助けて！", "box": [0.70, 0.10, 0.95, 0.35], "vertical": true}
        """.trimIndent())
        val bubble1 = Bubble.fromJson(json1)
        assertEquals(1, bubble1.id)
        assertEquals("助けて！", bubble1.text)
        assertEquals(0.70f, bubble1.x1, 0.001f)
        assertEquals(0.10f, bubble1.y1, 0.001f)
        assertEquals(0.95f, bubble1.x2, 0.001f)
        assertEquals(0.35f, bubble1.y2, 0.001f)
        assertEquals(true, bubble1.vertical)

        // Gemini box_2d 0..1000 format: [ymin, xmin, ymax, xmax]
        val json2 = org.json.JSONObject("""
            {"id": 2, "ocr": "なんだって！？", "box_2d": [100, 700, 350, 950]}
        """.trimIndent())
        val bubble2 = Bubble.fromJson(json2)
        assertEquals(2, bubble2.id)
        assertEquals("なんだって！？", bubble2.text)
        assertEquals(0.70f, bubble2.x1, 0.001f)
        assertEquals(0.10f, bubble2.y1, 0.001f)
        assertEquals(0.95f, bubble2.x2, 0.001f)
        assertEquals(0.35f, bubble2.y2, 0.001f)

        // Object coordinates {x1, y1, x2, y2}
        val json3 = org.json.JSONObject("""
            {"index": 3, "japanese": "了解", "box": {"x1": 0.2, "y1": 0.3, "x2": 0.5, "y2": 0.6}}
        """.trimIndent())
        val bubble3 = Bubble.fromJson(json3)
        assertEquals(3, bubble3.id)
        assertEquals("了解", bubble3.text)
        assertEquals(0.2f, bubble3.x1, 0.001f)
        assertEquals(0.3f, bubble3.y1, 0.001f)
        assertEquals(0.5f, bubble3.x2, 0.001f)
        assertEquals(0.6f, bubble3.y2, 0.001f)
    }

    @Test
    fun testFreeTextAndNarrationTypes() {
        val json = org.json.JSONObject("""
            {
                "id": 1,
                "text": "ゴゴゴゴ (轟音)",
                "box_2d": [400, 150, 600, 450],
                "type": "floating",
                "vertical": true
            }
        """.trimIndent())

        val bubble = Bubble.fromJson(json)
        assertEquals(1, bubble.id)
        assertEquals("floating", bubble.type)
        assertEquals(0.15f, bubble.x1, 0.001f)
        assertEquals(0.40f, bubble.y1, 0.001f)
        assertEquals(0.45f, bubble.x2, 0.001f)
        assertEquals(0.60f, bubble.y2, 0.001f)

        val jsonStr = bubble.toJson().toString()
        val parsedBack = Bubble.fromJson(org.json.JSONObject(jsonStr))
        assertEquals("floating", parsedBack.type)
    }

    @Test
    fun testRetryDelayExtractionFromGeminiErrorMessage() {
        val errorMsgSec = """
            You exceeded your current quota, please check your plan and billing details.
            * Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20, model: gemini-2.5-flash
            Please retry in 18.302319871s. (411ms)
        """.trimIndent()

        val regexSec = Regex("""retry in ([0-9]+(?:\.[0-9]+)?)\s*s""", RegexOption.IGNORE_CASE)
        val matchSec = regexSec.find(errorMsgSec)
        assertTrue(matchSec != null)
        val secs = matchSec!!.groupValues[1].toDouble()
        assertEquals(18.302319871, secs, 0.001)

        val errorMsgMs = "Resource exhausted. Please retry in 361.532506ms."
        val regexMs = Regex("""retry in ([0-9]+(?:\.[0-9]+)?)\s*ms""", RegexOption.IGNORE_CASE)
        val matchMs = regexMs.find(errorMsgMs)
        assertTrue(matchMs != null)
        val ms = matchMs!!.groupValues[1].toDouble()
        assertEquals(361.532506, ms, 0.001)
    }
}
