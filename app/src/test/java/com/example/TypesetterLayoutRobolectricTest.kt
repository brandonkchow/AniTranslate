package com.example

import android.graphics.Typeface
import com.example.pipeline.typeset.Typesetter
import com.example.pipeline.typeset.WordWrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
