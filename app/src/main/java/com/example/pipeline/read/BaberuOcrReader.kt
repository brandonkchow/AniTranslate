package com.example.pipeline.read

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * On-device Baberu OCR reader using three ONNX Runtime sessions:
 *   1. `vision_int4.onnx`: `pixel_values[1,3,224,224]` -> `vision_embeds[1,256,512]`
 *   2. `decoder_prefill_int8.onnx`: `vision_embeds`, `input_ids[1,1]` -> `logits[1,257,14630]` + `present_k0..5`/`present_v0..5`
 *   3. `decoder_step_int8.onnx`: `input_ids[1,1]`, `position_ids[1,1]`, `past_k0..5`, `past_v0..5` -> `logits[1,1,14630]` + `present_*`
 *
 * Failures always throw (never return silent `""`).
 */
class BaberuOcrReader(private val modelManager: BaberuModelManager) {

    companion object {
        private const val TAG = "BaberuOcrReader"
        const val INPUT_SIZE = 224
        private const val NUM_LAYERS = 6

        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        private val PAST_NAMES =
            (0 until NUM_LAYERS).map { "past_k$it" } + (0 until NUM_LAYERS).map { "past_v$it" }
        private val PRESENT_NAMES =
            (0 until NUM_LAYERS).map { "present_k$it" } + (0 until NUM_LAYERS).map { "present_v$it" }
    }

    private data class KvBuffer(val data: FloatArray, val shape: LongArray)

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var visionSession: OrtSession? = null
    private var prefillSession: OrtSession? = null
    private var stepSession: OrtSession? = null
    private var vocab: BaberuDecode.Vocab? = null

    @Volatile
    var isLoaded: Boolean = false
        private set

    @Synchronized
    fun load() {
        if (isLoaded) return
        val visionFile = modelManager.visionFile
        val prefillFile = modelManager.prefillFile
        val stepFile = modelManager.stepFile
        val vocabFile = modelManager.vocabFile

        if (!visionFile.exists() || !prefillFile.exists() || !stepFile.exists() || !vocabFile.exists()) {
            throw IllegalStateException("Baberu OCR model files are not downloaded. Download them in Settings.")
        }

        val loadedVocab = BaberuDecode.Vocab.fromJsonString(vocabFile.readText(Charsets.UTF_8))

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        var vSess: OrtSession? = null
        var pSess: OrtSession? = null
        var sSess: OrtSession? = null
        try {
            vSess = env.createSession(visionFile.absolutePath, opts)
            pSess = env.createSession(prefillFile.absolutePath, opts)
            sSess = env.createSession(stepFile.absolutePath, opts)

            visionSession = vSess
            prefillSession = pSess
            stepSession = sSess
            vocab = loadedVocab
            isLoaded = true
            Log.i(TAG, "Loaded Baberu OCR sessions (vocabSize=${loadedVocab.vocabSize})")
        } catch (t: Throwable) {
            try { vSess?.close() } catch (_: Throwable) {}
            try { pSess?.close() } catch (_: Throwable) {}
            try { sSess?.close() } catch (_: Throwable) {}
            isLoaded = false
            throw t
        }
    }

    fun read(crop: Bitmap): String {
        if (crop.width <= 0 || crop.height <= 0) {
            throw IllegalArgumentException("Cannot OCR empty crop (${crop.width}x${crop.height})")
        }
        if (!isLoaded) {
            load()
        }
        val vSess = visionSession ?: throw IllegalStateException("Vision session not loaded")
        val pSess = prefillSession ?: throw IllegalStateException("Prefill session not loaded")
        val sSess = stepSession ?: throw IllegalStateException("Step session not loaded")
        val activeVocab = vocab ?: throw IllegalStateException("Vocab not loaded")

        val pixelInput = preprocess(crop)
        val (visionData, visionShape) = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(pixelInput),
            longArrayOf(1L, 3L, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        ).use { pixelTensor ->
            vSess.run(mapOf("pixel_values" to pixelTensor)).use { vOut ->
                val vVal = vOut.get("vision_embeds").orElseThrow {
                    IllegalStateException("Missing vision_embeds output")
                } as OnnxTensor
                extractFloatTensor(vVal)
            }
        }

        val visionSeqLen = if (visionShape.size >= 2) visionShape[1].toInt() else 256

        var kvCache: List<KvBuffer> = emptyList()
        val initialLogits: DoubleArray = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(visionData),
            visionShape
        ).use { visionTensor ->
            OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(longArrayOf(activeVocab.bos.toLong())),
                longArrayOf(1L, 1L)
            ).use { bosTensor ->
                pSess.run(
                    mapOf("vision_embeds" to visionTensor, "input_ids" to bosTensor)
                ).use { pOut ->
                    val logitsTensor = pOut.get("logits").orElseThrow {
                        IllegalStateException("Missing logits output from prefill")
                    } as OnnxTensor
                    val lastLogits = extractLastStepLogitsAsDouble(logitsTensor)
                    kvCache = extractKvCache(pOut)
                    lastLogits
                }
            }
        }

        val tokens = BaberuDecode.decodeLoop(
            initialLogits = initialLogits,
            visionSeqLen = visionSeqLen,
            vocab = activeVocab
        ) { nextTokenId, pos ->
            val createdTensors = ArrayList<OnnxTensor>(2 + PAST_NAMES.size)
            try {
                val inputIdTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(longArrayOf(nextTokenId.toLong())),
                    longArrayOf(1L, 1L)
                ).also { createdTensors.add(it) }

                val posTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(longArrayOf(pos.toLong())),
                    longArrayOf(1L, 1L)
                ).also { createdTensors.add(it) }

                val feed = HashMap<String, OnnxTensor>(2 + PAST_NAMES.size)
                feed["input_ids"] = inputIdTensor
                feed["position_ids"] = posTensor

                for (i in PAST_NAMES.indices) {
                    val kv = kvCache[i]
                    val pastTensor = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(kv.data),
                        kv.shape
                    ).also { createdTensors.add(it) }
                    feed[PAST_NAMES[i]] = pastTensor
                }

                sSess.run(feed).use { sOut ->
                    val logitsTensor = sOut.get("logits").orElseThrow {
                        IllegalStateException("Missing logits output from step")
                    } as OnnxTensor
                    val nextLogits = extractLastStepLogitsAsDouble(logitsTensor)
                    kvCache = extractKvCache(sOut)
                    nextLogits
                }
            } finally {
                for (t in createdTensors) {
                    try { t.close() } catch (_: Throwable) {}
                }
            }
        }

        return activeVocab.decode(tokens)
    }

    @Synchronized
    fun close() {
        try { visionSession?.close() } catch (_: Throwable) {}
        try { prefillSession?.close() } catch (_: Throwable) {}
        try { stepSession?.close() } catch (_: Throwable) {}
        visionSession = null
        prefillSession = null
        stepSession = null
        vocab = null
        isLoaded = false
    }

    /**
     * Resize whole crop to 224x224, normalize with ImageNet mean/std, layout NCHW float32.
     */
    private fun preprocess(bitmap: Bitmap): FloatArray {
        val rgbBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        val scaled = Bitmap.createScaledBitmap(rgbBitmap, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== rgbBitmap) scaled.recycle()
        if (rgbBitmap !== bitmap) rgbBitmap.recycle()

        val plane = INPUT_SIZE * INPUT_SIZE
        val out = FloatArray(3 * plane)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            out[i] = (r - MEAN[0]) / STD[0]
            out[plane + i] = (g - MEAN[1]) / STD[1]
            out[2 * plane + i] = (b - MEAN[2]) / STD[2]
        }
        return out
    }

    private fun extractFloatTensor(tensor: OnnxTensor): Pair<FloatArray, LongArray> {
        val shape = tensor.info.shape
        val fb = tensor.floatBuffer
        val arr = FloatArray(fb.remaining())
        fb.get(arr)
        return arr to shape
    }

    private fun extractLastStepLogitsAsDouble(logitsTensor: OnnxTensor): DoubleArray {
        val shape = logitsTensor.info.shape
        // Expected shape: [1, seqLen, vocabSize]
        val seqLen = if (shape.size >= 2) shape[1].toInt() else 1
        val vocabSize = shape.last().toInt()
        val fb = logitsTensor.floatBuffer
        val total = fb.remaining()
        val offset = ((seqLen - 1) * vocabSize).coerceIn(0, (total - vocabSize).coerceAtLeast(0))
        fb.position(offset)
        val out = DoubleArray(vocabSize)
        for (i in 0 until vocabSize) {
            out[i] = fb.get().toDouble()
        }
        return out
    }

    private fun extractKvCache(result: OrtSession.Result): List<KvBuffer> {
        val list = ArrayList<KvBuffer>(PRESENT_NAMES.size)
        for (name in PRESENT_NAMES) {
            val tensor = result.get(name).orElseThrow {
                IllegalStateException("Missing KV output: $name")
            } as OnnxTensor
            val (data, shape) = extractFloatTensor(tensor)
            list.add(KvBuffer(data, shape))
        }
        return list
    }
}
