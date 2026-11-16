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
        val result = WordWrap.wrap("say $word now", 36f, 10f, hyphenate = true, measure = measure)
        assertNotNull(result)
        assertTrue(result!!.hyphenated)
        val hyphenatedLines = result.lines.filter { it.endsWith("-") }
        assertTrue("a hyphenated split must show the hyphen", hyphenatedLines.isNotEmpty())
        assertEquals("say $word now", reconstruct(result.lines).replace("-", ""))
        // Split points must be syllable boundaries chosen by the syllabifier, with 3/3 fragment floor.
        val wordChunks = WordWrap.hyphenateWord(word, 36f, 10f, measure)
        assertNotNull(wordChunks)
        var consumed = 0
        for (fragment in wordChunks!!.dropLast(1)) {
            consumed += fragment.length
            assertTrue(
                "split at $consumed in $word is not a syllable boundary ${WordWrap.syllableSplitPoints(word)}",
                WordWrap.syllableSplitPoints(word).contains(consumed)
            )
            assertTrue("fragment '$fragment' below 3-char floor", fragment.length >= 3)
        }
        val last = wordChunks.last()
        assertTrue("tail fragment '$last' below 3-char floor", last.length >= 3)
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

    @Test
    fun `fragment floor 3 rejects What's class and short-tail syllable splits`() {
        // "What's" has no syllable split and must not fall back to "What-'s".
        assertNull(WordWrap.hyphenateWord("What's", 30f, 10f, measure))
        assertNull(WordWrap.wrap("say What's up", 30f, 10f, hyphenate = true, measure = measure))

        // Words whose only syllable split leaves < 3 chars on either side must return null.
        // "pretty" -> only split at 4 ("pret-ty", 2-char tail); "sorry" -> 3 ("sor-ry", 2-char tail).
        assertNull(WordWrap.hyphenateWord("pretty", 30f, 10f, measure))
        assertNull(WordWrap.hyphenateWord("sorry", 24f, 10f, measure))
        assertNull(WordWrap.hyphenateWord("into", 18f, 10f, measure))
    }

    @Test
    fun `supposed still splits at 5-before 3-after syllable boundary`() {
        val chunks = WordWrap.hyphenateWord("supposed", 36f, 10f, measure)
        assertEquals(listOf("suppo", "sed"), chunks)
    }

    @Test
    fun `single-vowel-group word returns null instead of non-syllable prefix cut`() {
        assertNull(WordWrap.hyphenateWord("strengths", 36f, 10f, measure))
        assertNull(WordWrap.wrap("my strengths are", 36f, 10f, hyphenate = true, measure = measure))
    }

    @Test
    fun `titlecase word mid-paragraph is never hyphenated while first-word and all-caps are exempt`() {
        // Mid-paragraph Titlecase name overflows 36px (6 chars) -> must return null.
        assertNull(WordWrap.wrap("the Godzilla ran", 36f, 10f, hyphenate = true, measure = measure))

        // Paragraph-first Titlecase word is exempt -> may split at legal syllable point (God-zilla).
        val firstWord = WordWrap.wrap("Godzilla ran away", 36f, 10f, hyphenate = true, measure = measure)
        assertNotNull(firstWord)
        assertTrue(firstWord!!.hyphenated)
        assertEquals("God-", firstWord.lines.first())

        // Mid-paragraph ALL-CAPS shout is exempt -> may split at legal syllable point (GOD-ZILLA).
        val allCaps = WordWrap.wrap("the GODZILLA ran", 36f, 10f, hyphenate = true, measure = measure)
        assertNotNull(allCaps)
        assertTrue(allCaps!!.hyphenated)
        assertTrue(allCaps.lines.any { it == "GOD-" })
    }

    @Test
    fun `last word of a paragraph is never hyphenated while mid-paragraph may split`() {
        // Final word of paragraph -> must return null even with legal syllable points.
        assertNull(WordWrap.wrap("I am unbelievably", 36f, 10f, hyphenate = true, measure = measure))
        assertNull(WordWrap.wrap("I am unbelievably\nreally sad", 36f, 10f, hyphenate = true, measure = measure))

        // Same word mid-paragraph -> may split legally.
        val mid = WordWrap.wrap("I am unbelievably sad", 36f, 10f, hyphenate = true, measure = measure)
        assertNotNull(mid)
        assertTrue(mid!!.hyphenated)
    }

    @Test
    fun `multi-split word keeps every fragment at least 3 characters`() {
        val chunks = WordWrap.hyphenateWord("deliciousness", 36f, 10f, measure)
        assertEquals(listOf("deli", "cious", "ness"), chunks)
        assertTrue(chunks!!.all { it.length >= 3 })
    }
}
