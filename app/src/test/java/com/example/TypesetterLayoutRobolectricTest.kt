package com.example

import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import com.example.pipeline.typeset.Typesetter
import com.example.pipeline.typeset.WordWrap
import com.example.utils.FontHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Integration lock: the real Android StaticLayout path used by
 * [Typesetter.typesetBubbles] must hold the whole-word decision at every
 * font size the search can pick (Commander 2026-11-15).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TypesetterLayoutRobolectricTest {

    @Test
    fun `commander words never split mid-word without a hyphen at any picked size`() {
        for (word in listOf("unbelievably", "sorry", "crying")) {
            val text = "I am $word today, really $word"
            for (maxSize in listOf(18f, 26f, 36f, 48f)) {
                val choice = Typesetter.calculateBestLayout(
                    text = text,
                    availableWidth = 90,
                    availableHeight = 200f,
                    grownWidth = 110,
                    grownHeight = 220f,
                    maxSizePx = maxSize,
                    minSizePx = 9f,
                    typeface = Typeface.DEFAULT,
                    maxLines = 8
                )
                val drawn = Typesetter.layoutLines(choice.layout)
                assertEquals("drawn layout must equal the decided lines", choice.lines, drawn)
                assertFalse(
                    "mid-word split without hyphen for $word at maxSize $maxSize: $drawn",
                    WordWrap.hasUnhyphenatedMidWordSplit(text, drawn)
                )
            }
        }
    }

    /** The exact typeface the render path draws with (FontHelper fallback). */
    private fun comicTypeface(): Typeface =
        FontHelper.getComicTypeface(ApplicationProvider.getApplicationContext())

    @Test
    fun `island holds whole-word shrink instead of floor-hyphenating`() {
        // page-6 u7 "Deliciousness alert issued!!" in its real box
        // proportions (800x1184 page, box [0.5561,0.2562,0.7341,0.4898]).
        // Under professional lettering practice, whole-word shrink is kept
        // (phase "shrink", hyphenated == false) rather than adopting a
        // hyphenated layout to chase occupancy.
        val text = "Deliciousness alert issued!!"
        val choice = Typesetter.calculateBestLayout(
            text = text,
            availableWidth = 108,
            availableHeight = 221.26592f,
            grownWidth = 128,
            grownHeight = 248.92416f,
            maxSizePx = 23.70368f,
            minSizePx = 10.656f,
            typeface = comicTypeface(),
            maxLines = 8,
            isShout = true
        )
        assertTrue(
            "test premise: whole-word choice sits below 0.20 occupancy",
            Typesetter.occupancyOf(choice, 128, 248.92416f) < 0.20
        )
        assertEquals("shrink", choice.phase)
        assertFalse("island must stay whole-word: ${choice.lines}", choice.hyphenated)
        assertFalse(WordWrap.hasUnhyphenatedMidWordSplit(text, choice.lines))
        assertEquals(listOf("Deliciousness", "alert issued!!"), choice.lines)
    }

    @Test
    fun `accepted short p4u2 keeps its decided line`() {
        // Invariant: "...M...Mom?" (1280x1804 page, box
        // [0.8139,0.1758,0.8932,0.257]) sits below the floor at 0.1688 but
        // is a single word, so the trigger must not change its lines.
        val text = "...M...Mom?"
        val choice = Typesetter.calculateBestLayout(
            text = text,
            availableWidth = 77,
            availableHeight = 117.18784f,
            grownWidth = 91,
            grownHeight = 131.83632f,
            maxSizePx = 32.8328f,
            minSizePx = 16.236f,
            typeface = comicTypeface(),
            maxLines = 6,
            isShout = false
        )
        assertTrue(
            "test premise: p4u2 must sit below the floor",
            Typesetter.occupancyOf(choice, 91, 131.83632f) < 0.20
        )
        assertEquals(listOf("...M...Mom?"), choice.lines)
        assertFalse(choice.hyphenated)
        assertEquals("shrink", choice.phase)
    }

    @Test
    fun `accepted short p5u3 keeps its decided line`() {
        // Invariant: "...B-but..." (1280x1804 page, box
        // [0.1674,0.0688,0.2357,0.1469]) sits below the floor at 0.1849 but
        // is a single word, so the trigger must not change its lines.
        val text = "...B-but..."
        val choice = Typesetter.calculateBestLayout(
            text = text,
            availableWidth = 66,
            availableHeight = 112.71392f,
            grownWidth = 78,
            grownHeight = 126.80316f,
            maxSizePx = 32.8328f,
            minSizePx = 16.236f,
            typeface = comicTypeface(),
            maxLines = 6,
            isShout = false
        )
        assertTrue(
            "test premise: p5u3 must sit below the floor",
            Typesetter.occupancyOf(choice, 78, 126.80316f) < 0.20
        )
        assertEquals(listOf("...B-but..."), choice.lines)
        assertFalse(choice.hyphenated)
        assertEquals("shrink", choice.phase)
    }

    @Test
    fun `one-word shout never hyphenates`() {
        // Invariant: page-6 u10 "WHAT?!" (800x1184 page, box
        // [0.2058,0.5773,0.2902,0.7148]) sits below the floor at 0.1099 but
        // is one word, so it keeps its single line unhyphenated.
        val choice = Typesetter.calculateBestLayout(
            text = "WHAT?!",
            availableWidth = 51,
            availableHeight = 130.24f,
            grownWidth = 60,
            grownHeight = 146.52f,
            maxSizePx = 23.70368f,
            minSizePx = 10.656f,
            typeface = comicTypeface(),
            maxLines = 8,
            isShout = true
        )
        assertTrue(
            "test premise: WHAT?! must sit below the floor",
            Typesetter.occupancyOf(choice, 60, 146.52f) < 0.20
        )
        assertEquals(listOf("WHAT?!"), choice.lines)
        assertFalse(choice.hyphenated)
    }

    @Test
    fun `synthetic over-wide word is hyphenated with a visible hyphen in the drawn layout`() {
        val text = "antidisestablishmentarianism"
        val choice = Typesetter.calculateBestLayout(
            text = text,
            availableWidth = 60,
            availableHeight = 300f,
            grownWidth = 70,
            grownHeight = 320f,
            maxSizePx = 30f,
            minSizePx = 12f,
            typeface = Typeface.DEFAULT,
            maxLines = 10
        )
        val drawn = Typesetter.layoutLines(choice.layout)
        assertFalse(WordWrap.hasUnhyphenatedMidWordSplit(text, drawn))
        if (drawn.size > 1) {
            // Split happened: every break except the last line must show it.
            drawn.dropLast(1).forEach { line ->
                assert(line.endsWith("-")) { "split line without hyphen: '$line' in $drawn" }
            }
        }
    }
}
