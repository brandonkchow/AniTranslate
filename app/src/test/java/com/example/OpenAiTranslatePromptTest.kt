package com.example

import com.example.data.models.Bubble
import com.example.net.OpenAiCompatibleClient
import com.example.pipeline.typeset.BubbleBudget
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Translate prompt shape for space-aware budgets: budgets ride the input
 * items and the budget rule appears in the system prompt only when
 * budgets are present, so non-budget calls keep their exact old shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenAiTranslatePromptTest {

    private val client = OpenAiCompatibleClient()
    private val bubbles = listOf(
        Bubble(id = 1, text = "テスト", box = listOf(0.1f, 0.1f, 0.3f, 0.3f)),
        Bubble(id = 2, text = "です", box = listOf(0.4f, 0.1f, 0.6f, 0.3f))
    )

    @Test
    fun `input carries budgets only for budgeted bubbles`() {
        val budgets = mapOf(1 to BubbleBudget.Budget(50, 80, 12, 4))
        val arr = JSONArray(client.buildTranslateInput(bubbles, budgets))
        assertEquals(2, arr.length())
        val first = arr.getJSONObject(0)
        assertEquals(50, first.getInt("maxChars"))
        assertEquals(80, first.getInt("hardMaxChars"))
        assertFalse(arr.getJSONObject(1).has("maxChars"))
    }

    @Test
    fun `input without budgets is the old shape`() {
        val arr = JSONArray(client.buildTranslateInput(bubbles, emptyMap()))
        val first = arr.getJSONObject(0)
        assertEquals(1, first.getInt("id"))
        assertEquals("テスト", first.getString("text"))
        assertFalse(first.has("maxChars"))
    }

    @Test
    fun `budget rule appears only with budgets`() {
        assertFalse(client.buildTranslateSystemPrompt("", false, "").contains("Bubble Space Budgets"))
        assertTrue(client.buildTranslateSystemPrompt("", true, "").contains("Bubble Space Budgets"))
        assertTrue(client.buildTranslateSystemPrompt("", true, "").contains("hardMaxChars"))
    }

    @Test
    fun `retry instruction is appended when present`() {
        val prompt = client.buildTranslateSystemPrompt("", true, "REWRITE ONE BUBBLE")
        assertTrue(prompt.contains("REWRITE ONE BUBBLE"))
    }
}
