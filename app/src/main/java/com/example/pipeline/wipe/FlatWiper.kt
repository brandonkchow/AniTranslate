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

    /** Bubble types whose text sits directly on artwork: there is no wall to find or protect. */
    private val WALL_LESS_TYPES = setOf("floating", "side_text")

    private fun hasWall(bubbleType: String) = bubbleType.lowercase() !in WALL_LESS_TYPES

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

        // One full-page enclosure measurement per page, taken from the *source* so the glyphs it
        // reads are the ones the detector saw. The map is the authority on where each bubble's
        // interior really is — including lobes of a merged balloon that lie outside the detector's
        // rectangle — and is what lets the plan below wipe past a tight box without ever crossing
        // a wall. Measured once here rather than per bubble: the flood is page-wide by design,
        // because a bubble's extent is a property of the page, not of any box.
        val pagePixels = IntArray(bmpWidth * bmpHeight)
        sourceBitmap.getPixels(pagePixels, 0, bmpWidth, 0, 0, bmpWidth, bmpHeight)
        val pageLuminance = FloatArray(pagePixels.size) { BubbleInterior.luminance(pagePixels[it]) }
        val enclosureMap = EnclosureMap.build(pageLuminance, bmpWidth, bmpHeight)

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

            // Sample the fill colour from the box the detector drew, and measure the fallback inset
            // there too: widening the region below would move these sample points out onto the
            // artwork, and would change what the inset measurement walks past on its way in.
            val boxPixels = IntArray(boxW * boxH)
            resultBitmap.getPixels(boxPixels, 0, boxW, leftPx, topPx, boxW, boxH)
            val sampledColor = BubbleInkMask.sampleInteriorColor(boxPixels, boxW, boxH)
            val boxInset = BubbleInterior.measureWallInset(
                FloatArray(boxW * boxH) { BubbleInterior.luminance(boxPixels[it]) },
                boxW,
                boxH
            )

            // A walled bubble is measured over a margin-widened region. A detection box is tight by
            // construction, so the stroke is frequently clipped by its own edge and can no longer be
            // recognised as a wall that closes. The widening is for that measurement alone: the fill
            // colour and the fallback inset above, and the box the shape fallback draws into below,
            // all stay on the detector's own box.
            val margin = if (hasWall(bubble.type)) {
                EnclosedInterior.measurementMargin(boxW, boxH)
            } else {
                0
            }
            val regionLeft = (leftPx - margin).coerceAtLeast(0)
            val regionTop = (topPx - margin).coerceAtLeast(0)
            val regionRight = (rightPx + margin).coerceAtMost(bmpWidth)
            val regionBottom = (bottomPx + margin).coerceAtMost(bmpHeight)
            val regionW = regionRight - regionLeft
            val regionH = regionBottom - regionTop

            val pixels = IntArray(regionW * regionH)
            resultBitmap.getPixels(pixels, 0, regionW, regionLeft, regionTop, regionW, regionH)

            // The enclosure covering this box's centre, if any, lifted into region coordinates.
            // Supplied as the plan's measured interior: ink inside it is text by construction, so
            // the wipe may reach past the detector's rectangle — but never past this mask, which
            // ends at the wall. A box whose centre fell on artwork or between lobes wipes exactly
            // as before, on the box alone.
            val enclosure = enclosureMap.findEnclosureForBoxCenter(
                listOf(bubble.x1, bubble.y1, bubble.x2, bubble.y2)
            )
            val bubbleMask: BooleanArray? = enclosure?.let { enc ->
                BooleanArray(regionW * regionH) { i ->
                    val gx = (i % regionW) + regionLeft
                    val gy = (i / regionW) + regionTop
                    val id = enclosureMap.enclosureIds[gy * bmpWidth + gx]
                    id == enc.id
                }
            }

            val plan = BubbleInkMask.plan(
                pixels,
                regionW,
                regionH,
                sampledColor,
                shape,
                densityScale,
                BubbleInkMask.BoxFrame(
                    left = leftPx - regionLeft,
                    top = topPx - regionTop,
                    width = boxW,
                    height = boxH,
                    inset = boxInset
                ),
                bubbleMask
            )

            if (plan.usedInkMask) {
                // Preferred: repaint only the text, leaving the wall, tail and artwork intact.
                BubbleInkMask.apply(pixels, plan.wipe, sampledColor)
                resultBitmap.setPixels(pixels, 0, regionW, regionLeft, regionTop, regionW, regionH)
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
        if (!hasWall(bubbleType)) {
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
