package com.example.pipeline.wipe

import kotlin.math.max

/**
 * Maps closed white speech bubble enclosures across a full manga page.
 *
 * Speech bubbles in manga are enclosed paper regions surrounded by dark outlines. An on-device
 * object detector often emits bounding boxes that are slightly too tight, slightly misaligned, or
 * cover only one lobe of a multi-lobe bubble. Because classical segmentation by connected-component
 * flood fill operates directly on the page geometry, it can measure the true enclosed interior of
 * speech balloons with absolute fidelity:
 *
 *  - **Wall protection**: The flood never crosses the dark stroke enclosing the paper. The bubble
 *    outline is contiguous with the outer artwork or border, so it is never part of the interior.
 *  - **Lobe discovery**: Multi-lobe balloons connected by an open neck are naturally unified into a
 *    single continuous enclosure, recovering dialogue that would otherwise sit outside a single
 *    detector box.
 *  - **Floating bubble recovery**: When the vision LLM flags text as floating because the detector
 *    missed the bubble box, measuring the enclosure area allows reclassifying the text as a bubble
 *    whenever it sits inside a closed white balloon much larger than the text block itself.
 *
 * JVM-pure, no Android dependencies.
 */
class EnclosureMap private constructor(
    val width: Int,
    val height: Int,
    val enclosureIds: IntArray,
    val enclosures: List<Enclosure>
) {

    /**
     * A measured speech bubble enclosure.
     *
     * @param id unique 1-based identifier matching entries in [enclosureIds].
     * @param area total number of interior pixels (paper + enclosed glyphs).
     * @param pixelBounds bounding box `[minX, minY, maxX, maxY]` in page pixel coordinates.
     * @param normalizedBounds bounding box `[x1, y1, x2, y2]` in normalized `0.0..1.0` coordinates.
     */
    data class Enclosure(
        val id: Int,
        val area: Int,
        val pixelBounds: IntArray,
        val normalizedBounds: List<Float>
    ) {
        /**
         * Whether the center of the given normalized `[x1, y1, x2, y2]` box falls within this
         * enclosure's bounding box.
         */
        fun containsCenter(box: List<Float>): Boolean {
            if (box.size < 4) return false
            val cx = (box[0] + box[2]) * 0.5f
            val cy = (box[1] + box[3]) * 0.5f
            return cx >= normalizedBounds[0] && cx <= normalizedBounds[2] &&
                cy >= normalizedBounds[1] && cy <= normalizedBounds[3]
        }
    }

    /**
     * Look up the enclosure covering the normalized page coordinate `(normX, normY)`.
     */
    fun findEnclosureAt(normX: Float, normY: Float): Enclosure? {
        if (normX !in 0f..1f || normY !in 0f..1f) return null
        val px = (normX * width).toInt().coerceIn(0, width - 1)
        val py = (normY * height).toInt().coerceIn(0, height - 1)
        val id = enclosureIds[py * width + px]
        return if (id > 0 && id <= enclosures.size) enclosures[id - 1] else null
    }

    /**
     * Find the enclosure covering the center of the normalized `[x1, y1, x2, y2]` box, but only
     * when the enclosure is a plausible interior *for this box*. Two conditions:
     *
     * 1. **Direct hit:** the box's center itself must lie inside the enclosure. A center that
     *    landed on artwork or between bubbles must NOT adopt a neighbouring bubble's interior
     *    (a spiral search would do exactly that, wiping text that belongs to no box), so there
     *    is deliberately no fallback scan here.
     * 2. **Scale:** the enclosure's area must not exceed the box area by more than
     *    [maxScaleRatio]. A merged two-lobe balloon legitimately dwarfs a one-lobe detector box
     *    (the job-13 case this map exists for), but a page-background enclosure is an order of
     *    magnitude larger than any box. This is what stops the wipe from flooding artwork.
     *
     * Enclosures failing either check return null and the caller falls back to box-only
     * behaviour, exactly as if no enclosure had been measured.
     */
    fun findEnclosureForBoxCenter(box: List<Float>, maxScaleRatio: Float = 12f): Enclosure? {
        if (box.size < 4) return null
        val cx = (box[0] + box[2]) * 0.5f
        val cy = (box[1] + box[3]) * 0.5f
        val found = findEnclosureAt(cx, cy) ?: return null

        val bw = (box[2] - box[0]).coerceAtLeast(1e-4f)
        val bh = (box[3] - box[1]).coerceAtLeast(1e-4f)
        val boxArea = bw * bh
        val scaleOk = found.area.toFloat() / (boxArea * width * height) <= maxScaleRatio
        return if (scaleOk) found else null
    }

    companion object {
        /** Minimum paper pixels for a connected component to qualify as a candidate bubble. */
        private const val MIN_ENCLOSURE_PAPER = 200

        /** Minimum ink pixels inside an enclosure to qualify as text-bearing bubble. */
        private const val MIN_TEXT_INK = 10

        /**
         * Analyze full-page luminance and build an [EnclosureMap].
         *
         * @param luminance row-major float array of luminance values, length `width * height`.
         * @param width image width in pixels.
         * @param height image height in pixels.
         */
        fun build(luminance: FloatArray, width: Int, height: Int): EnclosureMap {
            val count = width * height
            require(count > 0) { "page must not be empty" }
            require(luminance.size >= count) { "need $count luminance values, got ${luminance.size}" }

            val paper = BooleanArray(count) { luminance[it] >= EnclosedInterior.PAPER_LUMINANCE }
            val borderPaper = BooleanArray(count)
            val queue = IntArray(count)
            var head = 0
            var tail = 0

            // 1. Mark paper connected to the page borders as open background / margins.
            for (x in 0 until width) {
                if (paper[x] && !borderPaper[x]) {
                    borderPaper[x] = true
                    queue[tail++] = x
                }
                val bottomIdx = (height - 1) * width + x
                if (paper[bottomIdx] && !borderPaper[bottomIdx]) {
                    borderPaper[bottomIdx] = true
                    queue[tail++] = bottomIdx
                }
            }
            for (y in 0 until height) {
                val leftIdx = y * width
                if (paper[leftIdx] && !borderPaper[leftIdx]) {
                    borderPaper[leftIdx] = true
                    queue[tail++] = leftIdx
                }
                val rightIdx = y * width + width - 1
                if (paper[rightIdx] && !borderPaper[rightIdx]) {
                    borderPaper[rightIdx] = true
                    queue[tail++] = rightIdx
                }
            }

            val dxs = intArrayOf(1, -1, 0, 0)
            val dys = intArrayOf(0, 0, 1, -1)
            while (head < tail) {
                val p = queue[head++]
                val px = p % width
                val py = p / width
                for (k in 0..3) {
                    val nx = px + dxs[k]
                    val ny = py + dys[k]
                    if (nx in 0 until width && ny in 0 until height) {
                        val np = ny * width + nx
                        if (paper[np] && !borderPaper[np]) {
                            borderPaper[np] = true
                            queue[tail++] = np
                        }
                    }
                }
            }

            // 2. Identify enclosed paper components and fill internal glyphs.
            val visited = BooleanArray(count)
            val enclosureIds = IntArray(count)
            val enclosures = mutableListOf<Enclosure>()
            var nextId = 1

            for (i in 0 until count) {
                if (!paper[i] || borderPaper[i] || visited[i]) continue

                head = 0
                tail = 0
                visited[i] = true
                queue[tail++] = i
                var minX = width
                var maxX = 0
                var minY = height
                var maxY = 0

                while (head < tail) {
                    val p = queue[head++]
                    val px = p % width
                    val py = p / width
                    if (px < minX) minX = px
                    if (px > maxX) maxX = px
                    if (py < minY) minY = py
                    if (py > maxY) maxY = py
                    for (k in 0..3) {
                        val nx = px + dxs[k]
                        val ny = py + dys[k]
                        if (nx in 0 until width && ny in 0 until height) {
                            val np = ny * width + nx
                            if (paper[np] && !borderPaper[np] && !visited[np]) {
                                visited[np] = true
                                queue[tail++] = np
                            }
                        }
                    }
                }

                val paperSize = tail
                if (paperSize < MIN_ENCLOSURE_PAPER) continue

                // Fill glyphs within a bounded window around the paper component.
                val margin = max(16, EnclosedInterior.measurementMargin(maxX - minX + 1, maxY - minY + 1))
                val winLeft = (minX - margin).coerceAtLeast(0)
                val winTop = (minY - margin).coerceAtLeast(0)
                val winRight = (maxX + margin).coerceAtMost(width - 1)
                val winBottom = (maxY + margin).coerceAtMost(height - 1)
                val winW = winRight - winLeft + 1
                val winH = winBottom - winTop + 1
                val winCount = winW * winH

                val localPaper = BooleanArray(winCount)
                for (k in 0 until paperSize) {
                    val p = queue[k]
                    val px = p % width
                    val py = p / width
                    localPaper[(py - winTop) * winW + (px - winLeft)] = true
                }

                val localComp = BooleanArray(winCount) { !localPaper[it] }
                val compOutside = BooleanArray(winCount)
                val lQueue = IntArray(winCount)
                var lHead = 0
                var lTail = 0

                for (x in 0 until winW) {
                    if (localComp[x] && !compOutside[x]) {
                        compOutside[x] = true
                        lQueue[lTail++] = x
                    }
                    val b = (winH - 1) * winW + x
                    if (localComp[b] && !compOutside[b]) {
                        compOutside[b] = true
                        lQueue[lTail++] = b
                    }
                }
                for (y in 0 until winH) {
                    val l = y * winW
                    if (localComp[l] && !compOutside[l]) {
                        compOutside[l] = true
                        lQueue[lTail++] = l
                    }
                    val r = y * winW + winW - 1
                    if (localComp[r] && !compOutside[r]) {
                        compOutside[r] = true
                        lQueue[lTail++] = r
                    }
                }

                while (lHead < lTail) {
                    val p = lQueue[lHead++]
                    val px = p % winW
                    val py = p / winW
                    for (k in 0..3) {
                        val nx = px + dxs[k]
                        val ny = py + dys[k]
                        if (nx in 0 until winW && ny in 0 until winH) {
                            val np = ny * winW + nx
                            if (localComp[np] && !compOutside[np]) {
                                compOutside[np] = true
                                lQueue[lTail++] = np
                            }
                        }
                    }
                }

                var totalArea = 0
                var inkCount = 0
                var encMinX = width
                var encMaxX = 0
                var encMinY = height
                var encMaxY = 0

                for (wy in 0 until winH) {
                    val gy = winTop + wy
                    for (wx in 0 until winW) {
                        val lIdx = wy * winW + wx
                        if (localPaper[lIdx] || !compOutside[lIdx]) {
                            val gx = winLeft + wx
                            val gIdx = gy * width + gx
                            totalArea++
                            if (luminance[gIdx] < 0.50f) inkCount++
                            if (gx < encMinX) encMinX = gx
                            if (gx > encMaxX) encMaxX = gx
                            if (gy < encMinY) encMinY = gy
                            if (gy > encMaxY) encMaxY = gy
                        }
                    }
                }

                if (inkCount >= MIN_TEXT_INK) {
                    val curId = nextId++
                    for (wy in 0 until winH) {
                        val gy = winTop + wy
                        for (wx in 0 until winW) {
                            val lIdx = wy * winW + wx
                            if (localPaper[lIdx] || !compOutside[lIdx]) {
                                val gx = winLeft + wx
                                val gIdx = gy * width + gx
                                enclosureIds[gIdx] = curId
                            }
                        }
                    }
                    enclosures.add(
                        Enclosure(
                            id = curId,
                            area = totalArea,
                            pixelBounds = intArrayOf(encMinX, encMinY, encMaxX, encMaxY),
                            normalizedBounds = listOf(
                                encMinX.toFloat() / width,
                                encMinY.toFloat() / height,
                                encMaxX.toFloat() / width,
                                encMaxY.toFloat() / height
                            )
                        )
                    )
                }
            }

            return EnclosureMap(width, height, enclosureIds, enclosures)
        }
    }
}
