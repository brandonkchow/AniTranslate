package com.example.pipeline.wipe

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.data.models.Bubble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Learned-mask wipe: the Step 1 integration of the proven CTD mask
 * (prompt 20261118). Replaces [FlatWiper]'s component rule as the
 * pipeline's wipe when it can run; the caller falls back to FlatWiper
 * when the CTD model cannot load or segment, so a model failure degrades
 * to the previous behaviour instead of failing the page.
 *
 * Both tiers share the same mask and the same composite contract:
 * original everywhere, fill only inside the erase mask.
 *  - STANDARD: nearest-surviving-colour fill ([MaskComposite]).
 *  - BEST: LaMa fill ([LamaFiller]) when the optional model is
 *    downloaded; otherwise Standard, logged.
 */
object MaskWiper {
    private const val TAG = "MaskWiper"

    /** The tier a wipe will actually use, for logs and render proofs. */
    fun resolvedTier(context: Context): WipeTier {
        val manager = InpaintModelManager.getInstance(context)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return WipeTierStore.resolve(
            WipeTierStore.mode(context),
            info.totalMem / (1024 * 1024),
            manager.isDownloaded
        )
    }

    /**
     * Wipe [sourceBitmap] for [bubbles]. Returns null when the mask path
     * cannot run at all (model load or segmentation failure); a null
     * means "fall back", never a partially wiped page.
     */
    suspend fun wipeBubbles(
        context: Context,
        sourceBitmap: Bitmap,
        bubbles: List<Bubble>,
        tierOverride: WipeTier? = null
    ): Bitmap? = withContext(Dispatchers.Default) {
        val ctd = CtdTextMask.getInstance(context) ?: return@withContext null
        val seg = ctd.segment(sourceBitmap) ?: return@withContext null
        val w = sourceBitmap.width; val h = sourceBitmap.height
        val active = bubbles.filter { it.visible && !it.isNonBalloon }
        if (active.isEmpty()) return@withContext sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val boxes = active.map { floatArrayOf(it.x1, it.y1, it.x2, it.y2) }
        val erase = MaskComposite.eraseMask(seg, w, h, boxes)
        if (erase.none { it }) return@withContext sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)

        val tier = tierOverride ?: resolvedTier(context)
        if (tier == WipeTier.BEST) {
            val manager = InpaintModelManager.getInstance(context)
            val lama = LamaFiller.getInstance(manager.modelFile)
            if (lama != null) {
                lama.fill(sourceBitmap, erase, boxes)?.let { return@withContext it }
                Log.w(TAG, "Best tier: LaMa fill returned null, falling back to nearest")
            } else {
                Log.w(TAG, "Best tier requested but LaMa model absent, using Standard fill")
            }
        }

        val px = IntArray(w * h)
        sourceBitmap.getPixels(px, 0, w, 0, 0, w, h)
        val out = MaskComposite.compositeNearestWH(px, w, h, erase)
        Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
