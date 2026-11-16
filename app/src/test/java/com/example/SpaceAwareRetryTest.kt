package com.example

import com.example.data.models.Bubble
import com.example.pipeline.translate.SpaceAwareRetry
import com.example.pipeline.typeset.BubbleBudget
import com.example.pipeline.typeset.Typesetter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Space-aware retry decisions: distress is the typesetter's measured
 * outcome, acceptance requires strictly shorter, rounds are bounded.
 */
class SpaceAwareRetryTest {

    private fun outcome(
        phase: String = "shrink",
        hyphenated: Boolean = false,
        fontSizePx: Float = 20f,
        minReadablePx: Float = 10f,
        clipped: Boolean = false
    ) = Typesetter.LayoutOutcome(1, phase, hyphenated, fontSizePx, minReadablePx, clipped)

    @Test
    fun `healthy layout is not distressed`() {
        assertFalse(SpaceAwareRetry.isDistressed(outcome()))
        assertFalse(SpaceAwareRetry.isDistressed(null))
    }

    @Test
    fun `hyphenation floor clipping and minimum font are distress`() {
        assertTrue(SpaceAwareRetry.isDistressed(outcome(hyphenated = true)))
        assertTrue(SpaceAwareRetry.isDistressed(outcome(phase = "floor-resort")))
        assertTrue(SpaceAwareRetry.isDistressed(outcome(clipped = true)))
        assertTrue(SpaceAwareRetry.isDistressed(outcome(fontSizePx = 10.4f)))
        assertFalse(SpaceAwareRetry.isDistressed(outcome(fontSizePx = 10.6f)))
    }

    @Test
    fun `round two tightens the target`() {
        val budget = BubbleBudget.Budget(preferredChars = 100, hardChars = 140, charsPerLine = 20, maxLines = 5)
        assertEquals(100, SpaceAwareRetry.targetChars(budget, 1))
        assertEquals(85, SpaceAwareRetry.targetChars(budget, 2))
    }

    @Test
    fun `only strictly shorter candidates are accepted`() {
        assertTrue(SpaceAwareRetry.accepts("a longer line", "short"))
        assertFalse(SpaceAwareRetry.accepts("same!", "same!"))
        assertFalse(SpaceAwareRetry.accepts("short", "a longer line"))
        assertFalse(SpaceAwareRetry.accepts("short", ""))
        assertFalse(SpaceAwareRetry.accepts("short", null))
    }

    @Test
    fun `madeWorse compares measured outcomes`() {
        val distressedBefore = outcome(phase = "floor-resort", fontSizePx = 10f)
        val stillDistressedSameFont = outcome(hyphenated = true, fontSizePx = 10f)
        val stillDistressedBiggerFont = outcome(hyphenated = true, fontSizePx = 14f)
        val healthy = outcome()
        assertTrue(SpaceAwareRetry.madeWorse(distressedBefore, stillDistressedSameFont))
        assertFalse(SpaceAwareRetry.madeWorse(distressedBefore, stillDistressedBiggerFont))
        assertFalse(SpaceAwareRetry.madeWorse(distressedBefore, healthy))
        assertTrue(SpaceAwareRetry.madeWorse(healthy, stillDistressedSameFont))
        assertFalse(SpaceAwareRetry.madeWorse(distressedBefore, null))
    }

    @Test
    fun `retry instruction carries target and shed order`() {
        val b = Bubble(id = 7, text = "テスト", translated = "This unbelievably delicious meat", box = listOf(0.1f, 0.1f, 0.3f, 0.3f))
        val s = SpaceAwareRetry.retryInstruction(b, 40, 1)
        assertTrue(s.contains("#7"))
        assertTrue(s.contains("at most 40 characters"))
        assertTrue(s.contains("core meaning last"))
    }

    @Test
    fun `page context lists every bubble`() {
        val bubbles = listOf(
            Bubble(id = 1, text = "あ", translated = "Ah", box = listOf(0.1f, 0.1f, 0.3f, 0.3f)),
            Bubble(id = 2, text = "い", translated = "Oh", box = listOf(0.4f, 0.1f, 0.6f, 0.3f))
        )
        val ctx = SpaceAwareRetry.pageContext(bubbles)
        assertTrue(ctx.contains("#1"))
        assertTrue(ctx.contains("#2"))
    }
}
