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
     * The layout outcome of one [typesetBubbles] call, per bubble id,
     * returned with the bitmap (never stored on this singleton: pages
     * typeset concurrently, and a shared outcome map would leak one
     * page's distress into another page's retry decisions, bubble ids
     * being 1..N on every page). The space-aware retry loop
     * (SpaceAwareRetry) reads it: distress is a measured typeset
     * result — below preferred size, floor phases, hyphenation, font
     * at the readable minimum, clipping — not a character-count guess.
     */
    data class LayoutOutcome(
        val bubbleId: Int,
        val phase: String,
        val hyphenated: Boolean,
        val fontSizePx: Float,
        val preferredSizePx: Float,
        val minReadablePx: Float,
        val clipped: Boolean
    )

    data class TypesetResult(
        val bitmap: Bitmap,
        val outcomes: Map<Int, LayoutOutcome>
    )

    /**
     * Deliberate shout detection: a balloon (not floating/non-balloon) is a shout
     * when it carries shout punctuation (`!`, `！`, `――`, `--`) in either the
     * English or Japanese text AND is a compact utterance (`<= 25` chars).
     * Long multi-clause sentences with `!` (e.g. 30–67 char conversational lines)
     * remain normal speech so they are neither uppercased nor forced onto the
     * shout size floor.
     */
    internal fun isShoutBubble(bubble: Bubble, rawText: String): Boolean {
        if (bubble.isNonBalloon || bubble.type.equals("floating", ignoreCase = true)) return false
        if (rawText.length > 25) return false
        return hasShoutPunctuation(rawText) || hasShoutPunctuation(bubble.text)
    }

    private fun hasShoutPunctuation(s: String): Boolean =
        s.contains("!") || s.contains("！") || s.contains("――") || s.contains("--")

    private fun medianOf(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    private data class PreparedBubble(
        val bubble: Bubble,
        val leftPx: Float,
        val topPx: Float,
        val rightPx: Float,
        val bottomPx: Float,
        val boxWidth: Float,
        val boxHeight: Float,
        val aspectRatio: Float,
        val isFloating: Boolean,
        val isShout: Boolean,
        val text: String,
        val availableWidth: Int,
        val availableHeight: Float,
        val grownWidth: Int,
        val grownHeight: Float,
        val maxLines: Int,
        val baseSizePx: Float,
        val preferredSizePx: Float,
        val minReadablePx: Float,
        var choice: LayoutChoice? = null
    )

    suspend fun typesetBubbles(
        context: Context,
        wipedBitmap: Bitmap,
        bubbles: List<Bubble>
    ): TypesetResult = withContext(Dispatchers.Default) {
        val resultBitmap = wipedBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)
        val typeface = FontHelper.getComicTypeface(context)
        val outcomes = mutableMapOf<Int, LayoutOutcome>()

        val bmpW = wipedBitmap.width
        val bmpH = wipedBitmap.height
        val densityScale = max(1.0f, max(bmpW, bmpH) / 1000f)

        val prepared = mutableListOf<PreparedBubble>()
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

            val isShout = isShoutBubble(bubble, rawText)
            val text = if (isShout && !rawText.all { it.isUpperCase() }) {
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
            val preferredSizePx = baseSizePx * BubbleBudget.PREFERRED_FONT_FRACTION
            val minReadablePx = if (isFloating) (baseSizePx * 0.25f).coerceAtLeast(6f * densityScale)
                else (baseSizePx * 0.40f).coerceAtLeast(9f * densityScale)

            // Interior clip/occupancy extent: 90% of the bubble box for balloons,
            // or the extent minus a hairline inset for free text.
            val grownWidth = if (isFloating) (boxWidth - 4f * densityScale).toInt().coerceAtLeast(availableWidth)
                else (boxWidth * 0.90f).toInt().coerceAtLeast(availableWidth)
            val grownHeight = if (isFloating) (boxHeight - 4f * densityScale).coerceAtLeast(availableHeight)
                else (boxHeight * 0.90f).coerceAtLeast(availableHeight)

            prepared += PreparedBubble(
                bubble = bubble,
                leftPx = leftPx,
                topPx = topPx,
                rightPx = rightPx,
                bottomPx = bottomPx,
                boxWidth = boxWidth,
                boxHeight = boxHeight,
                aspectRatio = aspectRatio,
                isFloating = isFloating,
                isShout = isShout,
                text = text,
                availableWidth = availableWidth,
                availableHeight = availableHeight,
                grownWidth = grownWidth,
                grownHeight = grownHeight,
                maxLines = maxLines,
                baseSizePx = baseSizePx,
                preferredSizePx = preferredSizePx,
                minReadablePx = minReadablePx
            )
        }

        // Pass 1: Lay out all non-shout units to measure the page's median English speech size.
        val calmSpeechSizes = mutableListOf<Float>()
        for (p in prepared) {
            if (p.isShout) continue
            val choice = calculateBestLayout(
                text = p.text,
                availableWidth = p.availableWidth,
                availableHeight = p.availableHeight,
                grownWidth = p.grownWidth,
                grownHeight = p.grownHeight,
                maxSizePx = p.baseSizePx * 1.30f,
                minSizePx = p.minReadablePx,
                typeface = typeface,
                maxLines = p.maxLines,
                isShout = false
            )
            p.choice = choice
            if (!p.isFloating) {
                calmSpeechSizes += choice.paint.textSize
            }
        }
        val pageMedianSpeechPx = medianOf(calmSpeechSizes)

        // Pass 2: Lay out shout bubbles with a size floor at the page's median English speech size.
        for (p in prepared) {
            if (!p.isShout) continue
            val shoutFloorPx = max(p.minReadablePx, pageMedianSpeechPx)
            val shoutMaxPx = max(p.baseSizePx * 1.30f, pageMedianSpeechPx * 1.25f)
            p.choice = calculateBestLayout(
                text = p.text,
                availableWidth = p.availableWidth,
                availableHeight = p.availableHeight,
                grownWidth = p.grownWidth,
                grownHeight = p.grownHeight,
                maxSizePx = shoutMaxPx,
                minSizePx = shoutFloorPx,
                typeface = typeface,
                maxLines = p.maxLines,
                isShout = true,
                narrowBubble = p.aspectRatio < 0.7f,
                fallbackMinSizePx = p.minReadablePx
            )
        }

        for (p in prepared) {
            val bubble = p.bubble
            val choice = p.choice ?: continue
            val optimalLayout = choice.layout
            val optimalPaint = choice.paint
            val layoutWidthUsed = choice.widthPx
            onLayoutDecided?.invoke(
                bubble.id, bubble.type, p.text, choice.lines, choice.hyphenated,
                choice.paint.textSize, choice.phase, choice.lines.size,
                occupancyOf(choice, p.grownWidth, p.grownHeight)
            )
            outcomes[bubble.id] = LayoutOutcome(
                bubbleId = bubble.id,
                phase = choice.phase,
                hyphenated = choice.hyphenated,
                fontSizePx = choice.paint.textSize,
                preferredSizePx = p.preferredSizePx,
                minReadablePx = p.minReadablePx,
                clipped = optimalLayout.height > p.grownHeight || optimalLayout.width > p.grownWidth
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
            if (optimalLayout.height > p.grownHeight || optimalLayout.width > p.grownWidth) {
                android.util.Log.w(
                    "Typesetter",
                    "bubble #${bubble.id} type=${bubble.type} does not fit its extent: " +
                        "layout ${optimalLayout.width}x${optimalLayout.height} > available ${p.grownWidth}x${p.grownHeight.toInt()} — clipped to extent"
                )
            }
            canvas.save()
            canvas.clipRect(p.leftPx, p.topPx, p.rightPx, p.bottomPx)
            val textLayoutHeight = optimalLayout.height.toFloat()
            val textLayoutWidth = optimalLayout.width.toFloat()
            val drawX = p.leftPx + (p.boxWidth - textLayoutWidth) / 2f
            val drawY = p.topPx + (p.boxHeight - textLayoutHeight) / 2f

            canvas.translate(drawX, drawY)
            strokeLayout.draw(canvas)
            optimalLayout.draw(canvas)
            canvas.restore()
        }

        // SKIP units are leave-as-drawn through every stage (prompt
        // 20261118, Part 1): a processed unit whose box overlaps a SKIP
        // unit must not paint over it. Restore SKIP pixels from the wiped
        // input, which already carries them bit-identical to the original.
        if (bubbles.any { it.textClass == com.example.data.models.TextClass.SKIP }) {
            val srcPx = IntArray(bmpW * bmpH).also { wipedBitmap.getPixels(it, 0, bmpW, 0, 0, bmpW, bmpH) }
            val outPx = IntArray(bmpW * bmpH).also { resultBitmap.getPixels(it, 0, bmpW, 0, 0, bmpW, bmpH) }
            com.example.pipeline.wipe.WipeGate.restoreSkipPixels(srcPx, outPx, bmpW, bmpH, bubbles)
            resultBitmap.setPixels(outPx, 0, bmpW, 0, 0, bmpW, bmpH)
        }

        TypesetResult(resultBitmap, outcomes)
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
        phase: String,
        stackWords: Boolean = false
    ): LayoutChoice? {
        val targetText = if (stackWords) {
            val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.size in 2..maxLines) words.joinToString("\n") else text
        } else {
            text
        }
        fun candidate(sizePx: Float): LayoutChoice? {
            val paint = makePaint(sizePx, typeface, isShout)
            val probe = TextPaint(paint)
            val wrap = WordWrap.wrap(targetText, widthPx.toFloat(), sizePx, hyphenate) { s, size ->
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
     * Professional lettering order: (1) whole-word search in the available
     * box ("shrink"), with fit restored by rewording ([SpaceAwareRetry])
     * before hyphenation when size drops below preferred; for narrow shout
     * bubbles, stack one word per line to spend abundant height and, if the
     * oval inset still pinches below [minSizePx] (the page median floor),
     * widen to the 90% safe interior ([grownWidth] x [grownHeight], phase
     * "grow") before
     * relaxing the floor or hyphenating; (2) hyphenate at a syllable
     * ("hyphenate") as a last resort; (3) "floor-resort" (4px, more lines)
     * and nothing-fitted fallback under the same hyphenation rules.
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
        isShout: Boolean = false,
        narrowBubble: Boolean = false,
        fallbackMinSizePx: Float = minSizePx
    ): LayoutChoice {
        if (isShout && narrowBubble) {
            searchLayout(text, availableWidth, availableHeight, maxSizePx, minSizePx, typeface, maxLines, isShout = true, hyphenate = false, phase = "shrink", stackWords = true)
                ?.let { return it }
            searchLayout(text, grownWidth, grownHeight, maxSizePx, minSizePx, typeface, maxLines, isShout = true, hyphenate = false, phase = "grow", stackWords = true)
                ?.let { return it }
            searchLayout(text, grownWidth, grownHeight, maxSizePx, minSizePx, typeface, maxLines, isShout = true, hyphenate = false, phase = "grow", stackWords = false)
                ?.let { return it }
            searchLayout(text, grownWidth, grownHeight, minSizePx, fallbackMinSizePx, typeface, maxLines, isShout = true, hyphenate = false, phase = "grow", stackWords = true)
                ?.let { return it }
        }
        searchLayout(text, availableWidth, availableHeight, maxSizePx, minSizePx, typeface, maxLines, isShout, hyphenate = false, phase = "shrink")
            ?.let { return it }
        if (fallbackMinSizePx < minSizePx) {
            searchLayout(text, availableWidth, availableHeight, minSizePx, fallbackMinSizePx, typeface, maxLines, isShout, hyphenate = false, phase = "shrink")
                ?.let { return it }
        }
        val effectiveMin = min(minSizePx, fallbackMinSizePx)
        searchLayout(text, availableWidth, availableHeight, maxSizePx, effectiveMin, typeface, maxLines, isShout, hyphenate = true, phase = "hyphenate")
            ?.let { return it }
        searchLayout(text, grownWidth, grownHeight, effectiveMin, 4f, typeface, maxLines * 3, isShout, hyphenate = true, phase = "floor-resort")
            ?.let { return it }

        // Nothing fitted: emit the hyphenated wrap at the floor size anyway
        // (caller logs and clips to the extent) rather than a silent split.
        val paint = makePaint(effectiveMin, typeface, isShout)
        val probe = TextPaint(paint)
        val wrap = WordWrap.wrap(text, grownWidth.toFloat(), effectiveMin, hyphenate = true) { s, size ->
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

