package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.db.PageEntity
import com.example.data.db.PageStatus
import com.example.data.slots.ApiProvider
import com.example.data.slots.ApiSlot
import com.example.data.slots.SlotRole
import com.example.data.slots.SlotStorage
import com.example.net.GeminiClient
import com.example.net.OpenAiCompatibleClient
import com.example.pipeline.PagePipeline
import com.example.pipeline.read.BaberuModelManager
import com.example.pipeline.router.KeyRouter
import com.example.pipeline.typeset.Typesetter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Render proof driver (wuwei only): runs the 11 GT clean pages through the real
 * PagePipeline on this emulator. Translation slot points at a stub server on the
 * host (10.0.2.2:18712) that serves bench Gemini control translations matched by
 * Japanese text, with live agy fallback. No Gemini API key is used anywhere.
 * Finished pages are copied to external files dir for adb pull before teardown.
 */
@RunWith(AndroidJUnit4::class)
class PageRenderProofInstrumentedTest {

    companion object { private const val TAG = "PageRenderProof" }

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx: Context get() = InstrumentationRegistry.getInstrumentation().context

    @Test(timeout = 60L * 60L * 1000L)
    fun renderAll11PagesThroughAppPipeline() {
        val manager = BaberuModelManager.getInstance(ctx)
        runBlocking {
            if (!manager.verifyAll()) {
                assertTrue("model download failed: ${manager.state.value}", manager.downloadAll())
            }
        }

        SlotStorage.getInstance(ctx).saveSlots(listOf(
            ApiSlot(
                provider = ApiProvider.GEMINI, label = "Stub (bench Gemini)",
                apiKey = "stub", model = "stub-gemini",
                baseUrl = "http://10.0.2.2:18712/v1", role = SlotRole.TRANSLATE,
                enabled = true, walkFreeModels = false
            )
        ))

        val outDir = File(ctx.getExternalFilesDir(null), "anitranslate_out").apply { mkdirs() }
        val allPages = testCtx.assets.list("baberu_gt/clean")?.sorted().orEmpty()
        // Optional subset for warm reruns: -e renderPages page-05,page-09
        val filter = InstrumentationRegistry.getArguments().getString("renderPages")
        val pages = if (filter.isNullOrBlank()) allPages else allPages.filter { p -> filter.split(",").any { p.contains(it.trim()) } }
        assertEquals("expected 11 clean pages", 11, allPages.size)

        val pipeline = PagePipeline(ctx, KeyRouter(SlotStorage.getInstance(ctx)), GeminiClient(), OpenAiCompatibleClient())
        val summary = JSONArray()
        val layoutDumpFile = File(outDir, "layout_dump.txt")
        if (filter.isNullOrBlank()) {
            layoutDumpFile.writeText("")
        }

        for (name in pages) {
            // Page identity comes from the file name, never from position in a
            // (possibly filtered) subset — a subset rerun must not renumber pages
            // (BACKLOG:11 root cause in this driver).
            val pageNum = Regex("page-(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
                ?: throw AssertionError("cannot parse page number from $name")
            val pageTag = "page-${pageNum.toString().padStart(2, '0')}"
            val src = File(ctx.filesDir, "render_in_$name")
            testCtx.assets.open("baberu_gt/clean/$name").use { input ->
                src.outputStream().use { input.copyTo(it) }
            }
            val page = PageEntity(
                id = 900L + (pageNum - 1), jobId = 777L, pageIndex = pageNum - 1,
                originalImageUri = "", originalCachePath = src.absolutePath
            )
            val pageDecisions = linkedMapOf<Int, JSONObject>()
            Typesetter.onLayoutDecided = { bubbleId, bubbleType, decidedText, lines, hyphenated, fontSizePx, phase, lineCount, occupancy ->
                val obj = JSONObject()
                    .put("page", pageTag)
                    .put("id", bubbleId)
                    .put("type", bubbleType)
                    .put("text", decidedText)
                    .put("lines", JSONArray(lines))
                    .put("hyphenated", hyphenated)
                    .put("fontSizePx", fontSizePx.toDouble())
                    .put("phase", phase)
                    .put("lineCount", lineCount)
                    .put("occupancy", occupancy)
                synchronized(pageDecisions) {
                    pageDecisions[bubbleId] = obj
                }
            }
            val result = try {
                runBlocking { pipeline.processPage(page) { } }
            } finally {
                Typesetter.onLayoutDecided = null
            }
            val rec = JSONObject()
                .put("page", name).put("status", result.status.name)
                .put("bubbles", result.getBubbles().size)
                .put("error", result.errorMessage)
            if (result.status == PageStatus.DONE && result.finalImagePath.isNotBlank()) {
                val dest = File(outDir, name.substringBeforeLast('.') + "_translated.jpg")
                File(result.finalImagePath).copyTo(dest, overwrite = true)
                rec.put("outBytes", dest.length())
            }
            if (result.wipedImagePath.isNotBlank() && File(result.wipedImagePath).exists()) {
                val wipedDest = File(outDir, name.substringBeforeLast('.') + "_wiped.jpg")
                val bmp = BitmapFactory.decodeFile(result.wipedImagePath)
                if (bmp != null) {
                    wipedDest.outputStream().use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                    bmp.recycle()
                } else {
                    File(result.wipedImagePath).copyTo(wipedDest, overwrite = true)
                }
            }
            val sidecarSrc = File(ctx.cacheDir, "sidecar_${page.id}.json")
            if (sidecarSrc.exists()) {
                sidecarSrc.copyTo(File(outDir, "sidecar_${page.id}.json"), overwrite = true)
            }
            if (pageDecisions.isNotEmpty()) {
                layoutDumpFile.appendText(pageDecisions.values.joinToString("\n") { it.toString() } + "\n")
            }
            Log.i(TAG, rec.toString())
            summary.put(rec)
        }
        File(outDir, "render_summary.json").writeText(summary.toString(1))
        val done = (0 until summary.length()).count { summary.getJSONObject(it).getString("status") == "DONE" }
        Log.i(TAG, "render done count=$done of ${summary.length()}")
        assertEquals("all rendered pages DONE", pages.size, done)
    }
}
