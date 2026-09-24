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

            // Non-balloon text is left untranslated: the wiper deliberately keeps the source text
            // (there is no uniform background to restore it against), and painting English over
            // kept Japanese effects is worse than no translation at all. A wall-less region the
            // merger reclassified into a measured enclosure arrives here as "bubble".
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

            // Elliptical safety inset: Speech bubbles are oval, so horizontal padding is wider near corners
            val horizontalPadding = (boxWidth * (if (aspectRatio < 0.7f) 0.12f else 0.15f)).coerceAtLeast(4f * densityScale)
            val verticalPadding = (boxHeight * 0.10f).coerceAtLeast(4f * densityScale)

            val availableWidth = (boxWidth - (horizontalPadding * 2)).toInt().coerceAtLeast(16)
            val availableHeight = (boxHeight - (verticalPadding * 2)).coerceAtLeast(16f)

            // Dynamic maxLines based on aspect ratio
            val maxLines = when {
                aspectRatio < 0.6f -> 8
                aspectRatio < 0.9f -> 6
                aspectRatio > 1.8f -> 3
                else -> 4
            }

            val baseSizePx = bubble.fontSizeSp * densityScale * (if (isShout) 1.10f else 1.0f)
            val (optimalLayout, optimalPaint) = calculateBestLayoutBinarySearch(
                text = text,
                availableWidth = availableWidth,
                availableHeight = availableHeight,
                maxSizePx = baseSizePx * 1.30f,
                minSizePx = (baseSizePx * 0.40f).coerceAtLeast(9f * densityScale),
                typeface = typeface,
                maxLines = maxLines,
                isShout = isShout
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

            val strokeLayout = StaticLayout.Builder
                .obtain(text, 0, text.length, strokePaint, availableWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 0.92f)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .build()

            // Draw centered within the bubble: Stroke first, then solid fill
            canvas.save()
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

    private fun calculateBestLayoutBinarySearch(
        text: String,
        availableWidth: Int,
        availableHeight: Float,
        maxSizePx: Float,
        minSizePx: Float,
        typeface: Typeface,
        maxLines: Int,
        isShout: Boolean = false
    ): Pair<StaticLayout, TextPaint> {
        var low = minSizePx
        var high = maxSizePx
        var bestSize = minSizePx
        var bestLayout: StaticLayout? = null
        var bestPaint: TextPaint? = null

        // Binary search for optimal text size with 0.5px precision
        for (i in 0 until 8) {
            val mid = (low + high) / 2f
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = mid
                this.typeface = typeface
                if (isShout) {
                    isFakeBoldText = true
                }
                textAlign = Paint.Align.LEFT
            }

            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, availableWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 0.92f)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .build()

            if (layout.height <= availableHeight && layout.lineCount <= maxLines) {
                bestSize = mid
                bestLayout = layout
                bestPaint = textPaint
                low = mid + 0.5f // Try larger font
            } else {
                high = mid - 0.5f // Try smaller font
            }
        }

        if (bestLayout == null) {
            val fallbackPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = minSizePx
                this.typeface = typeface
                if (isShout) {
                    isFakeBoldText = true
                }
            }
            bestLayout = StaticLayout.Builder
                .obtain(text, 0, text.length, fallbackPaint, availableWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 0.90f)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .build()
            bestPaint = fallbackPaint
        }

        return Pair(bestLayout, bestPaint ?: TextPaint())
    }
}

