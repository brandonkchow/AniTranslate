package com.example

import com.example.data.models.Bubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
            id = 1,
            box = listOf(0.7f, 0.1f, 0.9f, 0.3f), // x1=0.7, y1=0.1 (top right)
            text = "最初",
            translated = "First"
        )
        val bubbleTopLeft = Bubble(
            id = 2,
            box = listOf(0.1f, 0.1f, 0.3f, 0.3f), // x1=0.1, y1=0.1 (top left)
            text = "二番目",
            translated = "Second"
        )
        val bubbleBottomRight = Bubble(
            id = 3,
            box = listOf(0.7f, 0.6f, 0.9f, 0.8f), // x1=0.7, y1=0.6 (bottom right)
            text = "三番目",
            translated = "Third"
        )

        val unsortedList = listOf(bubbleBottomRight, bubbleTopLeft, bubbleTopRight)

        // Comparator: Sort primarily by vertical panel row, then right-to-left
        val sortedList = unsortedList.sortedWith(
            compareBy<Bubble> { (it.y1 * 5).toInt() } // panel grouping
                .thenByDescending { it.x1 } // right to left
        )

        assertEquals(1, sortedList[0].id)
        assertEquals(2, sortedList[1].id)
        assertEquals(3, sortedList[2].id)
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
}
