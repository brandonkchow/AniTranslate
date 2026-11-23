package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.models.Bubble
import com.example.pipeline.detect.TextClassifier
import com.example.pipeline.wipe.InpaintModelManager
import com.example.pipeline.wipe.MaskWiper
import com.example.pipeline.wipe.WipeGate
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
 * Args (-e): page, tier (STANDARD|BEST), out, and either
 *  - bubbles: a Bubble JSON sidecar (accepted units with real OCR text,
 *    type and orientation). Classification is computed ONCE here, at the
 *    detect stage, and carried on the bubbles through the wipe - the
 *    mask path consumes it and never re-derives it (prompt 20261118).
 *  - boxes: legacy CSV x1,y1,x2,y2 lines (unclassified; every unit is
 *    treated as dialogue, kept only for old inputs).
 * For BEST, the LaMa model must already sit in the app's
 * files/models/inpaint/ (the test copies an optional -e lama path there).
 *
 * Writes gate_report.txt next to the render: one SKIP-GATE line per SKIP
 * unit (bit-identical or FAIL), COVERAGE-FLAG lines for uncovered glyph
 * components in processed units, and a presentation line - flagged units
 * block PASS and are listed as known misses for a human verdict.
 */
@RunWith(AndroidJUnit4::class)
class MaskWipeRenderTest {

    @Test
    fun renderMaskWipedPage() {
        val args = InstrumentationRegistry.getArguments()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pagePath = args.getString("page") ?: error("no -e page")
        val tierName = args.getString("tier") ?: "STANDARD"
        val outDir = File(args.getString("out") ?: error("no -e out")).apply { mkdirs() }

        args.getString("lama")?.let { lamaPath ->
            val manager = InpaintModelManager.getInstance(ctx)
            manager.modelDir.mkdirs()
            File(lamaPath).copyTo(manager.modelFile, overwrite = true)
        }

        val bmp = BitmapFactory.decodeFile(pagePath) ?: error("cannot decode $pagePath")
        val bubblesArg = args.getString("bubbles")
        val bubbles: List<Bubble> = if (!bubblesArg.isNullOrBlank()) {
            // Computed once, carried as unit state from here on.
            TextClassifier.classifyAll(Bubble.parseListFromJson(File(bubblesArg).readText()))
        } else {
            val boxesPath = args.getString("boxes") ?: error("no -e boxes or -e bubbles")
            File(boxesPath).readLines().filter { it.isNotBlank() }.mapIndexed { i, line ->
                val f = line.split(",").map { it.trim().toFloat() }
                Bubble(id = i + 1, text = "テスト", box = listOf(f[0], f[1], f[2], f[3]), type = "bubble")
            }
        }
        val t0 = System.currentTimeMillis()
        val wiped = runBlocking {
            MaskWiper.wipeBubbles(ctx, bmp, bubbles, WipeTier.valueOf(tierName))
        } ?: error("MaskWiper returned null (mask path cannot run)")
        val ms = System.currentTimeMillis() - t0
        FileOutputStream(File(outDir, "wiped_${tierName.lowercase()}.png")).use {
            wiped.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        val w = bmp.width; val h = bmp.height
        val origPx = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        var renderedPx = IntArray(w * h).also { wiped.getPixels(it, 0, w, 0, 0, w, h) }
        // Optional final: typeset the frozen translations over the wipe so
        // the verdict image shows the whole page. SKIP units are neither
        // wiped nor typeset, so the gate below runs on this final too.
        if (args.getString("typeset") == "1") {
            val finalBmp = runBlocking {
                com.example.pipeline.typeset.Typesetter.typesetBubbles(ctx, wiped, bubbles)
            }.bitmap
            FileOutputStream(File(outDir, "final_${tierName.lowercase()}.png")).use {
                finalBmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            renderedPx = IntArray(w * h).also { finalBmp.getPixels(it, 0, w, 0, 0, w, h) }
        }
        val skipLines = WipeGate.skipGateLines(origPx, renderedPx, w, h, bubbles)
        val coverage = WipeGate.coverageFlags(origPx, IntArray(w * h).also { wiped.getPixels(it, 0, w, 0, 0, w, h) }, w, h, bubbles)
        val coverageLines = WipeGate.coverageLines(coverage)
        val skipFailed = skipLines.any { it.contains("FAIL") }
        val report = buildString {
            appendLine("page=${File(pagePath).name} tier=$tierName ms=$ms boxes=${bubbles.size} size=${w}x${h}")
            bubbles.forEach { appendLine("CLASS unit ${it.id}: ${it.textClass}") }
            skipLines.forEach { appendLine(it) }
            coverageLines.forEach { appendLine(it) }
            appendLine(
                when {
                    skipFailed -> "PRESENTATION: FAIL - SKIP gate violated"
                    coverage.isNotEmpty() -> "PRESENTATION: BLOCKED - coverage flags are known misses for human verdict, not a PASS"
                    else -> "PRESENTATION: PASS-ELIGIBLE - no SKIP violations, no coverage flags"
                }
            )
        }
        File(outDir, "gate_report.txt").writeText(report)
        File(outDir, "result.txt").writeText(
            "page=${File(pagePath).name} tier=$tierName ms=$ms boxes=${bubbles.size} size=${w}x${h}\n"
        )
        println("[maskwipe] tier=$tierName ms=$ms")
        print(report)
        assertTrue(File(outDir, "wiped_${tierName.lowercase()}.png").length() > 0)
        assertTrue("SKIP pixel gate failed:\n$report", !skipFailed)
    }
}
