package com.example.pipeline

import android.content.Context
import android.graphics.Bitmap
import com.example.data.db.PageEntity
import com.example.data.db.PageStatus
import com.example.data.models.Bubble
import com.example.data.slots.ApiProvider
import com.example.data.slots.ApiSlot
import com.example.data.slots.ModelCatalogRefresher
import com.example.data.slots.ModelCatalogStore
import com.example.data.slots.ModelWalk
import com.example.data.slots.SlotRole
import com.example.data.slots.SlotStorage
import com.example.net.ApiException
import com.example.net.GeminiClient
import com.example.net.OpenAiCompatibleClient
import com.example.pipeline.detect.BubbleTextAnchor
import com.example.pipeline.detect.BubbleTextMerger
import com.example.pipeline.detect.DetectedRegion
import com.example.pipeline.detect.DetectorPostProcess
import com.example.pipeline.detect.OnnxBubbleDetector
import com.example.pipeline.image.ImageScaler
import com.example.pipeline.read.BaberuModelManager
import com.example.pipeline.read.BaberuOcrReader
import com.example.pipeline.router.KeyRouter
import com.example.pipeline.router.PipelineStage
import com.example.pipeline.typeset.Typesetter
import com.example.pipeline.wipe.FlatWiper
import com.example.utils.RunLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PagePipeline(
    private val context: Context,
    private val keyRouter: KeyRouter,
    private val geminiClient: GeminiClient,
    private val openAiClient: OpenAiCompatibleClient,
    private val maxAttempts: Int = KeyRouter.DEFAULT_MAX_ATTEMPTS
) {

    companion object {
        private val loggedRunExclusions = ConcurrentHashMap.newKeySet<String>()

        fun resetLoggedExclusions() {
            loggedRunExclusions.clear()
        }

        fun resolveGeminiOpenAiBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().removeSuffix("/")
            return if (trimmed.isBlank() || trimmed == ApiProvider.GEMINI.defaultBaseUrl) {
                "${ApiProvider.GEMINI.defaultBaseUrl}/openai/"
            } else {
                baseUrl.trim()
            }
        }

        /**
         * Page identity for derived working images (BACKLOG:11, 2026-11-14).
         * The working copy is keyed by page id AND the SHA-256 of the page's own
         * original file. Keying by id alone silently reused a previous page's
         * working image whenever an id ever mapped to a different original
         * (rerun, reimport, subset render): detection and OCR then ran on the
         * old page while wipe/typeset drew onto the new one.
         */
        fun workingFileName(pageId: Long, contentHash: String): String =
            "working_${pageId}_${contentHash.take(16)}.jpg"

        fun contentHash(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private val slotStorage: SlotStorage by lazy { SlotStorage.getInstance(context) }
    private val catalogStore: ModelCatalogStore by lazy { ModelCatalogStore(context.filesDir) }
    private val baberuModelManager: BaberuModelManager by lazy { BaberuModelManager.getInstance(context) }
    private val baberuOcrReader: BaberuOcrReader by lazy { BaberuOcrReader(baberuModelManager) }

    /**
     * Tier 1 on-device detector. Held for the lifetime of the pipeline because building an
     * ORT session means loading a 10.6 MB graph — doing that per page would dominate the
     * page's runtime. Lazy so a device that cannot load it pays nothing.
     */
    private val bubbleDetector: OnnxBubbleDetector by lazy { OnnxBubbleDetector(context) }

    private suspend fun <T> executeWithSlotAndObservability(
        stage: PipelineStage,
        slot: ApiSlot,
        pageIndex: Int,
        jobId: Long,
        modelLabel: String = slot.model,
        thresholdMs: Long = 15_000L,
        block: suspend (ApiSlot) -> Result<T>
    ): Pair<Result<T>, Long> {
        val startTimeMs = System.currentTimeMillis()
        val watcherJob = CoroutineScope(Dispatchers.Default).launch {
            var elapsed = thresholdMs
            while (isActive) {
                delay(thresholdMs)
                RunLogger.logPageEvent(
                    context, jobId, pageIndex, "WAIT",
                    "Still waiting for \"${slot.displayTitle}\" ($modelLabel) response (${elapsed / 1000}s elapsed)..."
                )
                elapsed += thresholdMs
            }
        }
        return try {
            // Local providers run on CPU/LAN hardware where one call legitimately
            // takes tens of minutes (measured 25m for a full-page detect on wuwei,
            // 2026-11-13); the router's 60s attempt timeout would discard that
            // completed response as a timeout. Cloud slots keep the 60s cap.
            val attemptTimeoutMs = if (slot.provider == ApiProvider.CUSTOM || slot.provider == ApiProvider.WORKSTATION) {
                LOCAL_ATTEMPT_TIMEOUT_MS
            } else {
                KeyRouter.DEFAULT_ATTEMPT_TIMEOUT_MS
            }
            val result = keyRouter.executeWithSlot(stage, slot, timeoutMs = attemptTimeoutMs, block = block)
            val elapsedMs = System.currentTimeMillis() - startTimeMs
            Pair(result, elapsedMs)
        } finally {
            watcherJob.cancel()
        }
    }

    suspend fun processPage(
        page: PageEntity,
        onStatusUpdate: suspend (PageEntity) -> Unit
    ): PageEntity = withContext(Dispatchers.IO) {
        var currentPage = page

        // Keep the free-model walk supplied. Without this the catalogue is never fetched, so the
        // walk silently collapses to the pinned model alone. Never throws: a failed refresh leaves
        // the previous cache in place and the page still runs.
        ModelCatalogRefresher.refreshIfStale(catalogStore, slotStorage.slots.value)

        val catalog = catalogStore.load()

        // Emit ONE exclusion summary per run
        val currentRunId = RunLogger.getActiveRunId(currentPage.jobId) ?: "run_job_${currentPage.jobId}"
        if (loggedRunExclusions.add(currentRunId)) {
            val translateExclusions = keyRouter.explain(PipelineStage.TRANSLATE, catalog = catalog)
            val summary = buildString {
                append("Slot Exclusions — Translate: [")
                append(translateExclusions.joinToString(", ") { "${it.slotId} (${it.model}): ${it.reason}" })
                append("]")
            }
            RunLogger.log(context, currentPage.jobId, "SLOT_EXCLUSIONS", summary)
        }
        val origFile = File(currentPage.originalCachePath)
        if (!origFile.exists()) {
            currentPage = currentPage.copy(
                status = PageStatus.FAILED,
                errorMessage = "Original image file not found on device."
            )
            RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "ERROR", "Original image file not found.")
            onStatusUpdate(currentPage)
            return@withContext currentPage
        }

        // 1. Prepare working scaled image if not yet created. The file name binds
        // this page id to the hash of its own original (BACKLOG:11): a stale
        // working image from a different original under the same id is never reused.
        val workingFile = File(context.cacheDir, workingFileName(currentPage.id, contentHash(origFile)))
        if (!workingFile.exists()) {
            val origBmp = ImageScaler.loadBitmapFromFile(origFile)
            if (origBmp == null) {
                currentPage = currentPage.copy(
                    status = PageStatus.FAILED,
                    errorMessage = "Corrupt image format or decode failure."
                )
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "ERROR", "Corrupt image format or decode failure: ${origFile.name}")
                onStatusUpdate(currentPage)
                return@withContext currentPage
            }
            ImageScaler.createWorkingCopy(origBmp, workingFile, 2048)
            val fileKb = origFile.length() / 1024
            RunLogger.logPageEvent(
                context,
                currentPage.jobId,
                currentPage.pageIndex,
                "PREPARE",
                "Image loaded: ${origFile.name} | Dimensions: ${origBmp.width}x${origBmp.height} px | Size: ${fileKb} KB"
            )
            currentPage = currentPage.copy(
                workingScaledPath = workingFile.absolutePath,
                width = origBmp.width,
                height = origBmp.height
            )
            onStatusUpdate(currentPage)
        }

        // 2. Stage 1: Detect (OnnxBubbleDetector) + OCR (BaberuOcrReader)
        var bubbles = currentPage.getBubbles()
        if (bubbles.isEmpty() || currentPage.lastStageAttempted == "DETECT") {
            currentPage = currentPage.copy(status = PageStatus.DETECTING, lastStageAttempted = "DETECT", activeSlotName = "On-device (Baberu OCR)")
            onStatusUpdate(currentPage)

            val workingBmp = ImageScaler.loadBitmapFromFile(workingFile) ?: run {
                currentPage = currentPage.copy(status = PageStatus.FAILED, errorMessage = "Failed to load working image.")
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "ERROR", "Failed to load working image: ${workingFile.name}")
                onStatusUpdate(currentPage)
                return@withContext currentPage
            }

            // ---- Full-page enclosure measurement (classical CV, no model) ------------
            val pageEnclosures: List<com.example.pipeline.wipe.EnclosureMap.Enclosure> = try {
                val pagePx = IntArray(workingBmp.width * workingBmp.height)
                workingBmp.getPixels(pagePx, 0, workingBmp.width, 0, 0, workingBmp.width, workingBmp.height)
                val pageLum = FloatArray(pagePx.size) { com.example.pipeline.wipe.BubbleInterior.luminance(pagePx[it]) }
                com.example.pipeline.wipe.EnclosureMap.build(
                    pageLum,
                    workingBmp.width,
                    workingBmp.height
                ).enclosures
            } catch (t: Throwable) {
                RunLogger.logPageEvent(
                    context, currentPage.jobId, currentPage.pageIndex, "ENCLOSURE_MAP",
                    "Enclosure measurement failed (${t.message}) — merge and wipe fall back to box-only."
                )
                emptyList()
            }

            // ---- On-device geometry (OnnxBubbleDetector is the sole geometry source) ----
            val detectorBubbles: List<DetectedRegion>
            val detectorTextBubbles: List<DetectedRegion>
            val detectorFloating: List<DetectedRegion>
            val detectorStartMs = System.currentTimeMillis()
            val regions: List<DetectedRegion> = try {
                if (bubbleDetector.loadModel()) {
                    bubbleDetector.detect(workingBmp)
                } else {
                    emptyList()
                }
            } catch (t: Throwable) {
                val msg = "On-device bubble detector failed: ${t.message ?: "Unknown error"}"
                currentPage = currentPage.copy(status = PageStatus.FAILED, errorMessage = msg, activeSlotName = "")
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "ERROR", msg)
                onStatusUpdate(currentPage)
                return@withContext currentPage
            }
            val detectorMs = System.currentTimeMillis() - detectorStartMs
            detectorBubbles = DetectorPostProcess.suppressDuplicates(
                DetectorPostProcess.bubbleRegions(regions)
            )
            detectorTextBubbles = DetectorPostProcess.textBubbleRegions(regions)
            detectorFloating = DetectorPostProcess.freeTextRegions(regions)

            RunLogger.logPageEvent(
                context, currentPage.jobId, currentPage.pageIndex, "DETECT_ONNX",
                if (regions.isEmpty()) {
                    "On-device detector found no regions in ${detectorMs}ms — treating page as silent."
                } else {
                    "On-device detector: ${detectorBubbles.size} bubble(s), ${detectorTextBubbles.size} text block(s), " +
                        "${detectorFloating.size} free-text region(s) in ${detectorMs}ms."
                }
            )

            // Build reading units:
            // - For each detector bubble: the text_bubble region(s) inside it (6% padding clamped)
            // - If a bubble has no text_bubble inside it: the bubble box inset 4%
            // - Free-text regions cropped directly
            val cropBoxes = ArrayList<List<Float>>()
            for (bubble in detectorBubbles) {
                val innerTexts = detectorTextBubbles.filter {
                    DetectorPostProcess.containsCenter(bubble.box, it.box)
                }
                if (innerTexts.isNotEmpty()) {
                    for (tr in innerTexts) {
                        cropBoxes.add(padBoxClamped(tr.box, 0.06f))
                    }
                } else {
                    cropBoxes.add(insetBoxClamped(bubble.box, 0.04f))
                }
            }
            for (free in detectorFloating) {
                cropBoxes.add(clampBox(free.box))
            }

            if (cropBoxes.isEmpty()) {
                bubbles = emptyList()
                RunLogger.logPageEvent(
                    context, currentPage.jobId, currentPage.pageIndex, "DETECT_DONE",
                    "0 text regions detected in ${detectorMs}ms. Page treated as silent/non-dialogue."
                )
            } else {
                // Fail closed if Baberu OCR models are not Ready (no fallback, no wipe of unread text).
                // Ready can come from mere file presence at startup, so re-verify the
                // pinned SHA-256 hashes before reading (once per page is cheap vs OCR).
                if (baberuModelManager.state.value is BaberuModelManager.State.Ready && !baberuModelManager.verifyAll()) {
                    val msg = "Baberu OCR model files failed SHA-256 verification. Re-download in Settings."
                    currentPage = currentPage.copy(status = PageStatus.FAILED, errorMessage = msg, activeSlotName = "")
                    RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "ERROR", msg)
                    onStatusUpdate(currentPage)
                    return@withContext currentPage
                }
                when (val modelState = baberuModelManager.state.value) {
                    is BaberuModelManager.State.Ready -> Unit
                    is BaberuModelManager.State.Failed -> {
                        currentPage = currentPage.copy(status = PageStatus.FAILED, errorMessage = modelState.message, activeSlotName = "")
                        RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "ERROR", modelState.message)
                        onStatusUpdate(currentPage)
                        return@withContext currentPage
                    }
                    is BaberuModelManager.State.Downloading -> {
                        val msg = "Baberu OCR model is still downloading (${(modelState.progress * 100).toInt()}%)."
                        currentPage = currentPage.copy(status = PageStatus.FAILED, errorMessage = msg, activeSlotName = "")
                        RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "ERROR", msg)
                        onStatusUpdate(currentPage)
                        return@withContext currentPage
                    }
                    is BaberuModelManager.State.NotDownloaded -> {
                        val msg = "Baberu OCR model is not downloaded. Download it in Settings."
                        currentPage = currentPage.copy(status = PageStatus.FAILED, errorMessage = msg, activeSlotName = "")
                        RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "ERROR", msg)
                        onStatusUpdate(currentPage)
                        return@withContext currentPage
                    }
                }

                val ocrStartMs = System.currentTimeMillis()
                val vlmEntries = ArrayList<BubbleTextMerger.VlmEntry>(cropBoxes.size)
                try {
                    for (cropBox in cropBoxes) {
                        val x1 = (cropBox[0] * workingBmp.width).toInt().coerceIn(0, (workingBmp.width - 1).coerceAtLeast(0))
                        val y1 = (cropBox[1] * workingBmp.height).toInt().coerceIn(0, (workingBmp.height - 1).coerceAtLeast(0))
                        val x2 = (cropBox[2] * workingBmp.width).toInt().coerceIn(x1 + 1, workingBmp.width)
                        val y2 = (cropBox[3] * workingBmp.height).toInt().coerceIn(y1 + 1, workingBmp.height)
                        val cropW = (x2 - x1).coerceAtLeast(1)
                        val cropH = (y2 - y1).coerceAtLeast(1)

                        val cropBmp = Bitmap.createBitmap(workingBmp, x1, y1, cropW, cropH)
                        val text = try {
                            baberuOcrReader.read(cropBmp)
                        } finally {
                            if (cropBmp !== workingBmp) cropBmp.recycle()
                        }
                        vlmEntries.add(
                            BubbleTextMerger.VlmEntry(
                                text = text,
                                box = cropBox,
                                vertical = cropH > cropW
                            )
                        )
                    }
                } catch (t: Throwable) {
                    val msg = t.message?.takeIf { it.isNotBlank() } ?: "Baberu OCR failed."
                    currentPage = currentPage.copy(status = PageStatus.FAILED, errorMessage = msg, activeSlotName = "")
                    RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "ERROR", "Baberu OCR error: $msg")
                    onStatusUpdate(currentPage)
                    return@withContext currentPage
                }
                val ocrElapsedMs = System.currentTimeMillis() - ocrStartMs
                RunLogger.logPageEvent(
                    context, currentPage.jobId, currentPage.pageIndex, "OCR_BABERU",
                    "Baberu OCR read ${cropBoxes.size} region(s) in ${ocrElapsedMs}ms (${vlmEntries.count { it.text.isNotBlank() }} non-blank)."
                )

                val anchored = BubbleTextAnchor.snapOrphans(
                    bubbles = detectorBubbles,
                    vlm = vlmEntries,
                    textBubbles = detectorTextBubbles,
                    srcWidth = workingBmp.width,
                    srcHeight = workingBmp.height
                )
                val merged = BubbleTextMerger.merge(
                    bubbles = detectorBubbles,
                    vlm = anchored.entries,
                    textBubbles = detectorTextBubbles,
                    floating = detectorFloating,
                    enclosures = pageEnclosures,
                    srcWidth = workingBmp.width,
                    srcHeight = workingBmp.height
                ).filter { it.text.isNotBlank() }

                val placedFromDetector = merged.count { it.source == BubbleTextMerger.SOURCE_DETECTOR }
                bubbles = merged.mapIndexed { index, m ->
                    Bubble(
                        id = index + 1,
                        text = m.text,
                        box = m.box,
                        vertical = m.vertical,
                        type = if (m.source == BubbleTextMerger.SOURCE_FLOATING) "floating" else "bubble"
                    )
                }

                if (detectorBubbles.isNotEmpty()) {
                    RunLogger.logPageEvent(
                        context, currentPage.jobId, currentPage.pageIndex, "DETECT_MERGE",
                        "Merged ${cropBoxes.size} Baberu OCR region(s) into ${bubbles.size} bubble(s): " +
                            "$placedFromDetector bubble(s) matched text, " +
                            "${detectorBubbles.size - placedFromDetector} untouched " +
                            "(${anchored.unread.size} region(s) hold detected text — see DETECT_UNREAD), " +
                            "${merged.count { it.vertical }} of ${merged.size} oriented vertical."
                    )
                }

                if (anchored.snappedCount > 0) {
                    RunLogger.logPageEvent(
                        context, currentPage.jobId, currentPage.pageIndex, "DETECT_ANCHOR",
                        "Re-anchored ${anchored.snappedCount} box(es) onto detector text geometry."
                    )
                }

                if (anchored.unread.isNotEmpty()) {
                    RunLogger.logPageEvent(
                        context, currentPage.jobId, currentPage.pageIndex, "DETECT_UNREAD",
                        "${anchored.unread.size} detected text region(s) have no readable text — " +
                            "left untouched, not wiped: " + anchored.unread.joinToString(", ") { region ->
                            "x${(region.box[0] * 100).toInt()}..${(region.box[2] * 100).toInt()}%" +
                                " y${(region.box[1] * 100).toInt()}..${(region.box[3] * 100).toInt()}%" +
                                " (score ${String.format(java.util.Locale.US, "%.2f", region.score)})"
                        }
                    )
                }

                val totalStageMs = detectorMs + ocrElapsedMs
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "DETECT_DONE", "Detected ${bubbles.size} text regions in ${totalStageMs}ms:")
                bubbles.forEach { b ->
                    val boxStr = "[${String.format(java.util.Locale.US, "%.3f,%.3f,%.3f,%.3f", b.x1, b.y1, b.x2, b.y2)}]"
                    RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "DETECT_ITEM", "  #${b.id} $boxStr (type=${b.type}, vert=${b.vertical}): \"${b.text.replace("\n", " ")}\"")
                }
            }
            currentPage = currentPage.copy(
                bubblesJson = Bubble.listToJsonString(bubbles),
                lastStageAttempted = ""
            )
            onStatusUpdate(currentPage)
        }

        // If no text was detected, we still create wiped and final output
        // 3. Stage 2: Translate (if not already translated or retrying translation)
        val needsTranslation = bubbles.any { it.translated.isBlank() && it.text.isNotBlank() }
        if (needsTranslation || currentPage.lastStageAttempted == "TRANSLATE") {
            currentPage = currentPage.copy(status = PageStatus.TRANSLATING, lastStageAttempted = "TRANSLATE")
            onStatusUpdate(currentPage)

            // Sort bubbles in Manga reading order for coherent scene flow
            bubbles = Bubble.sortByMangaReadingOrder(bubbles)

            // Extract context from preceding page in the same job for narrative continuity
            val storyContext = buildString {
                try {
                    val prevPages = com.example.data.db.AppDatabase.getInstance(context).jobDao().getPagesForJobDirect(currentPage.jobId)
                    val prevPage = prevPages.find { it.pageIndex == currentPage.pageIndex - 1 }
                    if (prevPage != null) {
                        val prevBubbles = prevPage.getBubbles().filter { it.translated.isNotBlank() }
                        if (prevBubbles.isNotEmpty()) {
                            appendLine("Previous page (${prevPage.pageIndex + 1}) dialogue:")
                            prevBubbles.takeLast(4).forEach { pb ->
                                appendLine("- \"${pb.translated}\" (Japanese: \"${pb.text.replace("\n", " ")}\")")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            val allSlots = slotStorage.slots.value
            val geminiTranslateSlots = allSlots.filter {
                it.provider == ApiProvider.GEMINI &&
                    (it.role == SlotRole.TRANSLATE || it.role == SlotRole.ANY)
            }
            if (geminiTranslateSlots.none { it.enabled && it.apiKey.isNotBlank() && !it.isInvalidKey && !it.isModelRetired }) {
                val msg = "Add a Gemini API key in Settings to translate."
                currentPage = currentPage.copy(
                    status = PageStatus.FAILED,
                    errorMessage = msg,
                    activeSlotName = ""
                )
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "ERROR", msg)
                onStatusUpdate(currentPage)
                return@withContext currentPage
            }

            val nonGeminiSlotIds = allSlots
                .filter { it.provider != ApiProvider.GEMINI }
                .map { it.id }
                .toSet()

            val attemptedSlotIds = mutableSetOf<String>()
            val attemptedSlotNotes = mutableListOf<String>()
            var lastDisposition: ErrorDisposition? = null
            var lastElapsedMs = 0L
            var translationSucceeded = false
            var totalTranslateAttempts = 0

            val translateBudget = ModelWalk.translateBudget(
                enabledSlots = geminiTranslateSlots.count { it.enabled },
                floor = maxAttempts,
                perSlotCap = MAX_TRANSLATE_SLOT_ATTEMPTS
            )

            while (!translationSucceeded && totalTranslateAttempts < translateBudget) {
                val (slot, waitTime) = keyRouter.getAvailableSlot(
                    PipelineStage.TRANSLATE,
                    attemptedSlotIds + nonGeminiSlotIds
                )
                if (slot == null) {
                    val waitUntil = waitTime ?: (System.currentTimeMillis() + 15000L)
                    val errorMsg = if (attemptedSlotNotes.isEmpty()) {
                        "All Gemini translation keys cooling down or not configured."
                    } else {
                        "All Gemini translation slots exhausted. Tried: ${attemptedSlotNotes.joinToString("; ")}"
                    }
                    currentPage = currentPage.copy(
                        status = if (attemptedSlotNotes.isEmpty()) PageStatus.WAITING else PageStatus.FAILED,
                        waitingUntilEpochMs = waitUntil,
                        errorMessage = errorMsg
                    )
                    RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "WAIT", errorMsg)
                    onStatusUpdate(currentPage)
                    return@withContext currentPage
                }

                attemptedSlotIds.add(slot.id)

                val plan = ModelWalk.planFor(slot, catalog, needVision = false)
                val candidateModels = plan.all

                var slotAttempts = 0
                for (candidateModel in candidateModels) {
                    if (totalTranslateAttempts >= translateBudget) break
                    if (slotAttempts >= MAX_TRANSLATE_SLOT_ATTEMPTS) break
                    slotAttempts++
                    totalTranslateAttempts++
                    currentPage = currentPage.copy(activeSlotName = "${slot.displayTitle} ($candidateModel)")
                    val attemptSuffix = if (totalTranslateAttempts > 1) " (fallback attempt $totalTranslateAttempts/$translateBudget)" else ""
                    RunLogger.logPageEvent(
                        context, currentPage.jobId, currentPage.pageIndex, "TRANSLATE_START",
                        "Translating ${bubbles.size} bubbles with ${slot.displayTitle} ($candidateModel)$attemptSuffix"
                    )
                    onStatusUpdate(currentPage)

                    val (transResult, attemptElapsedMs) = executeWithSlotAndObservability(
                        PipelineStage.TRANSLATE, slot, currentPage.pageIndex, currentPage.jobId,
                        modelLabel = candidateModel
                    ) { s ->
                        val resolvedBaseUrl = resolveGeminiOpenAiBaseUrl(s.baseUrl)
                        openAiClient.translateBubbles(
                            resolvedBaseUrl,
                            s.apiKey,
                            candidateModel,
                            bubbles,
                            provider = ApiProvider.GEMINI,
                            storyContext = storyContext
                        )
                    }
                    val elapsedMs = attemptElapsedMs
                    lastElapsedMs = elapsedMs

                    if (transResult.isFailure) {
                        val errorInfo = classifyException(transResult.exceptionOrNull())
                        lastDisposition = errorInfo
                        attemptedSlotNotes += "${slot.displayTitle}:$candidateModel [${errorInfo.eventTag}]"
                        RunLogger.logPageEvent(
                            context, currentPage.jobId, currentPage.pageIndex, errorInfo.eventTag,
                            "${errorInfo.userMessage} (${elapsedMs}ms, cause: ${errorInfo.eventTag}) — slot \"${slot.displayTitle}\" ($candidateModel), attempt $totalTranslateAttempts/$translateBudget"
                        )
                        if (errorInfo.isRateLimit || errorInfo.eventTag == "AUTH_FAIL") {
                            break
                        }
                        continue
                    }

                    val translations = transResult.getOrNull().orEmpty()
                    bubbles = bubbles.map { b ->
                        val trans = translations[b.id] ?: b.translated
                        b.copy(translated = trans)
                    }
                    RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "TRANSLATE_DONE", "Translated ${translations.size} bubbles in ${elapsedMs}ms via ${slot.displayTitle} ($candidateModel):")
                    bubbles.forEach { b ->
                        if (b.translated.isNotBlank()) {
                            RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "TRANSLATE_ITEM", "  #${b.id}: \"${b.text.replace("\n", " ")}\" -> \"${b.translated.replace("\n", " ")}\"")
                        }
                    }
                    currentPage = currentPage.copy(
                        bubblesJson = Bubble.listToJsonString(bubbles),
                        lastStageAttempted = ""
                    )
                    onStatusUpdate(currentPage)
                    translationSucceeded = true
                    break
                }
            }

            if (!translationSucceeded) {
                val errorInfo = lastDisposition ?: ErrorDisposition(
                    isTransient = false,
                    isRateLimit = false,
                    backoffMs = 0L,
                    eventTag = "FAIL",
                    userMessage = "Translation failed on all available slots."
                )
                val detail = if (attemptedSlotNotes.isEmpty()) errorInfo.userMessage
                    else "${errorInfo.userMessage} — tried ${attemptedSlotNotes.size} slot(s): ${attemptedSlotNotes.joinToString("; ")}"
                currentPage = currentPage.copy(
                    status = if (errorInfo.isTransient) PageStatus.WAITING else PageStatus.FAILED,
                    waitingUntilEpochMs = if (errorInfo.isTransient) System.currentTimeMillis() + errorInfo.backoffMs else 0L,
                    errorMessage = detail,
                    activeSlotName = ""
                )
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, errorInfo.eventTag, "$detail (${lastElapsedMs}ms, cause: ${errorInfo.eventTag})")
                onStatusUpdate(currentPage)
                return@withContext currentPage
            }
        }

        // 4. Stage 3: Wipe
        currentPage = currentPage.copy(status = PageStatus.WIPING)
        RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "WIPE_START", "Wiping ${bubbles.size} speech bubbles")
        onStatusUpdate(currentPage)

        val origBmp = ImageScaler.loadBitmapFromFile(origFile) ?: run {
            currentPage = currentPage.copy(status = PageStatus.FAILED, errorMessage = "Failed to load original bitmap for wipe.")
            RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "ERROR", "Failed to load original bitmap for wipe")
            onStatusUpdate(currentPage)
            return@withContext currentPage
        }

        val wipedBmp = FlatWiper.wipeBubbles(origBmp, bubbles)
        val wipedFile = File(context.cacheDir, "wiped_${currentPage.id}.png")
        ImageScaler.saveBitmapPng(wipedBmp, wipedFile)
        currentPage = currentPage.copy(wipedImagePath = wipedFile.absolutePath)
        onStatusUpdate(currentPage)

        // 5. Stage 4: Typeset / Rendering
        currentPage = currentPage.copy(status = PageStatus.RENDERING)
        RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "TYPESET_START", "Typesetting manga fonts & layout")
        onStatusUpdate(currentPage)

        val finalBmp = Typesetter.typesetBubbles(context, wipedBmp, bubbles)
        val finalFile = File(context.cacheDir, "final_${currentPage.id}.png")
        ImageScaler.saveBitmapPng(finalBmp, finalFile)

        // Write sidecar JSON
        val sidecarFile = File(context.cacheDir, "sidecar_${currentPage.id}.json")
        sidecarFile.writeText(Bubble.listToJsonString(bubbles))

        currentPage = currentPage.copy(
            status = PageStatus.DONE,
            finalImagePath = finalFile.absolutePath,
            errorMessage = "",
            waitingUntilEpochMs = 0L,
            lastStageAttempted = ""
        )
        RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "DONE", "Page fully processed and typeset")
        onStatusUpdate(currentPage)
        return@withContext currentPage
    }

    private fun clampBox(box: List<Float>): List<Float> {
        if (box.size < 4) return listOf(0f, 0f, 1f, 1f)
        val x1 = box[0].coerceIn(0f, 1f)
        val y1 = box[1].coerceIn(0f, 1f)
        val x2 = box[2].coerceIn(x1, 1f)
        val y2 = box[3].coerceIn(y1, 1f)
        return listOf(x1, y1, x2, y2)
    }

    private fun padBoxClamped(box: List<Float>, fraction: Float): List<Float> {
        if (box.size < 4) return listOf(0f, 0f, 1f, 1f)
        val w = (box[2] - box[0]).coerceAtLeast(0f)
        val h = (box[3] - box[1]).coerceAtLeast(0f)
        val px = w * fraction
        val py = h * fraction
        val x1 = (box[0] - px).coerceIn(0f, 1f)
        val y1 = (box[1] - py).coerceIn(0f, 1f)
        val x2 = (box[2] + px).coerceIn(x1, 1f)
        val y2 = (box[3] + py).coerceIn(y1, 1f)
        return listOf(x1, y1, x2, y2)
    }

    private fun insetBoxClamped(box: List<Float>, fraction: Float): List<Float> {
        if (box.size < 4) return listOf(0f, 0f, 1f, 1f)
        val w = (box[2] - box[0]).coerceAtLeast(0f)
        val h = (box[3] - box[1]).coerceAtLeast(0f)
        val ix = w * fraction
        val iy = h * fraction
        val x1 = (box[0] + ix).coerceIn(0f, 1f)
        val y1 = (box[1] + iy).coerceIn(0f, 1f)
        val x2 = (box[2] - ix).coerceIn(0f, 1f)
        val y2 = (box[3] - iy).coerceIn(0f, 1f)
        return if (x2 > x1 && y2 > y1) listOf(x1, y1, x2, y2) else clampBox(box)
    }

    private fun classifyException(exception: Throwable?): ErrorDisposition {
        if (exception == null) {
            return ErrorDisposition(isTransient = false, isRateLimit = false, backoffMs = 0L, eventTag = "FAIL", userMessage = "Unknown error")
        }

        if (exception is ApiException) {
            if (exception.isRateLimited) {
                val retryMs = (exception.retryAfterMs ?: 20_000L).coerceAtLeast(10_000L)
                return ErrorDisposition(
                    isTransient = true,
                    isRateLimit = true,
                    backoffMs = retryMs,
                    eventTag = "RATE_LIMIT",
                    userMessage = exception.message
                )
            }
            if (exception.statusCode in 500..599) {
                val retryMs = (exception.retryAfterMs ?: 15_000L).coerceAtLeast(10_000L)
                return ErrorDisposition(
                    isTransient = true,
                    isRateLimit = false,
                    backoffMs = retryMs,
                    eventTag = "SERVER_RETRY",
                    userMessage = "Server temporary error (${exception.statusCode}): ${exception.message}"
                )
            }
            if (exception.isInvalidKey) {
                return ErrorDisposition(
                    isTransient = false,
                    isRateLimit = false,
                    backoffMs = 0L,
                    eventTag = "AUTH_FAIL",
                    userMessage = "API Key Unauthorized (HTTP ${exception.statusCode}): ${exception.message}"
                )
            }
        }

        // Network / DNS / Timeout exceptions are transient
        val isNetworkException = exception is java.net.UnknownHostException ||
                exception is java.net.SocketTimeoutException ||
                exception is java.net.ConnectException ||
                exception is javax.net.ssl.SSLException ||
                exception is java.io.IOException ||
                exception is java.util.concurrent.TimeoutException ||
                (exception.message?.contains("Unable to resolve host", ignoreCase = true) == true) ||
                (exception.message?.contains("timeout", ignoreCase = true) == true) ||
                (exception.message?.contains("connection", ignoreCase = true) == true)

        if (isNetworkException) {
            val isTimeout = exception is java.net.SocketTimeoutException ||
                    exception is java.util.concurrent.TimeoutException ||
                    exception.message?.contains("timeout", ignoreCase = true) == true
            val tag = if (isTimeout) "TIMEOUT" else "NETWORK_RETRY"
            return ErrorDisposition(
                isTransient = true,
                isRateLimit = false,
                backoffMs = 12_000L,
                eventTag = tag,
                userMessage = if (isTimeout) "Request timed out (${exception.message}). Auto-retrying..." else "Network/DNS issue (${exception.message}). Auto-retrying..."
            )
        }

        return ErrorDisposition(
            isTransient = false,
            isRateLimit = false,
            backoffMs = 0L,
            eventTag = "FAIL",
            userMessage = exception.message ?: "Failed"
        )
    }
}

private data class ErrorDisposition(
    val isTransient: Boolean,
    val isRateLimit: Boolean,
    val backoffMs: Long,
    val eventTag: String,
    val userMessage: String
)

// Backward-compatible default attempt limits
private const val MAX_TRANSLATE_SLOT_ATTEMPTS = KeyRouter.DEFAULT_MAX_ATTEMPTS
private const val LOCAL_ATTEMPT_TIMEOUT_MS = 60L * 60L * 1000L
private const val MAX_DETECT_SLOT_ATTEMPTS = KeyRouter.DEFAULT_MAX_ATTEMPTS
