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
        val paddingPx = 6f * densityScale

        for (bubble in bubbles) {
            if (!bubble.visible) continue
            val text = bubble.translated.ifBlank { bubble.text }
            if (text.isBlank()) continue

            val leftPx = bubble.x1 * bmpW
            val topPx = bubble.y1 * bmpH
            val rightPx = bubble.x2 * bmpW
            val bottomPx = bubble.y2 * bmpH

            val boxWidth = (rightPx - leftPx).coerceAtLeast(20f)
            val boxHeight = (bottomPx - topPx).coerceAtLeast(20f)

            val availableWidth = (boxWidth - (paddingPx * 2)).coerceAtLeast(10f)
            val availableHeight = (boxHeight - (paddingPx * 2)).coerceAtLeast(10f)

            // Determine optimal text size
            val baseSizePx = bubble.fontSizeSp * densityScale
            val (optimalLayout, optimalPaint) = calculateBestLayout(
                text = text,
                availableWidth = availableWidth.toInt(),
                availableHeight = availableHeight,
                baseSizePx = baseSizePx,
                typeface = typeface,
                maxLines = 4
            )

            // Draw centered
            canvas.save()
            val textLayoutHeight = optimalLayout.height.toFloat()
            val textLayoutWidth = optimalLayout.width.toFloat()
            val drawX = leftPx + (boxWidth - textLayoutWidth) / 2f
            val drawY = topPx + (boxHeight - textLayoutHeight) / 2f

            canvas.translate(drawX, drawY)
            optimalLayout.draw(canvas)
            canvas.restore()
        }

        resultBitmap
    }

    private fun calculateBestLayout(
        text: String,
        availableWidth: Int,
        availableHeight: Float,
        baseSizePx: Float,
        typeface: Typeface,
        maxLines: Int = 4
    ): Pair<StaticLayout, TextPaint> {
        var currentSize = baseSizePx
        val minSize = max(9f, baseSizePx * 0.45f)

        var bestLayout: StaticLayout? = null
        var bestPaint: TextPaint? = null

        while (currentSize >= minSize) {
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = currentSize
                this.typeface = typeface
                textAlign = Paint.Align.LEFT
            }

            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, availableWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 0.95f)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .build()

            bestLayout = layout
            bestPaint = textPaint

            if (layout.height <= availableHeight && layout.lineCount <= maxLines) {
                break
            }
            currentSize -= 1.5f
        }

        return Pair(
            bestLayout ?: StaticLayout.Builder
                .obtain(text, 0, text.length, TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = minSize
                    this.typeface = typeface
                }, availableWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build(),
            bestPaint ?: TextPaint()
        )
    }
}
