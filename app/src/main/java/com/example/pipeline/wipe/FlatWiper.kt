package com.example.pipeline.wipe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.example.data.models.Bubble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

object FlatWiper {

    private const val FALLBACK_COLOR = 0xFFFFFFFF.toInt() // Clean white fallback

    suspend fun wipeBubbles(
        sourceBitmap: Bitmap,
        bubbles: List<Bubble>
    ): Bitmap = withContext(Dispatchers.Default) {
        val resultBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        val bmpWidth = sourceBitmap.width
        val bmpHeight = sourceBitmap.height
        val densityScale = max(1.0f, max(bmpWidth, bmpHeight) / 1000f)
        val contourSafetyMargin = (2f * densityScale).coerceIn(1f, 5f)

        for (bubble in bubbles) {
            if (!bubble.visible) continue

            val leftPx = (bubble.x1 * bmpWidth).toInt().coerceIn(0, bmpWidth - 1)
            val topPx = (bubble.y1 * bmpHeight).toInt().coerceIn(0, bmpHeight - 1)
            val rightPx = (bubble.x2 * bmpWidth).toInt().coerceIn(leftPx + 1, bmpWidth)
            val bottomPx = (bubble.y2 * bmpHeight).toInt().coerceIn(topPx + 1, bmpHeight)

            val sampledColor = sampleBubbleInteriorColor(sourceBitmap, leftPx, topPx, rightPx, bottomPx)
            paint.color = sampledColor

            // Inset slightly so we do not overwrite the outer contour lines of the comic speech bubble
            val rectF = RectF(
                leftPx + contourSafetyMargin,
                topPx + contourSafetyMargin,
                rightPx - contourSafetyMargin,
                bottomPx - contourSafetyMargin
            )

            val width = rectF.width()
            val height = rectF.height()
            val aspect = width / height

            if (aspect in 0.65f..1.55f) {
                // Circular/oval speech bubble
                canvas.drawOval(rectF, paint)
            } else {
                // Rounded rect for elongated or rectangular panels
                val cornerRadius = min(width, height) * 0.25f
                canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
            }
        }

        resultBitmap
    }

    private fun sampleBubbleInteriorColor(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Int {
        val rList = mutableListOf<Int>()
        val gList = mutableListOf<Int>()
        val bList = mutableListOf<Int>()

        val bmpW = bitmap.width
        val bmpH = bitmap.height

        // Sample pixels inside the bubble near inner boundary, filtering out dark text strokes (luminance < 0.65)
        val insetX = ((right - left) * 0.12f).toInt().coerceAtLeast(2)
        val insetY = ((bottom - top) * 0.12f).toInt().coerceAtLeast(2)

        val samplePoints = listOf(
            Pair(left + insetX, top + insetY),
            Pair(right - insetX, top + insetY),
            Pair(left + insetX, bottom - insetY),
            Pair(right - insetX, bottom - insetY),
            Pair(left + insetX, (top + bottom) / 2),
            Pair(right - insetX, (top + bottom) / 2)
        )

        for ((x, y) in samplePoints) {
            val clampedX = x.coerceIn(0, bmpW - 1)
            val clampedY = y.coerceIn(0, bmpH - 1)
            val pixel = bitmap.getPixel(clampedX, clampedY)
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0

            // Only consider bright background pixels
            if (luminance >= 0.65) {
                rList.add(r)
                gList.add(g)
                bList.add(b)
            }
        }

        if (rList.isEmpty()) {
            return FALLBACK_COLOR
        }

        rList.sort()
        gList.sort()
        bList.sort()

        val medianR = rList[rList.size / 2]
        val medianG = gList[gList.size / 2]
        val medianB = bList[bList.size / 2]

        return Color.rgb(medianR, medianG, medianB)
    }
}

