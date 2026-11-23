package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.models.Bubble
import com.example.pipeline.wipe.InpaintModelManager
import com.example.pipeline.wipe.MaskWiper
import com.example.pipeline.wipe.WipeTier
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Render proof for the Step 1 mask wipe: drives the PRODUCTION
 * [MaskWiper] (not a re-implementation) over a real page with the
 * accepted detector boxes and writes the wiped page for crop judgement.
 *
 * Args (-e): page, boxes, tier (STANDARD|BEST), out
 * For BEST, the LaMa model must already sit in the app's
 * files/models/inpaint/ (the test copies an optional -e lama path there).
 */
@RunWith(AndroidJUnit4::class)
class MaskWipeRenderTest {

    @Test
    fun renderMaskWipedPage() {
        val args = InstrumentationRegistry.getArguments()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pagePath = args.getString("page") ?: error("no -e page")
        val boxesPath = args.getString("boxes") ?: error("no -e boxes")
        val tierName = args.getString("tier") ?: "STANDARD"
        val outDir = File(args.getString("out") ?: error("no -e out")).apply { mkdirs() }

        args.getString("lama")?.let { lamaPath ->
            val manager = InpaintModelManager.getInstance(ctx)
            manager.modelDir.mkdirs()
            File(lamaPath).copyTo(manager.modelFile, overwrite = true)
        }

        val bmp = BitmapFactory.decodeFile(pagePath) ?: error("cannot decode $pagePath")
        val bubbles = File(boxesPath).readLines().filter { it.isNotBlank() }.mapIndexed { i, line ->
            val f = line.split(",").map { it.trim().toFloat() }
            Bubble(id = i + 1, text = "テスト", box = listOf(f[0], f[1], f[2], f[3]), type = "bubble")
        }
        val t0 = System.currentTimeMillis()
        val wiped = runBlocking {
            MaskWiper.wipeBubbles(ctx, bmp, bubbles, WipeTier.valueOf(tierName))
        } ?: error("MaskWiper returned null (mask path cannot run)")
        val ms = System.currentTimeMillis() - t0
        FileOutputStream(File(outDir, "wiped_${tierName.lowercase()}.png")).use {
            wiped.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        File(outDir, "result.txt").writeText(
            "page=${File(pagePath).name} tier=$tierName ms=$ms boxes=${bubbles.size} size=${bmp.width}x${bmp.height}\n"
        )
        println("[maskwipe] tier=$tierName ms=$ms")
        assertTrue(File(outDir, "wiped_${tierName.lowercase()}.png").length() > 0)
    }
}
