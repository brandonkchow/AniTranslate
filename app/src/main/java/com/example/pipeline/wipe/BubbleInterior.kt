package com.example.pipeline.wipe

/**
 * Pure geometry helpers describing the *interior* of a speech bubble.
 *
 * The wiper used to treat a detected region as a rectangle of text ink, which is wrong on two
 * counts: a bubble's own outline passes through the inside of its bounding box (the 4px margin
 * that was meant to protect it cannot help, because a curved stroke enters the interior well
 * away from the box edge), and the corners of a round bubble's box are page artwork, not bubble.
 *
 * Everything here is deliberately free of Android types so the maths can be unit-tested on the
 * JVM, where the bitmaps cannot go.
 */
object BubbleInterior {

    /** How a region's interior is shaped, which decides what the wiper is allowed to touch. */
    enum class Shape { ELLIPSE, ROUNDED_RECT, RECT }

    /**
     * Pick the interior shape. Round-ish regions are bubbles and get an ellipse; anything else
     * is treated as a panel. Floating text sits directly on artwork with no wall to protect, so
     * its whole box is fair game.
     */
    fun shapeFor(aspect: Float, bubbleType: String): Shape = when (bubbleType.lowercase()) {
        "narration", "floating", "side_text" -> Shape.RECT
        else -> if (aspect in 0.65f..1.55f) Shape.ELLIPSE else Shape.ROUNDED_RECT
    }

    /**
     * Distance from a bounding-box edge to the inside of the bubble wall, measured from the image
     * rather than assumed.
     *
     * Marches inward along each of the four centre lines: it skips the page background outside the
     * bubble, crosses the stroke, and stops. The interior begins where it stops. Using the **max**
     * of the four rays is the conservative choice — it keeps every wall pixel clear, and text is
     * never laid out hard against a wall anyway.
     *
     * A ray that never crosses a stroke (a region with no wall at all, such as text floating on
     * artwork) is discarded. Returns 0 when no ray finds a wall, which the caller reads as
     * "there is nothing here to protect".
     */
    fun measureWallInset(
        lum: FloatArray,
        width: Int,
        height: Int,
        brightThreshold: Float = 0.75f,
        darkThreshold: Float = 0.55f
    ): Int {
        if (width <= 0 || height <= 0 || lum.size < width * height) return 0

        val cy = height / 2
        val cx = width / 2

        fun ray(at: (Int) -> Float, length: Int): Int {
            var i = 0
            while (i < length && at(i) >= brightThreshold) i++   // background outside the wall
            while (i < length && at(i) < darkThreshold) i++      // the wall itself
            return if (i < length) i else -1
        }

        val left = ray({ i -> lum[cy * width + i] }, width)
        val right = ray({ i -> lum[cy * width + (width - 1 - i)] }, width)
        val top = ray({ i -> lum[i * width + cx] }, height)
        val bottom = ray({ i -> lum[(height - 1 - i) * width + cx] }, height)

        val best = maxOf(left, right, top, bottom)
        // Clamp: a "wall" covering more than a third of the box is not a wall, it is artwork.
        return best.coerceIn(0, minOf(width, height) / 3)
    }

    /**
     * True where a pixel lies inside the bubble, given the box size and the measured wall inset.
     * Pixels outside this mask — the stroke itself and whatever artwork the box corners clipped —
     * are never modified.
     */
    fun interiorMask(width: Int, height: Int, shape: Shape, inset: Int): BooleanArray {
        val mask = BooleanArray(width * height)
        if (width <= 0 || height <= 0) return mask

        val safeInset = inset.coerceIn(0, minOf(width, height) / 3)

        when (shape) {
            Shape.RECT -> {
                val x0 = safeInset
                val x1 = width - 1 - safeInset
                val y0 = safeInset
                val y1 = height - 1 - safeInset
                if (x1 < x0 || y1 < y0) return mask
                for (y in y0..y1) {
                    val row = y * width
                    for (x in x0..x1) mask[row + x] = true
                }
            }

            Shape.ELLIPSE -> {
                val cx = (width - 1) / 2.0
                val cy = (height - 1) / 2.0
                val a = width / 2.0 - safeInset
                val b = height / 2.0 - safeInset
                if (a <= 0.5 || b <= 0.5) return mask
                for (y in 0 until height) {
                    val row = y * width
                    val dy = (y - cy) / b
                    val dySq = dy * dy
                    if (dySq > 1.0) continue
                    val halfSpan = a * kotlin.math.sqrt(1.0 - dySq)
                    val x0 = kotlin.math.ceil(cx - halfSpan).toInt().coerceAtLeast(0)
                    val x1 = kotlin.math.floor(cx + halfSpan).toInt().coerceAtMost(width - 1)
                    for (x in x0..x1) mask[row + x] = true
                }
            }

            Shape.ROUNDED_RECT -> {
                val x0 = safeInset
                val x1 = width - 1 - safeInset
                val y0 = safeInset
                val y1 = height - 1 - safeInset
                if (x1 < x0 || y1 < y0) return mask
                val radius = minOf(width, height) * 0.25
                val innerL = x0 + radius
                val innerR = x1 - radius
                val innerT = y0 + radius
                val innerB = y1 - radius
                for (y in y0..y1) {
                    val row = y * width
                    val cornerY = when {
                        y < innerT -> innerT
                        y > innerB -> innerB
                        else -> null
                    }
                    for (x in x0..x1) {
                        val cornerX = when {
                            x < innerL -> innerL
                            x > innerR -> innerR
                            else -> null
                        }
                        val ok = if (cornerX != null && cornerY != null) {
                            val dx = x - cornerX
                            val dy = y - cornerY
                            dx * dx + dy * dy <= radius * radius
                        } else true
                        if (ok) mask[row + x] = true
                    }
                }
            }
        }
        return mask
    }

    /** Perceptual luminance of a packed ARGB pixel, normalised to 0..1. */
    fun luminance(pixel: Int): Float {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }
}
