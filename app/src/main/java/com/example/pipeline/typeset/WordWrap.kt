package com.example.pipeline.typeset

/**
 * Whole-word line breaking for the typesetter (Commander 2026-11-15).
 *
 * What this is: the single place that decides where lines break. The fill
 * layout, the stroke layout and any fallback layout are all built from the
 * lines decided here, so a pass can never re-split a word another pass
 * wrapped whole.
 *
 * Why: [Typesetter] used to accept any StaticLayout whose line count fit,
 * and Android silently breaks a word that is wider than the line mid-word,
 * with no hyphen. Readers got "unbelie/vably", "sor/ry", "cry/ing".
 *
 * Thresholds, adapted from the reference repos on disk (see commit message):
 *  - Whole words first, hyphenation only on overflow — manga-image-translator
 *    rendering/text_render.py calc_horizontal Step 1 (~713: "Arrange words
 *    without hyphenating unless necessary") and BallonsTranslator
 *    ui/text_engine/horizontal_layout.py (~1029,
 *    WrapAtWordBoundaryOrAnywhere: word boundary first, anywhere only as
 *    fallback).
 *  - Hyphenate at syllable boundaries only (text_render.py ~753).
 *  - Small-split floor: no fragment under 2 characters, preferring fragments
 *    at least one font-size wide (text_render.py Step 3, ~838: fragments
 *    narrower than font_size are moved to the neighbouring line).
 *  - Word-length floor: words of 3 characters or fewer are never hyphenated
 *    (text_render.py ~855 draws hyphen characters only for words > 3 chars).
 *  - One deliberate deviation: text_render.py suppresses the hyphen
 *    character when there is only one word or the box is under 1.5 font
 *    sizes wide. Here a split without a visible hyphen is the defect being
 *    fixed, so the hyphen is always drawn when a word is split.
 */
object WordWrap {

    data class Result(val lines: List<String>, val hyphenated: Boolean) {
        val text: String get() = lines.joinToString("\n")
    }

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'y')

    /**
     * Greedy whole-word wrap of [text] into lines at most [maxWidthPx] wide
     * at [fontSizePx], measuring with [measure] (text, sizePx) -> widthPx.
     *
     * Returns null when the text cannot be laid out under the rules: with
     * [hyphenate] false, any single word wider than the line fails the
     * candidate (the caller then shrinks, re-wraps, or grows the box);
     * with [hyphenate] true, a word that cannot be split at a syllable
     * boundary above the small-split floor also fails.
     */
    fun wrap(
        text: String,
        maxWidthPx: Float,
        fontSizePx: Float,
        hyphenate: Boolean,
        measure: (String, Float) -> Float
    ): Result? {
        val lines = mutableListOf<String>()
        var hyphenated = false

        for (paragraph in text.split('\n')) {
            val words = paragraph.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            var current = StringBuilder()

            fun flush() {
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current = StringBuilder()
                }
            }

            for (word in words) {
                val wordWidth = measure(word, fontSizePx)
                if (wordWidth <= maxWidthPx) {
                    val candidate = if (current.isEmpty()) word else "$current $word"
                    if (measure(candidate, fontSizePx) <= maxWidthPx) {
                        if (current.isNotEmpty()) current.append(' ')
                        current.append(word)
                    } else {
                        flush()
                        current.append(word)
                    }
                    continue
                }

                // Single word wider than the line.
                if (!hyphenate) return null
                val chunks = hyphenateWord(word, maxWidthPx, fontSizePx, measure) ?: return null
                hyphenated = true
                for ((index, chunk) in chunks.withIndex()) {
                    val isLast = index == chunks.lastIndex
                    val piece = if (isLast) chunk else "$chunk-"
                    val candidate = if (current.isEmpty()) piece else "$current $piece"
                    if (measure(candidate, fontSizePx) <= maxWidthPx) {
                        if (current.isNotEmpty()) current.append(' ')
                        current.append(piece)
                    } else {
                        flush()
                        current.append(piece)
                    }
                    if (!isLast) flush()
                }
            }
            flush()
        }
        if (lines.isEmpty()) return null
        return Result(lines, hyphenated)
    }

    /**
     * Splits [word] into chunks that each fit [maxWidthPx] (with room for
     * the hyphen on all but the last), at syllable boundaries where
     * possible. Null when no split above the small-split floor exists.
     */
    fun hyphenateWord(
        word: String,
        maxWidthPx: Float,
        fontSizePx: Float,
        measure: (String, Float) -> Float
    ): List<String>? {
        if (word.length <= 3) return null
        val chunks = mutableListOf<String>()
        var rest = word
        while (measure(rest, fontSizePx) > maxWidthPx) {
            val budget = maxWidthPx // hyphen added by caller; reserve its width below
            val points = syllableSplitPoints(rest)
                .filter { it >= 2 && rest.length - it >= 2 }
                .sortedDescending()
            var split = points.firstOrNull { point ->
                measure(rest.substring(0, point) + "-", fontSizePx) <= budget &&
                    // Small-split floor: prefer fragments at least ~1em wide.
                    (measure(rest.substring(0, point), fontSizePx) >= fontSizePx ||
                        measure(rest.substring(point), fontSizePx) <= maxWidthPx)
            }
            if (split == null) {
                // No syllable boundary fits: largest fitting prefix, still
                // above the floor, so the split carries a hyphen and never
                // strands a single character.
                var best = -1
                for (point in 2 until rest.length - 1) {
                    if (measure(rest.substring(0, point) + "-", fontSizePx) <= budget) best = point
                }
                if (best < 0) return null
                split = best
            }
            chunks += rest.substring(0, split)
            rest = rest.substring(split)
            if (rest.length < 2) return null
        }
        if (rest.isEmpty()) return null
        chunks += rest
        return chunks
    }

    /**
     * Character indices inside [word] where a syllable break is allowed.
     * Heuristic English syllabifier: find vowel groups; between two groups,
     * a single consonant starts the next syllable, a longer consonant
     * cluster splits after its first consonant.
     */
    fun syllableSplitPoints(word: String): List<Int> {
        val letters = word.lowercase()
        if (letters.length < 4) return emptyList()
        val groups = mutableListOf<IntRange>()
        var i = 0
        while (i < letters.length) {
            if (letters[i] in VOWELS && letters[i].isLetter()) {
                var j = i
                while (j + 1 < letters.length && letters[j + 1] in VOWELS) j++
                groups += i..j
                i = j + 1
            } else i++
        }
        if (groups.size < 2) return emptyList()
        val points = mutableListOf<Int>()
        for (g in 0 until groups.lastIndex) {
            val clusterStart = groups[g].last + 1
            val clusterEnd = groups[g + 1].first // exclusive
            val cluster = clusterEnd - clusterStart
            val point = if (cluster <= 1) clusterStart else clusterStart + 1
            if (point in 2 until letters.length - 1) points += point
        }
        return points.distinct().sorted()
    }

    /**
     * True when [lines] cut a word from [original] across a line boundary
     * without a hyphen. Reconstruction joins hyphen-terminated lines
     * directly and all other lines with a space; any other split changes
     * the reconstructed text and is flagged.
     */
    fun hasUnhyphenatedMidWordSplit(original: String, lines: List<String>): Boolean {
        if (lines.size < 2) return false
        val expected = original.replace(Regex("\\s+"), " ").trim()
        val out = StringBuilder()
        lines.forEachIndexed { index, raw ->
            val line = raw.trim()
            if (index > 0 && !lines[index - 1].trim().endsWith("-")) out.append(' ')
            // A trailing hyphen is the split marker: drop it and join directly.
            // Hyphens inside a line (noi-can, I-it) are part of the word and stay.
            out.append(if (index < lines.lastIndex && line.endsWith("-")) line.dropLast(1) else line)
        }
        return out.toString() != expected
    }
}
