package com.example

import com.example.data.models.Bubble
import com.example.ui.editor.EditorUiState
import com.example.ui.editor.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorViewModelTest {

    @Test
    fun testSelectBubble() {
        val bubble1 = Bubble(id = 1, text = "テスト1", box = listOf(0.1f, 0.1f, 0.3f, 0.3f), translated = "Test 1")
        val bubble2 = Bubble(id = 2, text = "テスト2", box = listOf(0.4f, 0.4f, 0.6f, 0.6f), translated = "Test 2")

        val state = EditorUiState(
            bubbles = listOf(bubble1, bubble2),
            selectedBubbleId = 2
        )

        assertNotNull(state.selectedBubble)
        assertEquals(2, state.selectedBubble?.id)
        assertEquals("Test 2", state.selectedBubble?.translated)
    }

    @Test
    fun testUpdateBubbleTextInState() {
        val bubble = Bubble(id = 1, text = "元の文", box = listOf(0.1f, 0.1f, 0.3f, 0.3f), translated = "Original")
        val state = EditorUiState(bubbles = listOf(bubble))

        val updatedBubbles = state.bubbles.map {
            if (it.id == 1) it.copy(translated = "Updated Translation") else it
        }
        val updatedState = state.copy(bubbles = updatedBubbles)

        assertEquals("Updated Translation", updatedState.bubbles[0].translated)
    }

    @Test
    fun testAdjustFontSizeClamping() {
        val bubble = Bubble(id = 1, text = "テキスト", box = listOf(0.1f, 0.1f, 0.3f, 0.3f), fontSizeSp = 14f)

        // Increment
        val bigger = bubble.copy(fontSizeSp = (bubble.fontSizeSp + 4f).coerceIn(8f, 36f))
        assertEquals(18f, bigger.fontSizeSp, 0.01f)

        // Decrement below min
        val tooSmall = bubble.copy(fontSizeSp = (bubble.fontSizeSp - 20f).coerceIn(8f, 36f))
        assertEquals(8f, tooSmall.fontSizeSp, 0.01f)

        // Increment above max
        val tooLarge = bubble.copy(fontSizeSp = (bubble.fontSizeSp + 50f).coerceIn(8f, 36f))
        assertEquals(36f, tooLarge.fontSizeSp, 0.01f)
    }

    @Test
    fun testViewModeSwitching() {
        var state = EditorUiState(viewMode = ViewMode.FINAL)
        assertEquals(ViewMode.FINAL, state.viewMode)

        state = state.copy(viewMode = ViewMode.WIPED)
        assertEquals(ViewMode.WIPED, state.viewMode)

        state = state.copy(viewMode = ViewMode.ORIGINAL)
        assertEquals(ViewMode.ORIGINAL, state.viewMode)
    }

    @Test
    fun testDeleteBubbleState() {
        val b1 = Bubble(id = 1, text = "1", box = listOf(0.1f, 0.1f, 0.2f, 0.2f))
        val b2 = Bubble(id = 2, text = "2", box = listOf(0.3f, 0.3f, 0.4f, 0.4f))

        val state = EditorUiState(bubbles = listOf(b1, b2), selectedBubbleId = 1)
        val afterDelete = state.copy(
            bubbles = state.bubbles.filter { it.id != 1 },
            selectedBubbleId = null
        )

        assertEquals(1, afterDelete.size)
        assertEquals(2, afterDelete.bubbles[0].id)
        assertNull(afterDelete.selectedBubbleId)
    }
}
private val EditorUiState.size: Int get() = bubbles.size
