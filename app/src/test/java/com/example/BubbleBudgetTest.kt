package com.example

import com.example.data.models.Bubble
import com.example.pipeline.typeset.BubbleBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Space-aware budget geometry (calibration in BubbleBudget's header):
 * budgets are estimates from the typesetter's own geometry, so the
 * invariants under test are ordering and shape response, not exact
 * character counts.
 */
class BubbleBudgetTest {

    private fun bubble(box: List<Float>, type: String = "bubble") =
        Bubble(id = 1, text = "テスト", box = box, type = type)

    @Test
    fun `hard ceiling exceeds preferred budget`() {
        val b = BubbleBudget.budgetFor(bubble(listOf(0.1f, 0.1f, 0.4f, 0.3f)), 1000, 1500)!!
        assertTrue(b.hardChars > b.preferredChars)
        assertTrue(b.preferredChars >= 1)
    }

    @Test
    fun `bigger bubble holds more`() {
        val small = BubbleBudget.budgetFor(bubble(listOf(0.1f, 0.1f, 0.2f, 0.2f)), 1000, 1500)!!
        val large = BubbleBudget.budgetFor(bubble(listOf(0.1f, 0.1f, 0.5f, 0.4f)), 1000, 1500)!!
        assertTrue(large.preferredChars > small.preferredChars)
        assertTrue(large.hardChars > small.hardChars)
    }

    @Test
    fun `floating minimum is lower so its hard ceiling is higher`() {
        val box = listOf(0.1f, 0.1f, 0.4f, 0.3f)
        val balloon = BubbleBudget.budgetFor(bubble(box, "bubble"), 1000, 1500)!!
        val floating = BubbleBudget.budgetFor(bubble(box, "floating"), 1000, 1500)!!
        assertTrue(floating.hardChars > balloon.hardChars)
    }

    @Test
    fun `narrow tall bubble line cap follows the typesetter table`() {
        val narrow = BubbleBudget.budgetFor(bubble(listOf(0.1f, 0.1f, 0.2f, 0.5f)), 1000, 1500)!!
        val wide = BubbleBudget.budgetFor(bubble(listOf(0.1f, 0.1f, 0.6f, 0.2f)), 1000, 1500)!!
        assertEquals(8, narrow.maxLines)
        assertEquals(3, wide.maxLines)
    }

    @Test
    fun `unknown bitmap size yields no budget rather than a wrong one`() {
        assertNull(BubbleBudget.budgetFor(bubble(listOf(0.1f, 0.1f, 0.4f, 0.3f)), 0, 0))
    }

    @Test
    fun `budgetsFor skips units without geometry`() {
        val map = BubbleBudget.budgetsFor(
            listOf(bubble(listOf(0.1f, 0.1f, 0.4f, 0.3f)), Bubble(id = 2, text = "x", box = listOf(0f, 0f))),
            1000, 1500
        )
        assertEquals(setOf(1), map.keys)
    }
}
