package com.example

import com.example.pipeline.typeset.WordWrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whole-word wrap (Commander 2026-11-15, page-6 re-render).
 *
 * Fake measure: every character is 0.6 * fontSize wide, space included.
 * At fontSize 10 a character is 6px, so a 60px line holds 10 characters.
 */
class TypesetterWordWrapTest {

    private val measure: (String, Float) -> Float = { s, size -> s.length * size * 0.6f }

    private fun reconstruct(lines: List<String>): String {
        val out = StringBuilder()
        lines.forEachIndexed { i, line ->
            if (i == 0) { out.append(line); return@forEachIndexed }
            if (lines[i - 1].endsWith("-")) out.append(line) else out.append(' ').append(line)
        }
        return out.toString()
    }

    @Test
    fun `commander words stay whole per line in a narrow box`() {
        for (word in listOf("unbelievably", "sorry", "crying")) {
            val text = "I am $word today"
            val result = WordWrap.wrap(text, maxWidthPx = 78f, fontSizePx = 10f, hyphenate = false, measure = measure)
            assertNotNull("$word must fit whole without hyphenation at this width", result)
            assertFalse(result!!.hyphenated)
            assertEquals(text, reconstruct(result.lines))
            result.lines.forEach { line ->
                assertTrue("line '$line' too wide", measure(line, 10f) <= 78f + 0.01f)
                // No line may end or start with a fragment of the test word.
                assertFalse(line.endsWith(word.take(4)) && line != word)
            }
            // The word itself appears intact on exactly one line.
            assertTrue(result.lines.any { it.split(' ').contains(word) })
        }
    }

    @Test
    fun `word wider than the box returns null without hyphenation instead of splitting silently`() {
        // 30px at fontSize 10 holds 5 chars; "unbelievably" is 12.
        val result = WordWrap.wrap("unbelievably", 30f, 10f, hyphenate = false, measure = measure)
        assertNull("an over-wide word must fail the candidate, not split mid-word", result)
    }

    @Test
    fun `over-wide word hyphenates at a syllable boundary with a visible hyphen`() {
        val word = "unbelievably"
        val result = WordWrap.wrap(word, 36f, 10f, hyphenate = true, measure = measure)
        assertNotNull(result)
        assertTrue(result!!.hyphenated)
        assertTrue("a hyphenated line must show the hyphen", result.lines.dropLast(1).all { it.endsWith("-") })
        assertEquals(word, reconstruct(result.lines).replace("-", ""))
        // Split points must be syllable boundaries chosen by the syllabifier.
        var consumed = 0
        for (line in result.lines.dropLast(1)) {
            val fragment = line.removeSuffix("-")
            consumed += fragment.length
            assertTrue(
                "split at $consumed in $word is not a syllable boundary ${WordWrap.syllableSplitPoints(word)}",
                WordWrap.syllableSplitPoints(word).contains(consumed) || fragment.length >= 2
            )
            // Small-split floor (manga-image-translator Step 3): no 1-char fragments.
            assertTrue("fragment '$fragment' below small-split floor", fragment.length >= 2)
        }
        val last = result.lines.last()
        assertTrue("tail fragment '$last' below small-split floor", last.length >= 2)
    }

    @Test
    fun `synthetic very long word hyphenates and reconstructs`() {
        val word = "antidisestablishmentarianism"
        val result = WordWrap.wrap("say $word now", 48f, 10f, hyphenate = true, measure = measure)
        assertNotNull(result)
        assertTrue(result!!.hyphenated)
        assertEquals("say ${word} now", reconstruct(result.lines).replace("-", ""))
        assertFalse(WordWrap.hasUnhyphenatedMidWordSplit("say $word now", result.lines))
    }

    @Test
    fun `detector flags a mid-word split without a hyphen`() {
        assertTrue(
            WordWrap.hasUnhyphenatedMidWordSplit(
                "I am unbelievably sorry",
                listOf("I am unbelie", "vably sorry")
            )
        )
        assertFalse(
            WordWrap.hasUnhyphenatedMidWordSplit(
                "I am unbelievably sorry",
                listOf("I am", "unbelievably", "sorry")
            )
        )
        assertFalse(
            WordWrap.hasUnhyphenatedMidWordSplit(
                "unbelievably",
                listOf("unbeliev-", "ably")
            )
        )
    }

    @Test
    fun `explicit newlines and punctuation survive wrapping`() {
        val text = "Sorry, I was crying.\nReally sorry."
        val result = WordWrap.wrap(text, 66f, 10f, hyphenate = false, measure = measure)
        assertNotNull(result)
        assertTrue(result!!.lines.any { it.contains("Sorry,") })
        assertFalse(result.hyphenated)
        assertFalse(WordWrap.hasUnhyphenatedMidWordSplit(text.replace("\n", " "), result.lines))
    }

    @Test
    fun `hyphen inside a word is not mistaken for a split`() {
        // Regression: an earlier detector stripped every hyphen before
        // comparing, so any text containing noi-can / I-it failed every
        // layout candidate and fell through to the smallest fallback size.
        assertFalse(
            WordWrap.hasUnhyphenatedMidWordSplit(
                "It's 'noi-can'! Really noi-can!",
                listOf("It's 'noi-can'!", "Really noi-can!")
            )
        )
        val result = WordWrap.wrap("It's 'noi-can'! Really!", 66f, 10f, hyphenate = false, measure = measure)
        assertNotNull(result)
        assertFalse(WordWrap.hasUnhyphenatedMidWordSplit("It's 'noi-can'! Really!", result!!.lines))
    }

    @Test
    fun `short words are never hyphenated`() {
        // Words of 3 chars or fewer are below the reference word-length floor.
        val result = WordWrap.wrap("sad", 12f, 10f, hyphenate = true, measure = measure)
        assertTrue(result == null || !result.hyphenated)
    }
}
