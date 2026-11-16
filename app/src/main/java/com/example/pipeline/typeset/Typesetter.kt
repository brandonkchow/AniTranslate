package com.example.pipeline.typeset

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.data.models.Bubble
import com.example.utils.FontHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

object Typesetter {

    /** Test/render-proof hook: receives the one layout decision per unit. */
    internal var onLayoutDecided: ((bubbleId: Int, bubbleType: String, decidedText: String, lines: List<String>, hyphenated: Boolean, fontSizePx: Float, phase: String, lineCount: Int, occupancy: Double) -> Unit)? = null

    /**
     * Occupancy floor (Commander 2026-11-15): measured basis — accepted
     * pages 4/5 bubbles min 0.1688 / p10 0.2459 / median 0.6601, page-6
     * islands 0.1930 / 0.1968. F = 0.20 sits just above the island max
     * and is a TRIGGER only: a whole-word choice
     * below it evaluates the hyphenated candidate, which is adopted only
     * on strict improvement in both font size and occupancy. The adoption
     * test — not the floor — is the separator (a floor-as-verdict is
     * impossible: the islands sit inside the accepted range). Reference
     * thresholds for the hyphenation itself live in WordWrap.kt's header.
     */
    internal const val OCCUPANCY_FLOOR_TRIGGER = 0.20

    suspend fun typesetBubbles(
        context: Context,
        wipedBitmap: Bitmap,
        bubbles: List<Bubble>
    ): Bitmap = withContext(Dispatchers.Default) {
        val resultBitmap = wipedBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)
        val typeface = FontHelper.getComicTypeface(context)

        val bmpW = wipedBitmap.width
        val bmpH = wipedBitmap.height
        val densityScale = max(1.0f, max(bmpW, bmpH) / 1000f)

        for (bubble in bubbles) {
            if (!bubble.visible) continue

            // Out-of-scope types only (side_text). Free text (floating, detector
            // text_free) is typeset inside its own extent box (BACKLOG:13).
            if (bubble.isNonBalloon) continue

            val rawText = bubble.translated.ifBlank { bubble.text }
            if (rawText.isBlank()) continue

            val leftPx = bubble.x1 * bmpW
            val topPx = bubble.y1 * bmpH
            val rightPx = bubble.x2 * bmpW
            val bottomPx = bubble.y2 * bmpH

            val boxWidth = (rightPx - leftPx).coerceAtLeast(24f)
            val boxHeight = (bottomPx - topPx).coerceAtLeast(24f)
            val aspectRatio = boxWidth / boxHeight

            // Detect Shout / SFX emphasis (exclamation marks, scream dashes, uppercase)
            val isShout = rawText.contains("!") || rawText.contains("！") || rawText.contains("――") || rawText.contains("--")
            val text = if (isShout && rawText.length <= 25 && !rawText.all { it.isUpperCase() }) {
                rawText.uppercase()
            } else {
                rawText
            }

            val isFloating = bubble.type.equals("floating", ignoreCase = true)

            // Elliptical safety inset: Speech bubbles are oval, so horizontal padding is wider near corners.
            // A free-text extent is the Japanese block itself (BACKLOG:13): no oval inset,
            // the full box is the write area, and text is fitted inside it.
            val horizontalPadding = if (isFloating) (boxWidth * 0.04f).coerceAtLeast(2f * densityScale)
                else (boxWidth * (if (aspectRatio < 0.7f) 0.12f else 0.15f)).coerceAtLeast(4f * densityScale)
            val verticalPadding = if (isFloating) (boxHeight * 0.04f).coerceAtLeast(2f * densityScale)
                else (boxHeight * 0.10f).coerceAtLeast(4f * densityScale)

            val availableWidth = (boxWidth - (horizontalPadding * 2)).toInt().coerceAtLeast(16)
            val availableHeight = (boxHeight - (verticalPadding * 2)).coerceAtLeast(16f)

            // Dynamic maxLines based on aspect ratio (free text may wrap tighter
            // inside its small extent rather than grow out of it)
            val maxLines = when {
                isFloating && aspectRatio < 0.6f -> 10
                aspectRatio < 0.6f -> 8
                aspectRatio < 0.9f -> 6
                aspectRatio > 1.8f -> 3
                else -> 4
            }

            val baseSizePx = bubble.fontSizeSp * densityScale * (if (isShout) 1.10f else 1.0f)
            val minReadablePx = if (isFloating) (baseSizePx * 0.25f).coerceAtLeast(6f * densityScale)
                else (baseSizePx * 0.40f).coerceAtLeast(9f * densityScale)

            // Grow step (Commander fallback order): the box may expand only
            // inside the bubble interior — 90% of the bubble box for balloons.
            // For free text the interior is the Japanese extent itself, so the
            // grown box is the extent minus a hairline inset, never artwork.
            val grownWidth = if (isFloating) (boxWidth - 4f * densityScale).toInt().coerceAtLeast(availableWidth)
                else (boxWidth * 0.90f).toInt().coerceAtLeast(availableWidth)
            val grownHeight = if (isFloating) (boxHeight - 4f * densityScale).coerceAtLeast(availableHeight)
                else (boxHeight * 0.90f).coerceAtLeast(availableHeight)

            // One word-break decision per unit, in the Commander's order:
            // shrink + re-wrap whole words, then grow inside the interior,
            // then hyphenate at a syllable. Every layout drawn below (fill,
            // stroke) is built from this decision's text.
            val choice = calculateBestLayout(
                text = text,
                availableWidth = availableWidth,
                availableHeight = availableHeight,
                grownWidth = grownWidth,
                grownHeight = grownHeight,
                maxSizePx = baseSizePx * 1.30f,
                minSizePx = minReadablePx,
                typeface = typeface,
                maxLines = maxLines,
                isShout = isShout
            )
            val optimalLayout = choice.layout
            val optimalPaint = choice.paint
            val layoutWidthUsed = choice.widthPx
            onLayoutDecided?.invoke(
                bubble.id, bubble.type, text, choice.lines, choice.hyphenated,
                choice.paint.textSize, choice.phase, choice.lines.size,
                occupancyOf(choice, grownWidth, grownHeight)
            )

            // Dual-Pass Rendering:
            // Pass 1: Crisp white outer stroke halo (guarantees readability on screentones & dark art)
            val strokeWidthPx = (optimalPaint.textSize * 0.16f).coerceIn(2.5f * densityScale, 6.0f * densityScale)
            val strokePaint = TextPaint(optimalPaint).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokeWidthPx
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                color = Color.WHITE
            }

            // Stroke pass is built from the same decided text and width as the
            // fill pass, so it cannot re-wrap (or re-split) a word.
            val strokeLayout = StaticLayout.Builder
                .obtain(choice.text, 0, choice.text.length, strokePaint, layoutWidthUsed)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 0.92f)
                .setIncludePad(false)
                .setMaxLines(choice.lines.size.coerceAtLeast(1))
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()

            // Draw centered within the unit's extent: Stroke first, then solid fill.
            // Render check (BACKLOG:13): flag any layout that could not be fitted
            // inside its extent even at the minimum size, then clip the draw to
            // the extent so no glyph is ever painted outside it.
            if (optimalLayout.height > grownHeight || optimalLayout.width > grownWidth) {
                android.util.Log.w(
                    "Typesetter",
                    "bubble #${bubble.id} type=${bubble.type} does not fit its extent: " +
                        "layout ${optimalLayout.width}x${optimalLayout.height} > available ${grownWidth}x${grownHeight.toInt()} — clipped to extent"
                )
            }
            canvas.save()
            canvas.clipRect(leftPx, topPx, rightPx, bottomPx)
            val textLayoutHeight = optimalLayout.height.toFloat()
            val textLayoutWidth = optimalLayout.width.toFloat()
            val drawX = leftPx + (boxWidth - textLayoutWidth) / 2f
            val drawY = topPx + (boxHeight - textLayoutHeight) / 2f

            canvas.translate(drawX, drawY)
            strokeLayout.draw(canvas)
            optimalLayout.draw(canvas)
            canvas.restore()
        }

        resultBitmap
    }

    /** The one word-break decision for a unit: every drawn layout uses it. */
    internal data class LayoutChoice(
        val layout: StaticLayout,
        val paint: TextPaint,
        val text: String,
        val lines: List<String>,
        val widthPx: Int,
        val hyphenated: Boolean,
        val phase: String
    )

    /**
     * Occupancy = text block / grown-bubble interior, where the text block
     * is (widest drawn StaticLayout line x layout height) and the interior
     * is the grown box (90% of the bubble box for balloons, the extent
     * minus a hairline inset for floating units). Permanent measurement
     * instrument for the occupancy floor.
     */
    internal fun occupancyOf(choice: LayoutChoice, grownWidth: Int, grownHeight: Float): Double {
        val widest = (0 until choice.layout.lineCount).maxOfOrNull { choice.layout.getLineWidth(it) } ?: 0f
        return (widest.toDouble() * choice.layout.height.toDouble()) / (grownWidth.toDouble() * grownHeight.toDouble())
    }

    private fun makePaint(sizePx: Float, typeface: Typeface, isShout: Boolean) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = sizePx
            this.typeface = typeface
            if (isShout) isFakeBoldText = true
            textAlign = Paint.Align.LEFT
        }

    private fun buildLayout(text: String, paint: TextPaint, widthPx: Int, lineCount: Int): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 0.92f)
            .setIncludePad(false)
            .setMaxLines(lineCount.coerceAtLeast(1))
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    internal fun layoutLines(layout: StaticLayout): List<String> =
        (0 until layout.lineCount).map {
            layout.text.subSequence(layout.getLineStart(it), layout.getLineEnd(it)).toString().trim()
        }

    /**
     * Largest size in [minSizePx, maxSizePx] whose whole-word (or, when
     * [hyphenate], syllable-hyphenated) wrap fits [widthPx] x [heightPx].
     * A candidate is rejected when Android's own layout disagrees with the
     * decided lines — that disagreement is exactly a silent mid-word split.
     */
    private fun searchLayout(
        text: String,
        widthPx: Int,
        heightPx: Float,
        maxSizePx: Float,
        minSizePx: Float,
        typeface: Typeface,
        maxLines: Int,
        isShout: Boolean,
        hyphenate: Boolean,
        phase: String
    ): LayoutChoice? {
        fun candidate(sizePx: Float): LayoutChoice? {
            val paint = makePaint(sizePx, typeface, isShout)
            val probe = TextPaint(paint)
            val wrap = WordWrap.wrap(text, widthPx.toFloat(), sizePx, hyphenate) { s, size ->
                probe.textSize = size
                probe.measureText(s)
            } ?: return null
            if (wrap.lines.size > maxLines) return null
            val layout = buildLayout(wrap.text, paint, widthPx, wrap.lines.size)
            if (layout.height > heightPx) return null
            if (layoutLines(layout) != wrap.lines) return null
            if (WordWrap.hasUnhyphenatedMidWordSplit(text, wrap.lines)) return null
            return LayoutChoice(layout, paint, wrap.text, wrap.lines, widthPx, wrap.hyphenated, phase)
        }

        var low = minSizePx
        var high = maxSizePx
        var best: LayoutChoice? = null
        // Binary search for optimal text size with 0.5px precision
        for (i in 0 until 8) {
            val mid = (low + high) / 2f
            val choice = candidate(mid)
            if (choice != null) {
                best = choice
                low = mid + 0.5f // Try larger font
            } else {
                high = mid - 0.5f // Try smaller font
            }
        }
        return best ?: candidate(minSizePx)
    }

    /**
     * Occupancy-floor trigger plus adoption test: when a whole-word
     * choice sits below [OCCUPANCY_FLOOR_TRIGGER], evaluate the hyphenated
     * candidate at the grown box and adopt it only on strict improvement
     * in BOTH font size and occupancy. Anything else keeps the whole-word
     * layout unchanged, so Commander-accepted pages stay byte-identical in
     * their decided lines.
     */
    private fun considerFloorAdoption(
        text: String,
        wholeWord: LayoutChoice,
        grownWidth: Int,
        grownHeight: Float,
        maxSizePx: Float,
        minSizePx: Float,
        typeface: Typeface,
        maxLines: Int,
        isShout: Boolean
    ): LayoutChoice {
        // Hyphenation evaluation is multi-word only (manga-image-translator
        // text_render.py draws split hyphens only when len(words) > 1), so
        // one-word shorts and shouts never reach the adoption test.
        if (text.split(Regex("\\s+")).filter { it.isNotEmpty() }.size <= 1) return wholeWord
        val wholeOcc = occupancyOf(wholeWord, grownWidth, grownHeight)
        if (wholeOcc >= OCCUPANCY_FLOOR_TRIGGER) return wholeWord
        val hyphenated = searchLayout(
            text, grownWidth, grownHeight, maxSizePx, minSizePx,
            typeface, maxLines, isShout, hyphenate = true, phase = "hyphenate"
        ) ?: return wholeWord
        if (hyphenated.hyphenated &&
            hyphenated.paint.textSize > wholeWord.paint.textSize &&
            occupancyOf(hyphenated, grownWidth, grownHeight) > wholeOcc
        ) {
            return hyphenated.copy(phase = "floor-hyphenate")
        }
        return wholeWord
    }

    /**
     * Commander fallback order: (1) shrink the font and re-wrap whole words,
     * (2) grow the box inside the bubble interior / free-text extent,
     * (3) hyphenate at a syllable with a visible hyphen. Last resort keeps
     * the BACKLOG:13 shrink-to-fit floor (4px, more lines) under the same
     * hyphenation rules, so a split without a hyphen is never emitted.
     *
     * Refinement to (1)-(2): a whole-word choice below the occupancy floor
     * trigger is offered to the hyphenated candidate at the grown box, and
     * adopted only when it strictly beats whole-word on both font size and
     * occupancy ([considerFloorAdoption]).
     */
    internal fun calculateBestLayout(
        text: String,
        availableWidth: Int,
        availableHeight: Float,
        grownWidth: Int,
        grownHeight: Float,
        maxSizePx: Float,
        minSizePx: Float,
        typeface: Typeface,
        maxLines: Int,
        isShout: Boolean = false
    ): LayoutChoice {
        searchLayout(text, availableWidth, availableHeight, maxSizePx, minSizePx, typeface, maxLines, isShout, hyphenate = false, phase = "shrink")
            ?.let { return considerFloorAdoption(text, it, grownWidth, grownHeight, maxSizePx, minSizePx, typeface, maxLines, isShout) }
        searchLayout(text, grownWidth, grownHeight, maxSizePx, minSizePx, typeface, maxLines, isShout, hyphenate = false, phase = "grow")
            ?.let { return considerFloorAdoption(text, it, grownWidth, grownHeight, maxSizePx, minSizePx, typeface, maxLines, isShout) }
        searchLayout(text, grownWidth, grownHeight, maxSizePx, minSizePx, typeface, maxLines, isShout, hyphenate = true, phase = "hyphenate")
            ?.let { return it }
        searchLayout(text, grownWidth, grownHeight, minSizePx, 4f, typeface, maxLines * 3, isShout, hyphenate = true, phase = "floor-resort")
            ?.let { return it }

        // Nothing fitted: emit the hyphenated wrap at the floor size anyway
        // (caller logs and clips to the extent) rather than a silent split.
        val paint = makePaint(minSizePx, typeface, isShout)
        val probe = TextPaint(paint)
        val wrap = WordWrap.wrap(text, grownWidth.toFloat(), minSizePx, hyphenate = true) { s, size ->
            probe.textSize = size
            probe.measureText(s)
        }
        val lines = wrap?.lines ?: listOf(text)
        val decidedText = wrap?.text ?: text
        return LayoutChoice(
            buildLayout(decidedText, paint, grownWidth, lines.size),
            paint, decidedText, lines, grownWidth, wrap?.hyphenated ?: false, "floor-resort"
        )
    }
}

