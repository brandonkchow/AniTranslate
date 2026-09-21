package com.example.pipeline.wipe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.example.data.models.Bubble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Erases the source text inside each bubble so translated text can be laid over it.
 *
 * This class is deliberately a thin Android adapter: it converts between `Bitmap` and the plain
 * `IntArray` of ARGB pixels that [BubbleInkMask] and [BubbleInterior] work on. All of the actual
 * decision-making lives in those two pure objects, which is what lets a desktop harness exercise
 * the shipped logic against real pages instead of reimplementing it.
 */
object FlatWiper {

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

        for (bubble in bubbles) {
            if (!bubble.visible) continue

            val leftPx = (bubble.x1 * bmpWidth).toInt().coerceIn(0, bmpWidth - 1)
            val topPx = (bubble.y1 * bmpHeight).toInt().coerceIn(0, bmpHeight - 1)
            val rightPx = (bubble.x2 * bmpWidth).toInt().coerceIn(leftPx + 1, bmpWidth)
            val bottomPx = (bubble.y2 * bmpHeight).toInt().coerceIn(topPx + 1, bmpHeight)

            val boxW = rightPx - leftPx
            val boxH = bottomPx - topPx
            if (boxW <= 2 || boxH <= 2) continue

            val shape = BubbleInterior.shapeFor(boxW.toFloat() / boxH.toFloat(), bubble.type)

            val pixels = IntArray(boxW * boxH)
            resultBitmap.getPixels(pixels, 0, boxW, leftPx, topPx, boxW, boxH)
            val sampledColor = BubbleInkMask.sampleInteriorColor(pixels, boxW, boxH)

            val plan = BubbleInkMask.plan(pixels, boxW, boxH, sampledColor, shape, densityScale)

            if (plan.usedInkMask) {
                // Preferred: repaint only the text, leaving the wall, tail and artwork intact.
                BubbleInkMask.apply(pixels, plan.wipe, sampledColor)
                resultBitmap.setPixels(pixels, 0, boxW, leftPx, topPx, boxW, boxH)
            } else {
                // Nothing readable in there — fall back to wiping a shape.
                paint.color = sampledColor
                drawShapeWipe(
                    canvas = canvas,
                    paint = paint,
                    bubbleType = bubble.type,
                    shape = shape,
                    leftPx = leftPx,
                    topPx = topPx,
                    rightPx = rightPx,
                    bottomPx = bottomPx,
                    wallInset = plan.wallInset,
                    densityScale = densityScale
                )
            }
        }

        resultBitmap
    }

    private fun drawShapeWipe(
        canvas: Canvas,
        paint: Paint,
        bubbleType: String,
        shape: BubbleInterior.Shape,
        leftPx: Int,
        topPx: Int,
        rightPx: Int,
        bottomPx: Int,
        wallInset: Int,
        densityScale: Float
    ) {
        if (bubbleType.lowercase() in setOf("floating", "side_text")) {
            // Text directly over artwork: no wall to protect, so the whole region goes.
            val rectF = RectF(
                leftPx.toFloat(),
                topPx.toFloat(),
                rightPx.toFloat(),
                bottomPx.toFloat()
            )
            val cornerRadius = 6f * densityScale
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
            return
        }

        val rectF = RectF(
            (leftPx + wallInset).toFloat(),
            (topPx + wallInset).toFloat(),
            (rightPx - wallInset).toFloat(),
            (bottomPx - wallInset).toFloat()
        )
        when (shape) {
            // Narration box with a minimal inset.
            BubbleInterior.Shape.RECT -> {
                val cornerRadius = 3f * densityScale
                canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
            }
            // Circular / oval speech bubble, sitting inside its wall.
            BubbleInterior.Shape.ELLIPSE -> canvas.drawOval(rectF, paint)
            // Elongated or panel-shaped region.
            BubbleInterior.Shape.ROUNDED_RECT -> {
                val cornerRadius = min(rectF.width(), rectF.height()) * 0.25f
                canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
            }
        }
    }
}
