package com.example.pipeline.wipe

import kotlin.math.ceil
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
        val id = enclosureIdAt(normX, normY)
        return if (id > 0) enclosures[id - 1] else null
    }

    /** The enclosure id covering `(normX, normY)`, or `0` when the point is outside every one. */
    private fun enclosureIdAt(normX: Float, normY: Float): Int {
        if (normX !in 0f..1f || normY !in 0f..1f) return 0
        val px = (normX * width).toInt().coerceIn(0, width - 1)
        val py = (normY * height).toInt().coerceIn(0, height - 1)
        val id = enclosureIds[py * width + px]
        return if (id > 0 && id <= enclosures.size) id else 0
    }

    /**
     * Find the enclosure that owns the normalized `[x1, y1, x2, y2]` box.
     *
     * The box's own center is not a trustworthy probe, and treating it as one produced both field
     * symptoms at once. A detector box regularly has a center that lands on a glyph stroke, on bold
     * effect lettering, or on artwork the box merely overlaps. A single-pixel miss dropped the
     * caller onto its box-only fallback, which then:
     *
     * - wiped lettering *anywhere* inside the box, erasing an effect the box happened to overlap
     *   (the over-wipe); and
     * - could never reach this balloon's own text that fell outside a tight box, so it survived
     *   (the miss).
     *
     * Step 1 is the box centre, resolved exactly as this lookup always resolved it. Only when the
     * centre settles nothing — it landed on ink, on a wall, or outside every balloon — does the
     * whole box vote: a grid of probes is sampled across its interior and the enclosure holding a
     * plurality is adopted, provided:
     *
     * 1. **Coverage:** the winner holds at least [MIN_VOTE_SHARE] of the probes, so the box is
     *    genuinely inside this balloon rather than merely grazing it. A box that mostly sits on
     *    artwork or in a neighbouring bubble stays majority outside and adopts nothing — which is
     *    what a fallback scan used to get wrong.
     * 2. **Scale:** the enclosure's area must not exceed the box area by more than [maxScaleRatio].
     *    A merged two-lobe balloon legitimately dwarfs a one-lobe detector box (the job-13 case this
     *    map exists for), but a page-background enclosure is an order of magnitude larger than any
     *    box. This is what stops the wipe from flooding artwork.
     *
     * Enclosures failing either check return null and the caller falls back to box-only behaviour,
     * exactly as if no enclosure had been measured.
     *
     * Because step 1 is evaluated first and unchanged, the vote can only ever *add* adoptions: any
     * box that used to adopt its balloon still adopts the very same one, and the vote only rescues
     * the boxes whose centre happened to settle on a stroke.
     */
    fun findEnclosureForBox(box: List<Float>, maxScaleRatio: Float = 12f): Enclosure? {
        if (box.size < 4) return null
        val left = minOf(box[0], box[2])
        val top = minOf(box[1], box[3])
        val right = maxOf(box[0], box[2])
        val bottom = maxOf(box[1], box[3])
        val bw = (right - left).coerceAtLeast(1e-4f)
        val bh = (bottom - top).coerceAtLeast(1e-4f)

        // 1. The box centre, resolved exactly as this lookup has always resolved it. A box that used
        //    to adopt its balloon through its centre still adopts that same balloon, so the vote can
        //    only add adoptions — never take one away, which is what an earlier version of this
        //    change did by replacing the centre probe outright (it left text unerased on page 16).
        val centreHit = enclosureIdAt(left + bw / 2f, top + bh / 2f)
        if (centreHit > 0) {
            val found = enclosures.getOrNull(centreHit - 1)
            if (found != null && found.area.toFloat() / (bw * bh * width * height) <= maxScaleRatio) {
                return found
            }
        }

        // 2. The centre settled nothing. Probe cell centers, so no probe sits on the box's own edge — which is exactly where a
        // tight detector box runs into the wall, into the tail, or into whatever it overlaps.
        val votes = HashMap<Int, Int>()
        var probes = 0
        for (row in 0 until VOTE_GRID) {
            for (col in 0 until VOTE_GRID) {
                val nx = left + bw * ((col + 0.5f) / VOTE_GRID)
                val ny = top + bh * ((row + 0.5f) / VOTE_GRID)
                probes++
                val id = enclosureIdAt(nx, ny)
                if (id > 0) votes[id] = (votes[id] ?: 0) + 1
            }
        }
        if (probes == 0) return null
        val winner = votes.maxByOrNull { it.value } ?: return null
        val needed = maxOf(MIN_VOTE_COUNT, ceil(probes * MIN_VOTE_SHARE).toInt())
        if (winner.value < needed) return null

        val found = enclosures.getOrNull(winner.key - 1) ?: return null
        val scaleOk = found.area.toFloat() / (bw * bh * width * height) <= maxScaleRatio
        return if (scaleOk) found else null
    }

    companion object {
        /** Minimum paper pixels for a connected component to qualify as a candidate bubble. */
        private const val MIN_ENCLOSURE_PAPER = 200

        /** Probes per axis used to decide which enclosure owns a detector box: [VOTE_GRID]² total. */
        private const val VOTE_GRID = 7

        /**
         * Share of a box's probes that must land in one enclosure before it is adopted. The box is
         * an approximation of the balloon, so a clear majority is a strong claim; a box that merely
         * overlaps a balloon stays a minority there and adopts nothing.
         */
        private const val MIN_VOTE_SHARE = 0.45f

        /** Absolute floor on winning votes, so a small box is never adopted on one or two probes. */
        private const val MIN_VOTE_COUNT = 4

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
