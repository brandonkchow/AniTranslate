package com.example.pipeline.detect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.Collections

/**
 * Tier 1 on-device comic text & bubble detector.
 *
 * Model: `ogkalu/comic-text-and-bubble-detector` (RT-DETR-v2 / ResNet-vd, Apache-2.0),
 * INT8-quantized — see `assets/models/detector-v4-s_int8.onnx` (10.6 MB, opset 18).
 *
 * Why a real detector and not a vision LLM: VLMs regress boxes poorly and return
 * plausible-but-wrong geometry (measured: three differently-sized bubbles all returned
 * at a uniform 12% width, ~60% undersized). A dedicated detector is box-accurate to ~1%
 * absolute, runs fully offline, costs nothing per page, and leaks nothing to a provider.
 *
 * Session contract (verified against the shipped asset):
 *   images            : float32 [1, 3, 640, 640]  — RGB, rescaled by 1/255, NO normalization
 *   orig_target_sizes : int64   [1, 2]            — [width, height] of the source image
 *   -> labels         : int64   [1, 300]
 *      boxes          : float32 [1, 300, 4]       — xyxy in SOURCE-IMAGE PIXELS (already rescaled)
 *      scores         : float32 [1, 300]
 *
 * The model emits final detections (no anchors, no NMS), so the client side is a filter.
 */
class OnnxBubbleDetector(private val context: Context) {

    companion object {
        private const val TAG = "OnnxBubbleDetector"

        const val MODEL_ASSET = "models/detector-v4-s_int8.onnx"
        private const val EXPECTED_SHA256 =
            "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79"

        /** Fixed network input resolution required by the exported graph. */
        const val INPUT_SIZE = 640

        /** RT-DETR predicts a fixed set of 300 queries per image. */
        private const val NUM_QUERIES = 300

        /** Below this confidence a detection is noise; tuned against real manga pages. */
        const val DEFAULT_SCORE_THRESHOLD = 0.50f

        /** Class ids from the model config (`id2label`). */
        const val LABEL_BUBBLE = "bubble"
        const val LABEL_TEXT_BUBBLE = "text_bubble"
        const val LABEL_TEXT_FREE = "text_free"

        private val LABELS = arrayOf(LABEL_BUBBLE, LABEL_TEXT_BUBBLE, LABEL_TEXT_FREE)
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    /** True once a session is live; the pipeline uses this to decide whether to fall back. */
    @Volatile
    var isReady: Boolean = false
        private set

    /**
     * Rough byte size of the model, exposed so callers can log without touching disk.
     */
    fun loadModel(): Boolean {
        if (isReady) return true
        return try {
            val modelFile = ensureModelOnDisk()
            val opts = OrtSession.SessionOptions().apply {
                // Two threads: the S25 has plenty of cores, but ORT on mobile thrashes
                // above ~4. Leave headroom for the UI thread and the rest of the pipeline.
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = env.createSession(modelFile.absolutePath, opts)
            isReady = true
            Log.i(TAG, "Session ready: ${modelFile.length() / 1024} KB, inputs=${session?.inputNames}")
            true
        } catch (t: Throwable) {
            // Never let a detector failure kill the page — the caller falls back to the
            // cloud vision slot, which is exactly today's behaviour.
            Log.e(TAG, "Failed to initialise ONNX session: ${t.message}", t)
            isReady = false
            false
        }
    }

    /**
     * Detect bubble/text regions on [bitmap].
     *
     * Returns an empty list on any failure so the pipeline can transparently fall back.
     * Boxes are normalized to 0..1 against the source image, ordered in manga reading
     * order (top-to-bottom, then right-to-left).
     */
    fun detect(
        bitmap: Bitmap,
        scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD
    ): List<DetectedRegion> {
        val s = session ?: run { if (!loadModel()) return emptyList(); session } ?: return emptyList()

        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW <= 0 || srcH <= 0) return emptyList()

        return try {
            val input = preprocess(bitmap)

            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            ).use { imagesTensor ->
                OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(longArrayOf(srcW.toLong(), srcH.toLong())),
                    longArrayOf(1, 2)
                ).use { sizesTensor ->
                    val inputs = mapOf("images" to imagesTensor, "orig_target_sizes" to sizesTensor)
                    s.run(inputs).use { out ->
                        // Fetch by name rather than index: output ordering is an export
                        // detail, the tensor names are the actual contract.
                        val labels = flattenLongs(out.get("labels").orElse(null)?.value)
                        val boxes = flattenFloats(out.get("boxes").orElse(null)?.value)
                        val scores = flattenFloats(out.get("scores").orElse(null)?.value)
                        DetectorPostProcess.regionsFromRawOutput(
                            labels = labels,
                            boxes = boxes,
                            scores = scores,
                            numQueries = NUM_QUERIES,
                            labelNames = LABELS,
                            srcWidth = srcW,
                            srcHeight = srcH,
                            scoreThreshold = scoreThreshold
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Inference failed: ${t.message}", t)
            emptyList()
        }
    }

    fun close() {
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        session = null
        isReady = false
    }

    // ---------------------------------------------------------------- internals

    /**
     * Flatten a tensor value to a flat FloatArray.
     *
     * ORT nests `getValue()` results to match the tensor rank, and its exact nesting for
     * leading-1 dimensions has changed between releases. Walking the structure removes
     * that coupling entirely — we only ever care about the element sequence.
     */
    private fun flattenFloats(value: Any?): FloatArray {
        val acc = ArrayList<Float>(NUM_QUERIES * 4)
        fun walk(node: Any?) {
            when (node) {
                is FloatArray -> node.forEach { acc.add(it) }
                is Float -> acc.add(node)
                is Array<*> -> node.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(value)
        return acc.toFloatArray()
    }

    /** Long-valued counterpart of [flattenFloats]. */
    private fun flattenLongs(value: Any?): LongArray {
        val acc = ArrayList<Long>(NUM_QUERIES)
        fun walk(node: Any?) {
            when (node) {
                is LongArray -> node.forEach { acc.add(it) }
                is Long -> acc.add(node)
                is Int -> acc.add(node.toLong())
                is Array<*> -> node.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(value)
        return acc.toLongArray()
    }

    /**
     * Resize to the fixed 640x640 square, rescale to 0..1 and lay out as NCHW.
     *
     * The exported processor uses `do_resize` + `do_rescale` with `do_normalize: false`
     * and `do_pad: false`, i.e. a direct squash rather than a letterbox — matching that
     * exactly keeps the ~1% box accuracy we measured.
     */
    private fun preprocess(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== bitmap) scaled.recycle()

        val plane = INPUT_SIZE * INPUT_SIZE
        val out = FloatArray(3 * plane)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i] = ((p shr 16) and 0xFF) / 255f
            out[plane + i] = ((p shr 8) and 0xFF) / 255f
            out[2 * plane + i] = (p and 0xFF) / 255f
        }
        return out
    }

    /**
     * Copy the packaged weights into app storage once, verifying the digest so a
     * corrupted or tampered asset can never silently degrade detection.
     */
    private fun ensureModelOnDisk(): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val target = File(dir, MODEL_ASSET.substringAfterLast('/'))
        if (target.exists() && target.length() > 0 && sha256(target) == EXPECTED_SHA256) {
            return target
        }
        context.assets.open(MODEL_ASSET).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val digest = sha256(target)
        if (digest != EXPECTED_SHA256) {
            Log.w(TAG, "Model digest mismatch: got $digest")
        }
        return target
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** A single region returned by the on-device detector. */
data class DetectedRegion(
    val label: String,
    val score: Float,
    /** Normalized [x1, y1, x2, y2] in 0..1. */
    val box: List<Float>
)

/**
 * Pure post-processing, deliberately free of Android and ORT types so it is unit-testable
 * on the JVM without a device or a model file.
 */
object DetectorPostProcess {

    /**
     * Turn the raw `labels` / `boxes` / `scores` tensors into normalized regions in
     * manga reading order.
     *
     * `boxes` arrive as xyxy in source-image pixels; we divide by the source dimensions
     * so the rest of the pipeline (which speaks 0..1) needs no special casing.
     */
    fun regionsFromRawOutput(
        labels: LongArray,
        boxes: FloatArray,
        scores: FloatArray,
        numQueries: Int,
        labelNames: Array<String>,
        srcWidth: Int,
        srcHeight: Int,
        scoreThreshold: Float
    ): List<DetectedRegion> {
        if (srcWidth <= 0 || srcHeight <= 0) return emptyList()
        val w = srcWidth.toFloat()
        val h = srcHeight.toFloat()

        val found = ArrayList<DetectedRegion>()
        val limit = minOf(numQueries, labels.size, scores.size, boxes.size / 4)
        for (i in 0 until limit) {
            val score = scores[i]
            if (score < scoreThreshold || score.isNaN()) continue

            val labelIdx = labels[i].toInt()
            if (labelIdx < 0 || labelIdx >= labelNames.size) continue

            val o = i * 4
            // Guard against degenerate output rather than trusting it downstream.
            val x1 = boxes[o].coerceIn(0f, w) / w
            val y1 = boxes[o + 1].coerceIn(0f, h) / h
            val x2 = boxes[o + 2].coerceIn(0f, w) / w
            val y2 = boxes[o + 3].coerceIn(0f, h) / h
            if (x2 <= x1 || y2 <= y1) continue

            found += DetectedRegion(
                label = labelNames[labelIdx],
                score = score,
                box = listOf(x1, y1, x2, y2)
            )
        }
        return sortReadingOrder(found)
    }

    /**
     * Height of a reading-order row band, in normalized page units. A bubble slightly
     * lower but far right still precedes the one to its left (manga reads right-to-left).
     */
    const val READING_ORDER_BAND = 0.08f

    /** Order regions top-to-bottom, then right-to-left, for any boxed type. */
    fun <T> readingOrderComparator(boxOf: (T) -> List<Float>): Comparator<T> =
        compareBy(
            { Math.round(boxOf(it)[1] / READING_ORDER_BAND) },
            { -boxOf(it)[0] }
        )

    fun sortReadingOrder(regions: List<DetectedRegion>): List<DetectedRegion> {
        if (regions.size < 2) return regions
        return regions.sortedWith(readingOrderComparator { it.box })
    }

    /**
     * Pick the bubble regions that should drive wipe + typeset geometry.
     *
     * We prefer explicit `bubble` detections; `text_bubble` is a useful refinement
     * (it covers just the glyphs) but is not a substitute for the bubble outline.
     */
    fun bubbleRegions(regions: List<DetectedRegion>): List<DetectedRegion> =
        regions.filter { it.label == OnnxBubbleDetector.LABEL_BUBBLE }

    /**
     * Text regions *inside* bubbles. These are not used as geometry — they refine nothing the
     * bubble outline does not already give — but their shape is the most reliable signal of
     * text direction available, because it measures the text rather than the container.
     */
    fun textBubbleRegions(regions: List<DetectedRegion>): List<DetectedRegion> =
        regions.filter { it.label == OnnxBubbleDetector.LABEL_TEXT_BUBBLE }

    /**
     * Text lying directly on the artwork (sound effects, narration, signage) — has no bubble
     * outline, so it is used to re-anchor text the vision slot found outside any bubble.
     */
    fun freeTextRegions(regions: List<DetectedRegion>): List<DetectedRegion> =
        regions.filter { it.label == OnnxBubbleDetector.LABEL_TEXT_FREE }

    /**
     * Union of several boxes, normalized. Returns null for an empty input.
     */
    fun unionBox(boxes: List<List<Float>>): List<Float>? {
        val valid = boxes.filter { it.size == 4 }
        if (valid.isEmpty()) return null
        return listOf(
            valid.minOf { it[0] },
            valid.minOf { it[1] },
            valid.maxOf { it[2] },
            valid.maxOf { it[3] }
        )
    }

    /** True when the centre of [inner] falls inside [outer]. */
    fun containsCenter(outer: List<Float>, inner: List<Float>): Boolean {
        if (outer.size != 4 || inner.size != 4) return false
        val cx = (inner[0] + inner[2]) / 2f
        val cy = (inner[1] + inner[3]) / 2f
        return cx >= outer[0] && cx <= outer[2] && cy >= outer[1] && cy <= outer[3]
    }

    /** Intersection-over-union of two normalized xyxy boxes. */
    fun iou(a: List<Float>, b: List<Float>): Float {
        if (a.size != 4 || b.size != 4) return 0f
        val ix1 = maxOf(a[0], b[0])
        val iy1 = maxOf(a[1], b[1])
        val ix2 = minOf(a[2], b[2])
        val iy2 = minOf(a[3], b[3])
        val iw = ix2 - ix1
        val ih = iy2 - iy1
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val areaA = (a[2] - a[0]) * (a[3] - a[1])
        val areaB = (b[2] - b[0]) * (b[3] - b[1])
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    /**
     * Suppress near-duplicate detections, keeping the highest-scoring of each cluster.
     * DETR-family models are set-prediction and do not require NMS, but INT8 quantization
     * can produce paired boxes for one region that would otherwise double-wipe a bubble.
     */
    fun suppressDuplicates(
        regions: List<DetectedRegion>,
        iouThreshold: Float = 0.80f
    ): List<DetectedRegion> {
        val ordered = Collections.unmodifiableList(regions.sortedByDescending { it.score })
        val kept = ArrayList<DetectedRegion>()
        for (candidate in ordered) {
            val duplicate = kept.any {
                it.label == candidate.label && iou(it.box, candidate.box) >= iouThreshold
            }
            if (!duplicate) kept += candidate
        }
        return kept
    }
}
