package com.example.pipeline.typeset

import com.example.data.models.Bubble
import kotlin.math.max

/**
 * Space-aware translation budget (Commander 2026-11-16, design in
 * rapi-work/briefs/20261116_anitranslate-space-aware-design.md).
 *
 * A bubble is a fixed area; the smallest readable font sets a hard limit
 * on how much English it holds. This computes that limit BEFORE
 * translation so the translator can aim at wording that fits, instead of
 * the typesetter repairing blind wording afterwards by shrinking or
 * splitting. Two numbers per bubble: a preferred character count (fits
 * at a comfortable size) and a hard ceiling (fits at the minimum
 * readable size). Both are estimates — character shapes vary — so the
 * typesetter stays the final safety net and the retry loop keys off its
 * measured outcomes, not off these counts alone.
 *
 * Geometry mirrors [Typesetter.typesetBubbles] (grown box, aspect-ratio
 * line caps, minimum readable size) so budget and fit cannot drift
 * apart; the constants below are the calibration, measured 2026-11-16
 * on pages 4/5/6 layout decisions and the 11 ground-truth pages:
 * CHAR_WIDTH_FACTOR is the median width-per-character as a fraction of
 * font size over the 41 real layout decisions (p25 0.444, p75 0.550);
 * PREFERRED_FONT_FRACTION is the median font size of Commander-accepted
 * pages 4/5 as a fraction of the typesetter base size. On accepted
 * pages 4/5 these budgets flag 2/25 units over preferred and 1/25 over
 * hard; on the 11 GT pages' blind translations, 4% over preferred and
 * 2% over hard — prevention for a small class, not a rewrite of the
 * corpus.
 */
object BubbleBudget {

    const val CHAR_WIDTH_FACTOR = 0.481f
    const val PREFERRED_FONT_FRACTION = 0.75f
    private const val LINE_HEIGHT_FACTOR = 1.0f

    data class Budget(
        val preferredChars: Int,
        val hardChars: Int,
        val charsPerLine: Int,
        val maxLines: Int
    )

    fun budgetFor(bubble: Bubble, bitmapWidth: Int, bitmapHeight: Int): Budget? {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || bubble.box.size < 4) return null
        val densityScale = max(1.0f, max(bitmapWidth, bitmapHeight) / 1000f)
        val boxWidth = ((bubble.x2 - bubble.x1) * bitmapWidth).coerceAtLeast(24f)
        val boxHeight = ((bubble.y2 - bubble.y1) * bitmapHeight).coerceAtLeast(24f)
        val aspectRatio = boxWidth / boxHeight
        val isFloating = bubble.type.equals("floating", ignoreCase = true)

        val horizontalPadding = if (isFloating) (boxWidth * 0.04f).coerceAtLeast(2f * densityScale)
            else (boxWidth * (if (aspectRatio < 0.7f) 0.12f else 0.15f)).coerceAtLeast(4f * densityScale)
        val verticalPadding = if (isFloating) (boxHeight * 0.04f).coerceAtLeast(2f * densityScale)
            else (boxHeight * 0.10f).coerceAtLeast(4f * densityScale)
        val availableWidth = (boxWidth - (horizontalPadding * 2)).toInt().coerceAtLeast(16)
        val availableHeight = (boxHeight - (verticalPadding * 2)).coerceAtLeast(16f)
        val grownWidth = if (isFloating) (boxWidth - 4f * densityScale).toInt().coerceAtLeast(availableWidth)
            else (boxWidth * 0.90f).toInt().coerceAtLeast(availableWidth)
        val grownHeight = if (isFloating) (boxHeight - 4f * densityScale).coerceAtLeast(availableHeight)
            else (boxHeight * 0.90f).coerceAtLeast(availableHeight)

        val maxLines = when {
            isFloating && aspectRatio < 0.6f -> 10
            aspectRatio < 0.6f -> 8
            aspectRatio < 0.9f -> 6
            aspectRatio > 1.8f -> 3
            else -> 4
        }

        val baseSizePx = bubble.fontSizeSp * densityScale
        val minReadablePx = if (isFloating) (baseSizePx * 0.25f).coerceAtLeast(6f * densityScale)
            else (baseSizePx * 0.40f).coerceAtLeast(9f * densityScale)
        val preferredPx = baseSizePx * PREFERRED_FONT_FRACTION

        fun capacity(fontPx: Float): Int {
            val charsPerLine = grownWidth / (CHAR_WIDTH_FACTOR * fontPx)
            val linesByHeight = (grownHeight / (LINE_HEIGHT_FACTOR * fontPx)).toInt()
            val lines = linesByHeight.coerceIn(1, maxLines)
            return (charsPerLine * lines).toInt()
        }

        val charsPerLine = (grownWidth / (CHAR_WIDTH_FACTOR * preferredPx)).toInt().coerceAtLeast(1)
        return Budget(
            preferredChars = capacity(preferredPx).coerceAtLeast(1),
            hardChars = capacity(minReadablePx).coerceAtLeast(1),
            charsPerLine = charsPerLine,
            maxLines = maxLines
        )
    }

    fun budgetsFor(bubbles: List<Bubble>, bitmapWidth: Int, bitmapHeight: Int): Map<Int, Budget> =
        bubbles.mapNotNull { b -> budgetFor(b, bitmapWidth, bitmapHeight)?.let { b.id to it } }.toMap()
}
