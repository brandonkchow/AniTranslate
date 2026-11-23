package com.example.pipeline.wipe

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * Best-tier fill: LaMa inpainting over the erase mask, per detector box.
 *
 * Each box is cropped with context (the box grown by [CONTEXT] on each
 * side, clipped to the page), the crop and the erase mask are resized to
 * LaMa's fixed 512x512, the model redraws the masked area, and the result
 * is composited back ONLY where the erase mask is set — pixels outside
 * the mask stay bit-identical to the original (the contract in
 * [MaskComposite]). Returns null if the model is absent or fails, so the
 * caller falls back to the Standard nearest fill.
 */
class LamaFiller private constructor(private val session: OrtSession) {

    fun fill(page: Bitmap, erase: BooleanArray, boxes: List<FloatArray>): Bitmap? {
        val w = page.width; val h = page.height
        if (w <= 0 || h <= 0) return null
        return try {
            val result = page.copy(Bitmap.Config.ARGB_8888, true)
            val pagePx = IntArray(w * h); page.getPixels(pagePx, 0, w, 0, 0, w, h)
            val env = OrtEnvironment.getEnvironment()
            val inNames = session.inputNames.toList()
            for (b in boxes) {
                val bx1 = (b[0] * w).toInt(); val by1 = (b[1] * h).toInt()
                val bx2 = (b[2] * w).toInt(); val by2 = (b[3] * h).toInt()
                var any = false
                for (y in by1 until by2.coerceAtMost(h)) for (x in bx1 until bx2.coerceAtMost(w)) if (erase[y * w + x]) { any = true; break }
                if (!any) continue
                val bw = (bx2 - bx1).coerceAtLeast(1); val bh = (by2 - by1).coerceAtLeast(1)
                val cx1 = (bx1 - bw * CONTEXT).roundToInt().coerceIn(0, w - 1)
                val cy1 = (by1 - bh * CONTEXT).roundToInt().coerceIn(0, h - 1)
                val cx2 = (bx2 + bw * CONTEXT).roundToInt().coerceIn(cx1 + 1, w)
                val cy2 = (by2 + bh * CONTEXT).roundToInt().coerceIn(cy1 + 1, h)
                val cw = cx2 - cx1; val ch = cy2 - cy1

                val crop = Bitmap.createBitmap(page, cx1, cy1, cw, ch)
                val crop512 = Bitmap.createScaledBitmap(crop, SIZE, SIZE, true)
                val cpx = IntArray(SIZE * SIZE); crop512.getPixels(cpx, 0, SIZE, 0, 0, SIZE, SIZE)
                val img = FloatArray(3 * SIZE * SIZE)
                val msk = FloatArray(SIZE * SIZE)
                for (y in 0 until SIZE) for (x in 0 until SIZE) {
                    val p = cpx[y * SIZE + x]; val o = y * SIZE + x
                    // This LaMa export takes the image normalized to 0..1 and
                    // returns 0..255 (verified on desktop against the raw-255
                    // feed, 2026-11-22): feeding 0..255 saturates most of the
                    // crop to white but blows isolated erased pixels out to
                    // (255,x,255) / (255,255,x) specks — the pink/yellow dots
                    // the Commander failed the Step 1 set for.
                    img[o] = Color.red(p) / 255f; img[SIZE * SIZE + o] = Color.green(p) / 255f; img[2 * SIZE * SIZE + o] = Color.blue(p) / 255f
                    // Map the 512 cell back to the page erase mask.
                    val px = cx1 + (x * cw / SIZE); val py = cy1 + (y * ch / SIZE)
                    msk[o] = if (px in 0 until w && py in 0 until h && erase[py * w + px]) 1f else 0f
                }
                val outArr = OnnxTensor.createTensor(env, FloatBuffer.wrap(img), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { imgT ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(msk), longArrayOf(1, 1, SIZE.toLong(), SIZE.toLong())).use { mskT ->
                        val inputs = if (inNames.size >= 2) mapOf(inNames[0] to imgT, inNames[1] to mskT)
                            else mapOf(inNames[0] to imgT)
                        session.run(inputs).use { res ->
                            val t = res.get(0) as? OnnxTensor ?: return null
                            val buf = FloatArray(3 * SIZE * SIZE); t.floatBuffer.get(buf); buf
                        }
                    }
                } ?: continue
                // Back to crop size, then composite only erase pixels.
                val out512 = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
                out512.setPixels(IntArray(SIZE * SIZE) { i ->
                    val r = outArr[i].roundToInt().coerceIn(0, 255)
                    val g = outArr[SIZE * SIZE + i].roundToInt().coerceIn(0, 255)
                    val bl = outArr[2 * SIZE * SIZE + i].roundToInt().coerceIn(0, 255)
                    Color.rgb(r, g, bl)
                }, 0, SIZE, 0, 0, SIZE, SIZE)
                val outCrop = Bitmap.createScaledBitmap(out512, cw, ch, true)
                val opx = IntArray(cw * ch); outCrop.getPixels(opx, 0, cw, 0, 0, cw, ch)
                val resPx = IntArray(w * h); result.getPixels(resPx, 0, w, 0, 0, w, h)
                for (y in 0 until ch) for (x in 0 until cw) {
                    val gx = cx1 + x; val gy = cy1 + y
                    if (erase[gy * w + gx]) resPx[gy * w + gx] = opx[y * cw + x]
                }
                result.setPixels(resPx, 0, w, 0, 0, w, h)
            }
            result
        } catch (t: Throwable) {
            Log.e(TAG, "LaMa fill failed: ${t.message}", t)
            null
        }
    }

    companion object {
        private const val TAG = "LamaFiller"
        private const val SIZE = 512
        private const val CONTEXT = 0.5f

        @Volatile private var instance: LamaFiller? = null

        fun getInstance(file: File): LamaFiller? {
            if (!file.exists()) return null
            instance?.let { return it }
            return synchronized(this) {
                instance ?: try {
                    val opts = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(4)
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    }
                    LamaFiller(OrtEnvironment.getEnvironment().createSession(file.absolutePath, opts))
                        .also { instance = it }
                } catch (t: Throwable) {
                    Log.e(TAG, "LaMa load failed: ${t.message}", t)
                    null
                }
            }
        }
    }
}
