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

            val boxW = rightPx - leftPx
            val boxH = bottomPx - topPx
            if (boxW <= 2 || boxH <= 2) continue

            val sampledColor = sampleBubbleInteriorColor(sourceBitmap, leftPx, topPx, rightPx, bottomPx)

            // Attempt smart text ink-mask wiping first (leaves bubble borders, tails, and screentones intact)
            val wipedViaInkMask = wipeTextInkMask(
                resultBitmap = resultBitmap,
                leftPx = leftPx,
                topPx = topPx,
                rightPx = rightPx,
                bottomPx = bottomPx,
                sampledBgColor = sampledColor,
                densityScale = densityScale,
                bubbleType = bubble.type
            )

            // Fallback to geometric shape wipe only if ink-mask wiping detected insufficient text contrast
            if (!wipedViaInkMask) {
                paint.color = sampledColor
                when (bubble.type.lowercase()) {
                    "narration" -> {
                        // Rectangular narration box with minimal inset
                        val rectF = RectF(
                            leftPx + contourSafetyMargin,
                            topPx + contourSafetyMargin,
                            rightPx - contourSafetyMargin,
                            bottomPx - contourSafetyMargin
                        )
                        val cornerRadius = 3f * densityScale
                        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
                    }
                    "floating", "side_text" -> {
                        // Floating text directly over artwork: Clean tight text region with subtle corner radius
                        val rectF = RectF(
                            leftPx.toFloat(),
                            topPx.toFloat(),
                            rightPx.toFloat(),
                            bottomPx.toFloat()
                        )
                        val cornerRadius = 6f * densityScale
                        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
                    }
                    else -> {
                        // Standard Speech / Thought Bubble: Inset contour-safe oval or rounded rect
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
                }
            }
        }

        resultBitmap
    }

    private fun wipeTextInkMask(
        resultBitmap: Bitmap,
        leftPx: Int,
        topPx: Int,
        rightPx: Int,
        bottomPx: Int,
        sampledBgColor: Int,
        densityScale: Float,
        bubbleType: String
    ): Boolean {
        val width = rightPx - leftPx
        val height = bottomPx - topPx
        val pixels = IntArray(width * height)
        resultBitmap.getPixels(pixels, 0, width, leftPx, topPx, width, height)

        val bgR = Color.red(sampledBgColor)
        val bgG = Color.green(sampledBgColor)
        val bgB = Color.blue(sampledBgColor)
        val bgLum = (0.299 * bgR + 0.587 * bgG + 0.114 * bgB) / 255.0

        val isInverted = bgLum <= 0.40
        val textMask = BooleanArray(width * height)
        var textPixelCount = 0

        // Margin to avoid erasing the bubble's outer stroke contour
        val isFloating = bubbleType.lowercase() in listOf("floating", "side_text")
        val marginX = if (isFloating) 0 else (width * 0.05f).toInt().coerceIn(1, 4)
        val marginY = if (isFloating) 0 else (height * 0.05f).toInt().coerceIn(1, 4)

        for (y in marginY until height - marginY) {
            val rowOffset = y * width
            for (x in marginX until width - marginX) {
                val pixel = pixels[rowOffset + x]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0

                val isInk = if (isInverted) {
                    lum > bgLum + 0.25 || lum > 0.65
                } else {
                    lum < bgLum - 0.20 && lum < 0.55
                }

                if (isInk) {
                    textMask[rowOffset + x] = true
                    textPixelCount++
                }
            }
        }

        // If fewer than 10 text ink pixels detected, fall back to shape wipe
        if (textPixelCount < 10) {
            return false
        }

        // Morphological dilation (radius ~ 1-2px) to absorb anti-aliasing edges and furigana
        val dilationRadius = (1.5f * densityScale).toInt().coerceIn(1, 3)
        val dilatedMask = BooleanArray(width * height)

        for (y in marginY until height - marginY) {
            val rowOffset = y * width
            for (x in marginX until width - marginX) {
                if (textMask[rowOffset + x]) {
                    for (dy in -dilationRadius..dilationRadius) {
                        val ny = y + dy
                        if (ny !in marginY until height - marginY) continue
                        val nRowOffset = ny * width
                        for (dx in -dilationRadius..dilationRadius) {
                            val nx = x + dx
                            if (nx in marginX until width - marginX) {
                                dilatedMask[nRowOffset + nx] = true
                            }
                        }
                    }
                }
            }
        }

        // Inpaint: Fill ONLY dilated ink pixels with sampled interior color
        for (y in marginY until height - marginY) {
            val rowOffset = y * width
            for (x in marginX until width - marginX) {
                val idx = rowOffset + x
                if (dilatedMask[idx]) {
                    pixels[idx] = sampledBgColor
                }
            }
        }

        resultBitmap.setPixels(pixels, 0, width, leftPx, topPx, width, height)
        return true
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

