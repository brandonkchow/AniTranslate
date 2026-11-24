package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.db.AppDatabase
import com.example.data.db.JobEntity
import com.example.data.db.PageEntity
import com.example.data.db.PageStatus
import com.example.data.slots.ApiProvider
import com.example.data.slots.ApiSlot
import com.example.data.slots.SlotRole
import com.example.data.slots.SlotStorage
import com.example.pipeline.JobManager
import com.example.utils.RunLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Whole-chapter end-to-end proof through the host proxy (first page -> rendered output).
 *
 * One job over a real chapter directory rides detect -> translate -> wipe -> typeset via
 * [JobManager.startOrResumeJob], asserting per-page terminal status only. Provider-agnostic:
 * structure (statuses, bubbles present, output files exist) is asserted, never exact strings.
 *
 * PM-editable model slugs live in the companion object below — change them there, nowhere else.
 *
 * Instrumentation args:
 *  - `chapterDir`: absolute path on device storage holding page images. Absent -> single
 *    bundled/synthesised fixture page.
 *  - `proxyBaseUrl`: overrides [PROXY_BASE_URL] (negative control: pass a dead URL such as
 *    `http://10.0.2.2:1`; the test must then FAIL with non-terminal / no-DONE pages).
 *  - `e2eTimeoutMs`: overrides the page-count-scaled poll deadline (negative control: short value).
 */
@RunWith(AndroidJUnit4::class)
class ChapterProxyE2eTest {

    companion object {
        private const val TAG = "ChapterProxyE2eTest"

        /** Host proxy as seen from the emulator. PM: raise the proxy `--call-cap` for a 55-page run. */
        const val PROXY_BASE_URL = "http://10.0.2.2:8799"

        /** Proxy auth only — never a real provider key. */
        const val PROXY_API_KEY = "bulcan-local"

        /** DETECT_OCR vision slot (OPENROUTER / OpenAI-compatible shape through the proxy). */
        const val DETECT_MODEL = "nvidia/nemotron-3.5-super-vl:free"

        /**
         * Vision-capable fallback slot. Seeded CUSTOM (OpenAI-compatible) so it rides the proxy's
         * `/chat/completions`; if the proxy catalogue spells it `google/gemini-2.5-flash`, edit here.
         */
        const val FALLBACK_VISION_MODEL = "gemini-2.5-flash"

        /** TRANSLATE slot: text slug from the catalogue (Groq shape, served through the proxy). */
        const val TRANSLATE_MODEL = "openai/gpt-oss-120b"

        private const val POLL_INTERVAL_MS = 5_000L
        private const val PER_PAGE_BUDGET_MS = 90_000L
        private const val MIN_TIMEOUT_MS = 10 * 60_000L
        private const val MAX_TIMEOUT_MS = 120 * 60_000L
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val TERMINAL_JOB_STATUSES = setOf("COMPLETED", "PARTIAL_SUCCESS", "FAILED")
    }

    @Test
    fun chapterRidesPipelineToRenderedOutputThroughProxy() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val proxyBaseUrl = args.getString("proxyBaseUrl")?.takeIf { it.isNotBlank() } ?: PROXY_BASE_URL

        val slotStorage = SlotStorage.getInstance(context)
        val originalSlots = slotStorage.loadSlots()
        seedProxySlots(slotStorage, proxyBaseUrl)
        try {
            runChapter(context, slotStorage, proxyBaseUrl)
        } finally {
            slotStorage.saveSlots(originalSlots)
        }
    }

    private fun seedProxySlots(slotStorage: SlotStorage, proxyBaseUrl: String) {
        val slots = listOf(
            ApiSlot(
                provider = ApiProvider.OPENROUTER,
                label = "Proxy DETECT_OCR: Nemotron 3.5 Super VL (free)",
                apiKey = PROXY_API_KEY,
                model = DETECT_MODEL,
                baseUrl = proxyBaseUrl,
                role = SlotRole.DETECT_OCR,
                enabled = true,
                walkFreeModels = false
            ),
            ApiSlot(
                provider = ApiProvider.CUSTOM,
                label = "Proxy fallback vision: Gemini 2.5 Flash",
                apiKey = PROXY_API_KEY,
                model = FALLBACK_VISION_MODEL,
                baseUrl = proxyBaseUrl,
                role = SlotRole.DETECT_OCR,
                enabled = true,
                walkFreeModels = false
            ),
            ApiSlot(
                provider = ApiProvider.GROQ,
                label = "Proxy TRANSLATE: GPT-OSS 120B",
                apiKey = PROXY_API_KEY,
                model = TRANSLATE_MODEL,
                baseUrl = proxyBaseUrl,
                role = SlotRole.TRANSLATE,
                enabled = true,
                walkFreeModels = false
            )
        )
        slotStorage.saveSlots(slots)
        Log.i(TAG, "Seeded ${slots.size} proxy slots at $proxyBaseUrl: " +
            slots.joinToString { "${it.role}/${it.model}" })
    }

    private suspend fun runChapter(
        context: android.content.Context,
        slotStorage: SlotStorage,
        proxyBaseUrl: String
    ) {
        val db = AppDatabase.getInstance(context)
        val dao = db.jobDao()

        // --- Collect page bytes: chapterDir arg, else one fixture page. ---
        val args = InstrumentationRegistry.getArguments()
        val chapterDir = args.getString("chapterDir")?.takeIf { it.isNotBlank() }
        val pageSources: List<Pair<String, ByteArray>> = if (chapterDir != null) {
            val dir = File(chapterDir)
            assertTrue("chapterDir must be a readable directory: $chapterDir", dir.isDirectory)
            val files = dir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
                ?.sortedBy { it.name }
                .orEmpty()
            assertTrue("chapterDir holds no page images: $chapterDir", files.isNotEmpty())
            files.map { it.name to it.readBytes() }
        } else {
            listOf("fixture_page.png" to fixturePageBytes(context))
        }
        Log.i(TAG, "Chapter source: ${chapterDir ?: "<fixture>"} (${pageSources.size} page(s)) via $proxyBaseUrl")

        // --- Enqueue ONE job through the app's own job-creation seam ---
        // Same seam HomeViewModel.startTranslationJob uses: JobEntity + cacheDir/job_<id>/orig_*
        // files + PageEntity(QUEUED) rows via JobDao, then JobManager.startOrResumeJob.
        val jobId = dao.insertJob(JobEntity(pageCount = pageSources.size, status = "RUNNING", targetLang = "en"))
        val pagesDir = File(context.cacheDir, "job_$jobId").apply { mkdirs() }
        val pageEntities = pageSources.mapIndexed { index, (name, bytes) ->
            val ext = name.substringAfterLast('.', "png").lowercase().takeIf { it in IMAGE_EXTENSIONS } ?: "png"
            val origFile = File(pagesDir, "orig_${index + 1}.$ext")
            origFile.writeBytes(bytes)
            PageEntity(
                jobId = jobId,
                pageIndex = index,
                originalImageUri = "file://${origFile.absolutePath}",
                originalCachePath = origFile.absolutePath,
                status = PageStatus.QUEUED
            )
        }
        dao.insertPages(pageEntities)

        val timeoutMs = args.getString("e2eTimeoutMs")?.toLongOrNull()
            ?: (pageSources.size * PER_PAGE_BUDGET_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        Log.i(TAG, "Enqueued job #$jobId with ${pageSources.size} page(s); poll deadline ${timeoutMs}ms")

        JobManager(context, dao, slotStorage).startOrResumeJob(jobId)

        // --- Poll real DAO until terminal or deadline. ---
        val deadline = System.currentTimeMillis() + timeoutMs
        var job = dao.getJobByIdDirect(jobId)
        var pages = dao.getPagesForJobDirect(jobId)
        while (System.currentTimeMillis() < deadline) {
            job = dao.getJobByIdDirect(jobId)
            pages = dao.getPagesForJobDirect(jobId)
            val allTerminal = pages.isNotEmpty() && pages.all { it.status.isTerminal }
            if (allTerminal && job?.status in TERMINAL_JOB_STATUSES) break
            // Terminal pages alone are not enough while the batch loop still runs, but once
            // every page is terminal JobManager finalises the job status on its next tick.
            if (allTerminal && job?.status != "RUNNING") break
            delay(POLL_INTERVAL_MS)
        }
        job = dao.getJobByIdDirect(jobId)
        pages = dao.getPagesForJobDirect(jobId)

        // --- Per-stage summary + run-log location for PM failure classification. ---
        val byStatus = pages.groupingBy { it.status }.eachCount()
        val byStage = pages.groupingBy { it.lastStageAttempted.ifBlank { "(none)" } }.eachCount()
        val runLogPath = RunLogger.getRecentRuns(context).firstOrNull { it.jobId == jobId }?.filePath
            ?: RunLogger.getActiveRunId(jobId)?.let { File(context.filesDir, "run_logs/$it.log").absolutePath }
            ?: "(no run log found)"
        val summary = buildString {
            append("job #").append(jobId).append(" status=").append(job?.status)
            append(" pages=").append(pages.size)
            append(" byStatus=").append(byStatus)
            append(" byLastStage=").append(byStage)
            append(" done=").append(byStatus[PageStatus.DONE] ?: 0)
            append(" failed=").append(byStatus[PageStatus.FAILED] ?: 0)
        }
        Log.i(TAG, summary)
        println("ChapterProxyE2eTest: $summary")
        println("ChapterProxyE2eTest: RUN_LOG_PATH=$runLogPath")
        pages.forEach { page ->
            Log.i(TAG, "page ${page.pageIndex + 1}: status=${page.status} stage=${page.lastStageAttempted} " +
                "slot=${page.activeSlotName} error=${page.errorMessage.take(200)}")
        }

        // --- Structure assertions (provider-agnostic; no exact strings). ---
        assertTrue("Job #$jobId must reach a terminal status (was ${job?.status})",
            job?.status in TERMINAL_JOB_STATUSES)
        val stuck = pages.filter { !it.status.isTerminal }
        assertTrue("Every page must reach DONE or FAILED; stuck: " +
            stuck.joinToString { "#${it.pageIndex + 1}=${it.status}" }, stuck.isEmpty())

        val donePages = pages.filter { it.status == PageStatus.DONE }
        val failedPages = pages.filter { it.status == PageStatus.FAILED }
        donePages.forEach { page ->
            assertTrue("DONE page ${page.pageIndex + 1} must have non-blank bubblesJson",
                page.bubblesJson.isNotBlank())
            val rendered = page.finalImagePath.takeIf { it.isNotBlank() }?.let { File(it) }
            assertTrue("DONE page ${page.pageIndex + 1} must have an existing rendered output file " +
                "(finalImagePath=${page.finalImagePath})",
                rendered != null && rendered.exists() && rendered.length() > 0)
        }
        failedPages.forEach { page ->
            assertTrue("FAILED page ${page.pageIndex + 1} must carry a non-blank errorMessage",
                page.errorMessage.isNotBlank())
        }
        if (donePages.isEmpty()) {
            fail("At least one page must be DONE; a 100%-failure chapter is not a pass " +
                "($summary; run log: $runLogPath)")
        }
    }

    /** Bundled asset when present, else a synthesised manga-like page (same recipe as the sample loader). */
    private fun fixturePageBytes(context: android.content.Context): ByteArray {
        try {
            context.assets.open("sample.jpg").use { return it.readBytes() }
        } catch (_: Exception) {
            // Fall through to the synthesised page.
        }
        val width = 900
        val height = 1350
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val border = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 6f }
        val fill = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        val text = Paint().apply {
            color = Color.BLACK; textSize = 30f; isAntiAlias = true; typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.drawRect(50f, 50f, width - 50f, height * 0.45f, border)
        val bubble = RectF(width * 0.62f, 70f, width * 0.92f, 300f)
        canvas.drawOval(bubble, fill)
        canvas.drawOval(bubble, border)
        canvas.drawText("うますぎ警報", bubble.left + 25f, bubble.top + 90f, text)
        canvas.drawText("発令―――！！", bubble.left + 25f, bubble.top + 150f, text)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
