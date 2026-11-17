package com.example.pipeline.wipe

import android.graphics.Bitmap
import com.example.data.models.Bubble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

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

        val bmpWidth = sourceBitmap.width
        val bmpHeight = sourceBitmap.height
        val densityScale = max(1.0f, max(bmpWidth, bmpHeight) / 1000f)

        // Box-only wipe (wipe-reset, Commander 2026-11-16): no enclosure geometry
        // feeds the wipe. EnclosureMap still serves BubbleTextMerger upstream in
        // PagePipeline; the wipe's repaint region is the detector box, full stop.

        for (bubble in bubbles) {
            if (!bubble.visible) continue

            // Out-of-scope types only (side_text). Free text (floating) is handled
            // by the extent-box branch below (BACKLOG:13).
            if (bubble.isNonBalloon) continue

            // Free text (detector text_free, BACKLOG:13): there is no balloon wall
            // or measured enclosure to wipe within, so the wipe extent is the
            // Japanese text block's own box and nothing beyond it. The wipe follows
            // the glyphs, not the rectangle: only floating ink components are
            // repainted, so artwork inside the extent survives. The repaint
            // colour comes from each glyph pixel's nearest surviving
            // neighbour, not one flat fill: free text sits on artwork, and a
            // flat bright fill leaves the glyph shapes standing as ghosts on
            // anything darker. The typesetter writes the English back into
            // the same extent.
            if (bubble.type.equals("floating", ignoreCase = true)) {
                val fLeft = (bubble.x1 * bmpWidth).toInt().coerceIn(0, bmpWidth - 1)
                val fTop = (bubble.y1 * bmpHeight).toInt().coerceIn(0, bmpHeight - 1)
                val fRight = (bubble.x2 * bmpWidth).toInt().coerceIn(fLeft + 1, bmpWidth)
                val fBottom = (bubble.y2 * bmpHeight).toInt().coerceIn(fTop + 1, bmpHeight)
                val fW = fRight - fLeft
                val fH = fBottom - fTop
                if (fW > 2 && fH > 2) {
                    val boxPixels = IntArray(fW * fH)
                    resultBitmap.getPixels(boxPixels, 0, fW, fLeft, fTop, fW, fH)
                    val fill = BubbleInkMask.sampleInteriorColor(boxPixels, fW, fH)
                    val plan = BubbleInkMask.plan(
                        boxPixels, fW, fH, fill,
                        BubbleInterior.Shape.RECT, densityScale,
                        BubbleInkMask.BoxFrame(0, 0, fW, fH, 0)
                    )
                    if (plan.usedInkMask) {
                        BubbleInkMask.repaintNearest(boxPixels, fW, fH, plan.wipe, fill)
                        resultBitmap.setPixels(boxPixels, 0, fW, fLeft, fTop, fW, fH)
                    }
                    // No floating ink classified: repaint nothing. No flat
                    // rectangle fill remains to fall back to.
                }
                continue
            }

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

            val plan = BubbleInkMask.plan(
                boxPixels,
                boxW,
                boxH,
                sampledColor,
                shape,
                densityScale,
                BubbleInkMask.BoxFrame(0, 0, boxW, boxH, inset = boxInset)
            )

            // Wipe 1b: repaint only the floating components the plan erased,
            // each pixel with its nearest surviving colour. Border-touching
            // ink (the outline) is not in the wipe mask and survives. When no
            // floating ink was found the box is left exactly as drawn — there
            // is no sampled flat fill and no fitted-shape interior fallback.
            if (plan.usedInkMask) {
                BubbleInkMask.repaintNearest(boxPixels, boxW, boxH, plan.wipe, sampledColor)
                resultBitmap.setPixels(boxPixels, 0, boxW, leftPx, topPx, boxW, boxH)
            }
        }

        resultBitmap
    }
}
