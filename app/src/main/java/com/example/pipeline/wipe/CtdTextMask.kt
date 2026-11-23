package com.example.pipeline.wipe

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.nio.FloatBuffer

/**
 * CTD (comic-text-detector) segmentation model: marks letter pixels only.
 *
 * This is the learned mask that replaced the hand-written component rule
 * (closed after three measured failures, Commander 2026-11-18). The model
 * downloads on first run via [CtdModelManager] (94.7 MB full precision).
 * It is never bundled in assets. The dynamically-quantized int8
 * form is NOT used: ONNX Runtime Android refuses to load it
 * (ORT_NOT_IMPLEMENTED, ConvInteger) — proven in the 2026-11-22 emulator
 * proof, rendered-pages/mask-wipe-step0-20261122/STEP0.md.
 *
 * Method matches the accepted desktop proof exactly: fit the page into a
 * 1024x1024 black canvas, take the `seg` output, bilinear-resize to page
 * size, threshold 0.5 (see [MaskComposite]).
 */
class CtdTextMask private constructor(private val session: OrtSession) {

    /** Segmentation mask at page size, or null on any failure. */
    fun segment(bitmap: Bitmap): BooleanArray? {
        val w = bitmap.width; val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        return try {
            val scale = 1024f / maxOf(w, h)
            val nw = (w * scale).toInt().coerceAtLeast(1); val nh = (h * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
            val spx = IntArray(nw * nh); scaled.getPixels(spx, 0, nw, 0, 0, nw, nh)
            val input = FloatArray(3 * 1024 * 1024)
            for (y in 0 until nh) for (x in 0 until nw) {
                val p = spx[y * nw + x]; val o = y * 1024 + x
                input[o] = Color.red(p) / 255f
                input[1024 * 1024 + o] = Color.green(p) / 255f
                input[2 * 1024 * 1024 + o] = Color.blue(p) / 255f
            }
            val env = OrtEnvironment.getEnvironment()
            val inName = session.inputNames.first()
            val seg = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, 1024, 1024)).use { tensor ->
                session.run(mapOf(inName to tensor)).use { results ->
                    val names = session.outputNames.toList()
                    val idx = names.indexOf("seg").takeIf { it >= 0 }
                        ?: (0 until results.size()).firstOrNull { i ->
                            val t = results.get(i) as? OnnxTensor
                            t != null && t.info.shape.size == 4 && t.info.shape[2] == 1024L
                        } ?: return null
                    val tensorOut = results.get(idx) as? OnnxTensor ?: return null
                    val buf = FloatArray(1024 * 1024)
                    tensorOut.floatBuffer.get(buf)
                    buf
                }
            } ?: return null
            val small = FloatArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) small[y * nw + x] = seg[y * 1024 + x]
            MaskComposite.thresholdResize(small, nw, nh, w, h)
        } catch (t: Throwable) {
            Log.e(TAG, "CTD segmentation failed: ${t.message}", t)
            null
        }
    }

    companion object {
        private const val TAG = "CtdTextMask"

        @Volatile private var instance: CtdTextMask? = null

        /**
         * Session over the downloaded model file, or null when the file is
         * missing, unreadable, or corrupt. Failures are never cached, so a
         * later download can still succeed in the same process.
         */
        fun getInstance(context: Context): CtdTextMask? {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: try {
                    val bytes = CtdModelManager.getInstance(context).modelFile.readBytes()
                    val opts = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(4)
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    }
                    val session = OrtEnvironment.getEnvironment().createSession(bytes, opts)
                    CtdTextMask(session).also { instance = it }
                } catch (t: Throwable) {
                    Log.e(TAG, "CTD model load failed: ${t.message}", t)
                    null
                }
            }
        }

        /** Drops the cached session so a re-downloaded file is never stale. */
        fun invalidate() {
            synchronized(this) {
                try { instance?.close() } catch (_: Throwable) { }
                instance = null
            }
        }
    }

    private fun close() {
        try { session.close() } catch (_: Throwable) { }
    }
}
