package com.example.pipeline.detect

import com.example.data.models.Bubble
import com.example.data.models.TextClass
import com.example.data.models.UnitState

/**
 * Classifies detected text regions into [TextClass.SKIP], [TextClass.INFO], or
 * [TextClass.DIALOGUE] using measured geometric and OCR text properties.
 *
 * - [TextClass.SKIP]: non-balloon `side_text`, punctuation-only regions (`...`),
 *   large horizontal stylized titles/logos, and compact horizontal kanji-heavy
 *   background print/signage without digits or speech punctuation.
 * - [TextClass.INFO]: structured horizontal floating info cards, rankings, and
 *   captions (multi-line cards with digits or Latin runs, or explicit ranking labels).
 * - [TextClass.DIALOGUE]: speech bubbles, vertical floating speech/SFX, and any
 *   unclassifiable floating text (fail-closed to current behaviour).
 */
object TextClassifier {

    private val SPEECH_PUNCTUATION = setOf(
        '!', '！', '?', '？', '…', '〜', '～', '「', '」', '『', '』', '♪', '♡', '★'
    )

    fun classify(bubble: Bubble): TextClass {
        if (bubble.type.equals("side_text", ignoreCase = true)) {
            return TextClass.SKIP
        }

        val text = bubble.text.trim()
        if (text.isEmpty() || text.none { it.isLetterOrDigit() }) {
            return TextClass.SKIP
        }

        val w = bubble.width
        val h = bubble.height
        val area = w * h
        val maxLatin = maxLatinRun(text)
        val hasDigit = text.any { it.isDigit() }
        val lines = text.split('\n').count { it.isNotBlank() }.coerceAtLeast(1)

        // Large horizontal stylized title/logo (e.g. magazine cover/title lettering):
        // covers a large fraction of the page horizontally with long Latin runs or
        // display-sized non-conversational text.
        if (!bubble.vertical && area >= 0.12f &&
            (maxLatin >= 4 || (w >= 0.50f && h >= 0.30f && !hasSpeechPunctuation(text)))
        ) {
            return TextClass.SKIP
        }

        // Regions outside speech bubbles ("floating" / text_free)
        if (bubble.type.equals("floating", ignoreCase = true) && !bubble.vertical) {
            // Structured info card / ranking / caption:
            // multi-line with digits or long Latin run, or explicit ranking/number label.
            val isRankingOrCard = (lines >= 2 && (hasDigit || maxLatin >= 4)) ||
                (hasDigit && (maxLatin >= 2 || text.contains('位') || text.contains('#') || text.contains("No.", ignoreCase = true)))
            if (isRankingOrCard) {
                return TextClass.INFO
            }

            // Background print / signage: compact single-line horizontal kanji-heavy
            // label without digits, Latin runs, or speech punctuation.
            val kanjiCount = text.count { isKanji(it) }
            if (lines == 1 && !hasDigit && maxLatin == 0 && !hasSpeechPunctuation(text) && kanjiCount >= 2 && h <= 0.08f) {
                return TextClass.SKIP
            }
        }

        return TextClass.DIALOGUE
    }

    fun classifyAll(bubbles: List<Bubble>): List<Bubble> =
        bubbles.map {
            val textClass = classify(it)
            // Classification runs once at detect. A RESTORED state set by the
            // Commander survives re-classification; anything else mirrors it.
            val unitState = when {
                it.unitState == UnitState.RESTORED -> UnitState.RESTORED
                textClass == TextClass.SKIP -> UnitState.SKIPPED
                else -> UnitState.PROCESSED
            }
            it.copy(textClass = textClass, unitState = unitState)
        }

    private fun hasSpeechPunctuation(text: String): Boolean =
        text.any { it in SPEECH_PUNCTUATION }

    private fun isLatinLetter(ch: Char): Boolean =
        ch in 'A'..'Z' || ch in 'a'..'z' || ch in 'Ａ'..'Ｚ' || ch in 'ａ'..'ｚ'

    private fun isKanji(ch: Char): Boolean =
        ch.code in 0x4E00..0x9FFF

    private fun maxLatinRun(text: String): Int {
        var cur = 0
        var best = 0
        for (ch in text) {
            if (isLatinLetter(ch)) {
                cur++
                if (cur > best) best = cur
            } else {
                cur = 0
            }
        }
        return best
    }
}
