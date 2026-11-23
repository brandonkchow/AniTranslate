package com.example.pipeline.wipe

/**
 * Pure pixel math for the learned-mask wipe (prompt 20261118, Step 1).
 *
 * Contract, both tiers: the output equals the original everywhere except
 * inside the erase mask (CTD segmentation, dilated, AND detector boxes);
 * pixels outside the erase mask are copied bit-identically. This is the
 * Commander's "original laid back on top" test, by construction.
 *
 * No Android types here: the desktop and unit harnesses exercise this
 * exact code, the Android adapters only convert Bitmap <-> IntArray and
 * run the ONNX models.
 */
object MaskComposite {

    const val SEG_THRESHOLD = 0.5f

    /** Bilinear-resample a row-major float map and threshold it. */
    fun thresholdResize(
        src: FloatArray, srcW: Int, srcH: Int,
        dstW: Int, dstH: Int, threshold: Float = SEG_THRESHOLD
    ): BooleanArray {
        val out = BooleanArray(dstW * dstH)
        for (y in 0 until dstH) {
            val sy = (y * srcH.toFloat() / dstH).coerceIn(0f, (srcH - 1).toFloat())
            val y0 = sy.toInt(); val y1 = minOf(y0 + 1, srcH - 1); val fy = sy - y0
            for (x in 0 until dstW) {
                val sx = (x * srcW.toFloat() / dstW).coerceIn(0f, (srcW - 1).toFloat())
                val x0 = sx.toInt(); val x1 = minOf(x0 + 1, srcW - 1); val fx = sx - x0
                val v = src[y0 * srcW + x0] * (1 - fx) * (1 - fy) +
                    src[y0 * srcW + x1] * fx * (1 - fy) +
                    src[y1 * srcW + x0] * (1 - fx) * fy +
                    src[y1 * srcW + x1] * fx * fy
                out[y * dstW + x] = v > threshold
            }
        }
        return out
    }

    /** 5x5 elliptical dilation, matching the desktop proof's cv2 kernel. */
    fun dilate(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val offsets = intArrayOf(
            -2, 0, -1, -1, -1, 0, -1, 1, 0, -2, 0, -1, 0, 0, 0, 1, 0, 2, 1, -1, 1, 0, 1, 1, 2, 0
        )
        val out = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var d = false
            var i = 0
            while (i < offsets.size) {
                val yy = y + offsets[i]; val xx = x + offsets[i + 1]
                if (yy in 0 until h && xx in 0 until w && mask[yy * w + xx]) { d = true; break }
                i += 2
            }
            out[y * w + x] = d
        }
        return out
    }

    /**
     * Erase mask = dilated segmentation AND the union of detector boxes.
     * [boxes] are normalized [x1, y1, x2, y2] quads.
     */
    fun eraseMask(seg: BooleanArray, w: Int, h: Int, boxes: List<FloatArray>): BooleanArray {
        val dil = dilate(seg, w, h)
        val inbox = BooleanArray(w * h)
        for (b in boxes) {
            val x1 = (b[0] * w).toInt().coerceIn(0, w); val x2 = (b[2] * w).toInt().coerceIn(0, w)
            val y1 = (b[1] * h).toInt().coerceIn(0, h); val y2 = (b[3] * h).toInt().coerceIn(0, h)
            for (y in y1 until y2) for (x in x1 until x2) inbox[y * w + x] = true
        }
        return BooleanArray(w * h) { dil[it] && inbox[it] }
    }

    /**
     * Standard-tier fill: every erased pixel takes the colour of its
     * nearest surviving (non-erased) pixel, multi-source BFS. Returns a
     * new array; the input is never mutated.
     */
    fun compositeNearestWH(pixels: IntArray, w: Int, h: Int, erase: BooleanArray): IntArray {
        val src = IntArray(w * h) { -1 }
        val queue = IntArray(w * h); var qh = 0; var qt = 0
        for (i in 0 until w * h) if (!erase[i]) { src[i] = i; queue[qt++] = i }
        while (qh < qt) {
            val cur = queue[qh++]; val cx = cur % w; val cy = cur / w
            if (cx + 1 < w && src[cur + 1] == -1) { src[cur + 1] = src[cur]; queue[qt++] = cur + 1 }
            if (cx > 0 && src[cur - 1] == -1) { src[cur - 1] = src[cur]; queue[qt++] = cur - 1 }
            if (cy + 1 < h && src[cur + w] == -1) { src[cur + w] = src[cur]; queue[qt++] = cur + w }
            if (cy > 0 && src[cur - w] == -1) { src[cur - w] = src[cur]; queue[qt++] = cur - w }
        }
        return IntArray(w * h) { i -> if (erase[i]) pixels[src[i]] else pixels[i] }
    }

    /** Copy [fill] over [base] only where [erase] is set. */
    fun compositeOnlyMasked(base: IntArray, fill: IntArray, erase: BooleanArray): IntArray =
        IntArray(base.size) { i -> if (erase[i]) fill[i] else base[i] }
}
