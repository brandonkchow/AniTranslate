package com.example.pipeline.detect

import com.example.data.models.Bubble
import com.example.data.models.TextClass
import com.example.data.models.UnitState

/**
 * Classifies detected text regions into [TextClass.SKIP], [TextClass.INFO], or
 * [TextClass.DIALOGUE]. Priority order: early structural/display rules (`side_text`,
 * empty/punctuation-only, large horizontal title/logo, floating [TextClass.INFO] cards,
 * and compact horizontal kanji background print) run first; for remaining floating text,
 * the statement guard runs before SFX detection so statement-shaped text always stays
 * [TextClass.DIALOGUE], followed by the positive-evidence horizontal-only SFX rule
 * (requiring an onomatopoeia marker, [TextClass.SKIP]), with all other regions failing
 * closed to [TextClass.DIALOGUE].
 */
object TextClassifier {

    private val SPEECH_PUNCTUATION = setOf(
        '!', '！', '?', '？', '…', '〜', '～', '「', '」', '『', '』', '♪', '♡', '★'
    )

    private val CLAUSE_MARKERS = listOf(
        "いたのだ", "だった", "です", "ます", "は", "が", "を", "に", "へ", "の", "だ"
    )

    private val SFX_MARKERS = setOf(
        'ー', '―', '…',
        'ッ', 'ァ', 'ィ', 'ゥ', 'ェ', 'ォ',
        'っ', 'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ'
    )
    private val SFX_STRIP_CHARS = setOf(
        'ー', '―', '…', '・', '！', '!', '？', '?',
        'ャ', 'ュ', 'ョ', 'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', 'ッ',
        'ゃ', 'ゅ', 'ょ', 'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'っ'
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
        if (bubble.type.equals("floating", ignoreCase = true)) {
            if (!bubble.vertical) {
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

            // Statement guard: statement-shaped floating text must never be SFX-SKIP.
            if (isStatementShaped(text)) {
                return TextClass.DIALOGUE
            }

            // Positive-evidence SFX rule (floating, horizontal only).
            if (!bubble.vertical && lines == 1 && isPositiveSfx(text)) {
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

    private fun isStatementShaped(text: String): Boolean {
        if (text.any { isKanji(it) }) return true
        val letterCount = text.count { it.isLetter() }
        if (text.contains('。')) return true
        if ((text.contains("――") || text.contains('…') || text.contains('―')) && letterCount >= 6) return true
        if (letterCount >= 8) return true
        if (CLAUSE_MARKERS.any { text.contains(it) }) return true
        return false
    }

    /**
     * Positive-evidence SFX detector for single-line horizontal floating text.
     *
     * Requires all non-strip characters to be katakana (`0x30A1..0x30FA` or `'ー'`)
     * with at least one repetition or SFX marker.
     * Accepted residual: pure-hiragana SFX (e.g. `"ず―――ん"`) falls through to
     * [TextClass.DIALOGUE] (translated, the safe direction).
     */
    private fun isPositiveSfx(text: String): Boolean {
        if (text.length > 12) return false

        val stripped = text.filterNot { it in SFX_STRIP_CHARS }
        if (stripped.isEmpty()) return false
        if (!stripped.all { isKatakana(it) }) return false

        var hasRepeatedKana = false
        val collapsed = buildString {
            var prev: Char? = null
            for (ch in stripped) {
                if (ch == prev) {
                    hasRepeatedKana = true
                } else {
                    append(ch)
                    prev = ch
                }
            }
        }
        val hasMarker = hasRepeatedKana || text.any { it in SFX_MARKERS }
        return hasMarker && collapsed.length in 1..6
    }

    private fun isKatakana(ch: Char): Boolean =
        ch.code in 0x30A1..0x30FA || ch == 'ー'

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
