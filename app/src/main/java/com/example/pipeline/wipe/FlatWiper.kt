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

            // Balloon interiors only. Text that sits on artwork — effects, onomatopoeia, signage,
            // captions — is left exactly as drawn: it has no uniform background to repaint it
            // with, and no wall to bound a wipe, so any attempt smudges the line art underneath.
            // A region the merger reclassified into a *measured* enclosure arrives here typed
            // "bubble", so wall-less dialogue that is genuinely inside a balloon still wipes.
            if (bubble.isNonBalloon) continue

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
            // colour and the wall-inset estimate above are taken from the detector's own box, and the
            // plan's frame below is still that box, so a widened measurement can never widen the
            // region that gets repainted.
            val margin = EnclosedInterior.measurementMargin(boxW, boxH)
            val regionLeft = (leftPx - margin).coerceAtLeast(0)
            val regionTop = (topPx - margin).coerceAtLeast(0)
            val regionRight = (rightPx + margin).coerceAtMost(bmpWidth)
            val regionBottom = (bottomPx + margin).coerceAtMost(bmpHeight)
            val regionW = regionRight - regionLeft
            val regionH = regionBottom - regionTop

            val pixels = IntArray(regionW * regionH)
            resultBitmap.getPixels(pixels, 0, regionW, regionLeft, regionTop, regionW, regionH)

            // The enclosure that owns this box, if any, lifted into region coordinates. Supplied as
            // the plan's measured interior: ink inside it is text by construction, so the wipe may
            // reach past the detector's rectangle — but never past this mask, which ends at the
            // wall. A box that mostly sits on artwork or in a neighbouring bubble adopts nothing, and
            // the plan then falls back to the interior it measures for itself — never to the box, and
            // never to a fitted shape, so nothing outside a measured interior can be repainted.
            val enclosure = enclosureMap.findEnclosureForBox(
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

            // Either the plan classified lettering and returns the pixels to repaint (`wipe`), or it
            // found nothing readable and returns the interior it measured instead (`interior`) so
            // that half-erased lettering is still cleared.
            //
            // The second case used to paint a *fitted oval over the whole detector box*, throwing
            // away the mask the plan had just measured. A box is routinely larger than the balloon
            // it belongs to and can straddle artwork, so that oval reached past the wall and erased
            // the effects and line art around it. A measured interior cannot do that: it is the
            // region the image itself closes, and it ends at the stroke by construction.
            BubbleInkMask.apply(pixels, if (plan.usedInkMask) plan.wipe else plan.interior, sampledColor)
            resultBitmap.setPixels(pixels, 0, regionW, regionLeft, regionTop, regionW, regionH)
        }

        resultBitmap
    }
}
