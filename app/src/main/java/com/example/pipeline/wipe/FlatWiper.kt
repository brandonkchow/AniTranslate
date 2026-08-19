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

    private const val FALLBACK_COLOR = 0xFFFFF8EE.toInt() // Warm manga paper tint (#FFF8EE)

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

        for (bubble in bubbles) {
            if (!bubble.visible) continue

            val leftPx = (bubble.x1 * bmpWidth).toInt().coerceIn(0, bmpWidth - 1)
            val topPx = (bubble.y1 * bmpHeight).toInt().coerceIn(0, bmpHeight - 1)
            val rightPx = (bubble.x2 * bmpWidth).toInt().coerceIn(leftPx + 1, bmpWidth)
            val bottomPx = (bubble.y2 * bmpHeight).toInt().coerceIn(topPx + 1, bmpHeight)

            val sampledColor = sampleOuterRingMedianColor(sourceBitmap, leftPx, topPx, rightPx, bottomPx, ringThicknessPx = 4)
            paint.color = sampledColor

            val rectF = RectF(
                leftPx.toFloat(),
                topPx.toFloat(),
                rightPx.toFloat(),
                bottomPx.toFloat()
            )
            // Draw smooth rounded rect over bubble
            val cornerRadius = min(rectF.width(), rectF.height()) * 0.15f
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
        }

        resultBitmap
    }

    private fun sampleOuterRingMedianColor(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        ringThicknessPx: Int = 4
    ): Int {
        val rList = mutableListOf<Int>()
        val gList = mutableListOf<Int>()
        val bList = mutableListOf<Int>()

        val bmpW = bitmap.width
        val bmpH = bitmap.height

        // Top ring
        val topStart = max(0, top - ringThicknessPx)
        for (y in topStart until top) {
            for (x in max(0, left - ringThicknessPx) until min(bmpW, right + ringThicknessPx)) {
                val pixel = bitmap.getPixel(x, y)
                rList.add(Color.red(pixel))
                gList.add(Color.green(pixel))
                bList.add(Color.blue(pixel))
            }
        }

        // Bottom ring
        val bottomEnd = min(bmpH, bottom + ringThicknessPx)
        for (y in bottom until bottomEnd) {
            for (x in max(0, left - ringThicknessPx) until min(bmpW, right + ringThicknessPx)) {
                val pixel = bitmap.getPixel(x, y)
                rList.add(Color.red(pixel))
                gList.add(Color.green(pixel))
                bList.add(Color.blue(pixel))
            }
        }

        // Left ring
        val leftStart = max(0, left - ringThicknessPx)
        for (x in leftStart until left) {
            for (y in top until bottom) {
                val pixel = bitmap.getPixel(x, y)
                rList.add(Color.red(pixel))
                gList.add(Color.green(pixel))
                bList.add(Color.blue(pixel))
            }
        }

        // Right ring
        val rightEnd = min(bmpW, right + ringThicknessPx)
        for (x in right until rightEnd) {
            for (y in top until bottom) {
                val pixel = bitmap.getPixel(x, y)
                rList.add(Color.red(pixel))
                gList.add(Color.green(pixel))
                bList.add(Color.blue(pixel))
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
