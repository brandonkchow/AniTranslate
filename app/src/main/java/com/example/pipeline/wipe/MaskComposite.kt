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

    /**
     * Interior ink components the learned mask missed: dark components
     * fully inside a detector box that do not touch the box edge. The
     * edge rule is the protection — ink fused to the bubble outline or
     * the box border is never erased by this rule. This is the old
     * component wipe's rule (accepted on page 09 in 2026-11) reused as
     * the mask's safety net for isolated strokes like the page-09 id11
     * dash, which the CTD segmentation scores below threshold.
     * Components are dilated 1px to take their anti-aliased fringe.
     */
    fun interiorComponentMask(pixels: IntArray, w: Int, h: Int, boxes: List<FloatArray>): BooleanArray {
        val out = BooleanArray(w * h)
        val queue = IntArray(w * h)
        for (b in boxes) {
            val x1 = (b[0] * w).toInt().coerceIn(0, w); val x2 = (b[2] * w).toInt().coerceIn(0, w)
            val y1 = (b[1] * h).toInt().coerceIn(0, h); val y2 = (b[3] * h).toInt().coerceIn(0, h)
            if (x2 <= x1 || y2 <= y1) continue
            val bw = x2 - x1; val bh = y2 - y1
            val seen = BooleanArray(bw * bh)
            for (sy in 0 until bh) for (sx in 0 until bw) {
                val start = sy * bw + sx
                if (seen[start]) continue
                seen[start] = true
                if (BubbleInterior.luminance(pixels[(y1 + sy) * w + (x1 + sx)]) > 0.40f) continue
                var head = 0; var tail = 0
                queue[tail++] = start
                var count = 0; var touchesEdge = false
                var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
                val members = IntArray(bw * bh); var mCount = 0
                while (head < tail) {
                    val cur = queue[head++]
                    val cx = cur % bw; val cy = cur / bw
                    count++; members[mCount++] = cur
                    if (cx < minX) minX = cx; if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
                    if (cx == 0 || cy == 0 || cx == bw - 1 || cy == bh - 1) touchesEdge = true
                    val nb = intArrayOf(
                        if (cx + 1 < bw) cur + 1 else -1, if (cx > 0) cur - 1 else -1,
                        if (cy + 1 < bh) cur + bw else -1, if (cy > 0) cur - bw else -1
                    )
                    for (n in nb) {
                        if (n < 0 || seen[n]) continue
                        seen[n] = true
                        if (BubbleInterior.luminance(pixels[(y1 + n / bw) * w + (x1 + n % bw)]) <= 0.40f) queue[tail++] = n
                    }
                }
                // Glyph-scaled only: a large interior blob is art, and
                // protecting art inside boxes is why the learned mask
                // exists. Same scale bounds as the coverage flag.
                val glyphW = maxX - minX + 1; val glyphH = maxY - minY + 1
                if (touchesEdge || count < 10) continue
                if (glyphW > maxOf(24, (w * 0.05f).toInt()) || glyphH > maxOf(32, (h * 0.08f).toInt())) continue
                for (m in 0 until mCount) {
                    val cur = members[m]; val cx = cur % bw; val cy = cur / bw
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = cx + dx; val ny = cy + dy
                        if (nx in 0 until bw && ny in 0 until bh) out[(y1 + ny) * w + (x1 + nx)] = true
                    }
                }
            }
        }
        return out
    }

    /** Copy [fill] over [base] only where [erase] is set. */
    fun compositeOnlyMasked(base: IntArray, fill: IntArray, erase: BooleanArray): IntArray =
        IntArray(base.size) { i -> if (erase[i]) fill[i] else base[i] }
}
