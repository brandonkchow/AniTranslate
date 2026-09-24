package com.example.pipeline

import android.content.Context
import com.example.data.db.PageEntity
import com.example.data.db.PageStatus
import com.example.data.models.Bubble
import com.example.data.slots.ApiProvider
import com.example.data.slots.ApiSlot
import com.example.net.ApiException
import com.example.net.GeminiClient
import com.example.net.OpenAiCompatibleClient
import com.example.pipeline.detect.BubbleTextAnchor
import com.example.pipeline.detect.BubbleTextMerger
import com.example.pipeline.detect.DetectedRegion
import com.example.pipeline.detect.DetectorPostProcess
import com.example.pipeline.detect.OnnxBubbleDetector
import com.example.pipeline.image.ImageScaler
import com.example.pipeline.router.KeyRouter
import com.example.pipeline.router.PipelineStage
import com.example.pipeline.typeset.Typesetter
import com.example.pipeline.wipe.FlatWiper
import com.example.utils.RunLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PagePipeline(
    private val context: Context,
    private val keyRouter: KeyRouter,
    private val geminiClient: GeminiClient,
    private val openAiClient: OpenAiCompatibleClient
) {

    /**
     * Tier 1 on-device detector. Held for the lifetime of the pipeline because building an
     * ORT session means loading a 10.6 MB graph — doing that per page would dominate the
     * page's runtime. Lazy so a device that cannot load it pays nothing.
     */
    private val bubbleDetector: OnnxBubbleDetector by lazy { OnnxBubbleDetector(context) }

    suspend fun processPage(
        page: PageEntity,
        onStatusUpdate: suspend (PageEntity) -> Unit
    ): PageEntity = withContext(Dispatchers.IO) {
        var currentPage = page
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

        // 1. Prepare working scaled image if not yet created
        val workingFile = File(context.cacheDir, "working_${currentPage.id}.jpg")
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

        // 2. Stage 1: Detect + OCR (if not already detected)
        var bubbles = currentPage.getBubbles()
        if (bubbles.isEmpty() || currentPage.lastStageAttempted == "DETECT") {
            currentPage = currentPage.copy(status = PageStatus.DETECTING, lastStageAttempted = "DETECT")
            onStatusUpdate(currentPage)

            val workingBmp = ImageScaler.loadBitmapFromFile(workingFile) ?: run {
                currentPage = currentPage.copy(status = PageStatus.FAILED, errorMessage = "Failed to load working image.")
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "ERROR", "Failed to load working image: ${workingFile.name}")
                onStatusUpdate(currentPage)
                return@withContext currentPage
            }
            val base64 = ImageScaler.bitmapToBase64(workingBmp, quality = 85)

            // ---- Full-page enclosure measurement (classical CV, no model) ------------
            // The detector's boxes are hints; the page itself is the authority on where bubble
            // interiors are. Measured once here and consumed by both the text merger (which
            // reclassifies a "floating" entry that actually sits inside a closed white enclosure)
            // and the wiper (whose interior-authoritative plan needs the mask in page pixels).
            // A measurement failure must degrade to box-only behaviour, never abort the page.
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

            // ---- Tier 1: on-device geometry ------------------------------------------
            // The detector owns bubble geometry; the vision slot below is asked only to read
            // text. Letting a VLM own boxes is what produced three identically-sized 12%-wide
            // boxes for three differently-sized bubbles — roughly 60% undersized — which then
            // sheared the bubble walls while wiping and truncated the typeset text.
            var detectorBubbles: List<DetectedRegion>
            var detectorTextBubbles: List<DetectedRegion>
            var detectorFloating: List<DetectedRegion>
            val detectorStartMs = System.currentTimeMillis()
            try {
                val regions = if (bubbleDetector.loadModel()) {
                    bubbleDetector.detect(workingBmp)
                } else {
                    emptyList()
                }
                detectorBubbles = DetectorPostProcess.suppressDuplicates(
                    DetectorPostProcess.bubbleRegions(regions)
                )
                detectorTextBubbles = DetectorPostProcess.textBubbleRegions(regions)
                detectorFloating = DetectorPostProcess.freeTextRegions(regions)
                RunLogger.logPageEvent(
                    context, currentPage.jobId, currentPage.pageIndex, "DETECT_ONNX",
                    if (regions.isEmpty()) {
                        "On-device detector found no regions in ${System.currentTimeMillis() - detectorStartMs}ms — using cloud vision geometry."
                    } else {
                        "On-device detector: ${detectorBubbles.size} bubble(s), ${detectorTextBubbles.size} text block(s), " +
                            "${detectorFloating.size} free-text region(s) in ${System.currentTimeMillis() - detectorStartMs}ms."
                    }
                )
            } catch (t: Throwable) {
                // Tier 1 is an optimisation, never a dependency: any failure must degrade to
                // exactly the cloud-only behaviour that shipped before it existed.
                detectorBubbles = emptyList()
                detectorTextBubbles = emptyList()
                detectorFloating = emptyList()
                RunLogger.logPageEvent(
                    context, currentPage.jobId, currentPage.pageIndex, "DETECT_ONNX",
                    "On-device detector failed (${t.message}) — using cloud vision geometry."
                )
            }

            // Multi-slot fallback: a vision slot that is rate-limited, policy-excluded, or
            // otherwise unable to serve this page must not abort the job when another configured
            // vision slot could still detect and OCR it.
            val attemptedDetectSlotIds = mutableSetOf<String>()
            val attemptedDetectNotes = mutableListOf<String>()
            var lastDetectDisposition: ErrorDisposition? = null
            var detectSucceeded = false
            var elapsedMs = 0L
            var detectSlot: ApiSlot? = null

            while (!detectSucceeded && attemptedDetectSlotIds.size < MAX_DETECT_SLOT_ATTEMPTS) {
                val (slot, waitTime) = keyRouter.getAvailableSlot(PipelineStage.DETECT_OCR, attemptedDetectSlotIds)
                if (slot == null) {
                    val waitUntil = waitTime ?: (System.currentTimeMillis() + 15000L)
                    val errorMsg = if (attemptedDetectSlotIds.isEmpty()) {
                        "All vision keys cooling down or not configured."
                    } else {
                        "All vision slots exhausted. Tried: ${attemptedDetectNotes.joinToString("; ")}"
                    }
                    currentPage = currentPage.copy(
                        status = if (attemptedDetectSlotIds.isEmpty()) PageStatus.WAITING else PageStatus.FAILED,
                        waitingUntilEpochMs = waitUntil,
                        errorMessage = errorMsg
                    )
                    RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "WAIT", errorMsg)
                    onStatusUpdate(currentPage)
                    return@withContext currentPage
                }

                attemptedDetectSlotIds.add(slot.id)
                currentPage = currentPage.copy(activeSlotName = slot.displayTitle)
                val attemptSuffix = if (attemptedDetectSlotIds.size > 1) " (fallback attempt ${attemptedDetectSlotIds.size}/$MAX_DETECT_SLOT_ATTEMPTS)" else ""
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "DETECT_START", "Detecting with ${slot.displayTitle} (${slot.model})$attemptSuffix")
                onStatusUpdate(currentPage)

                val startTimeMs = System.currentTimeMillis()
                val detectResult = keyRouter.executeWithSlot(PipelineStage.DETECT_OCR, slot) { s ->
                    when (s.provider) {
                        ApiProvider.GEMINI -> geminiClient.detectBubbles(s.baseUrl, s.apiKey, s.model, base64)
                        ApiProvider.OPENROUTER, ApiProvider.CUSTOM, ApiProvider.WORKSTATION, ApiProvider.HUGGINGFACE ->
                            openAiClient.detectBubbles(s.baseUrl, s.apiKey, s.model, base64, provider = s.provider)
                        ApiProvider.GROQ -> Result.failure(IllegalArgumentException("Groq does not support image detection."))
                    }
                }
                elapsedMs = System.currentTimeMillis() - startTimeMs

                if (detectResult.isFailure) {
                    val errorInfo = classifyException(detectResult.exceptionOrNull())
                    lastDetectDisposition = errorInfo
                    attemptedDetectNotes += "${slot.displayTitle} [${errorInfo.eventTag}]"
                    RunLogger.logPageEvent(
                        context, currentPage.jobId, currentPage.pageIndex, errorInfo.eventTag,
                        "${errorInfo.userMessage} (${elapsedMs}ms) — slot \"${slot.displayTitle}\", attempt ${attemptedDetectSlotIds.size}/$MAX_DETECT_SLOT_ATTEMPTS"
                    )
                    continue
                }

                bubbles = detectResult.getOrNull().orEmpty()
                detectSlot = slot
                detectSucceeded = true
            }

            if (!detectSucceeded) {
                val errorInfo = lastDetectDisposition ?: ErrorDisposition(
                    isTransient = false,
                    isRateLimit = false,
                    backoffMs = 0L,
                    eventTag = "FAIL",
                    userMessage = "Detection failed on all available vision slots."
                )
                val detail = if (attemptedDetectNotes.isEmpty()) errorInfo.userMessage
                    else "${errorInfo.userMessage} — tried ${attemptedDetectNotes.size} slot(s): ${attemptedDetectNotes.joinToString("; ")}"
                currentPage = currentPage.copy(
                    status = if (errorInfo.isTransient) PageStatus.WAITING else PageStatus.FAILED,
                    waitingUntilEpochMs = if (errorInfo.isTransient) System.currentTimeMillis() + errorInfo.backoffMs else 0L,
                    errorMessage = detail,
                    activeSlotName = ""
                )
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, errorInfo.eventTag, "$detail (${elapsedMs}ms)")
                onStatusUpdate(currentPage)
                return@withContext currentPage
            }

            // ---- Fuse detector geometry with vision text -------------------------------
            // The detector's boxes are authoritative; the vision slot above contributed only
            // its reading of the text. Matching is by centre-containment, which recovers the
            // text even when the vision box was badly sized but still sat on the right bubble.
            if (detectorBubbles.isNotEmpty()) {
                val vlmEntries = bubbles.map {
                    BubbleTextMerger.VlmEntry(text = it.text, box = it.box, vertical = it.vertical)
                }
                // Snap misplaced vision boxes onto detector text geometry *before* matching. A box
                // that missed its bubble by a few dozen pixels otherwise loses its text to
                // centre-containment and is typeset as a floating region in empty artwork.
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

                if (merged.isNotEmpty()) {
                    val visionBoxCount = bubbles.size
                    bubbles = merged.mapIndexed { index, m ->
                        Bubble(
                            id = index + 1,
                            text = m.text,
                            box = m.box,
                            vertical = m.vertical,
                            type = if (m.source == BubbleTextMerger.SOURCE_FLOATING) "floating" else "bubble"
                        )
                    }
                    RunLogger.logPageEvent(
                        context, currentPage.jobId, currentPage.pageIndex, "DETECT_MERGE",
                        "Replaced $visionBoxCount vision box(es) with detector geometry: " +
                            "$placedFromDetector bubble(s) matched text, " +
                            "${detectorBubbles.size - placedFromDetector} untouched " +
                            "(${anchored.unread.size} region(s) of them hold detected text — see DETECT_UNREAD), " +
                            "${merged.count { it.vertical }} of ${merged.size} oriented vertical."
                    )
                } else {
                    // Nothing we read landed inside a detected bubble — that is a real signal
                    // about the vision slot, so say so rather than failing silently.
                    RunLogger.logPageEvent(
                        context, currentPage.jobId, currentPage.pageIndex, "DETECT_MERGE",
                        "Detector found ${detectorBubbles.size} bubble(s) but no vision text fell inside any of them — keeping vision geometry."
                    )
                }

                if (anchored.snappedCount > 0) {
                    RunLogger.logPageEvent(
                        context, currentPage.jobId, currentPage.pageIndex, "DETECT_ANCHOR",
                        "Re-anchored ${anchored.snappedCount} vision box(es) that missed their bubble " +
                            "onto detector text geometry (max gap ${(BubbleTextAnchor.MAX_ANCHOR_GAP_FRACTION * 100).toInt()}% of page diagonal)."
                    )
                }

                if (anchored.unread.isNotEmpty()) {
                    // The detector measured text here and the reading slot returned none for it.
                    // Never silent: name the regions, and leave the artwork untouched — an
                    // untouched bubble beats one wiped blank with nothing to typeset into it.
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
            }

            val activeDetectSlot: ApiSlot = detectSlot ?: run {
                // Defensive: detectSucceeded is only set together with detectSlot, so this is
                // unreachable in practice — fail loudly rather than dereferencing null.
                currentPage = currentPage.copy(status = PageStatus.FAILED, errorMessage = "Detection slot resolution failed.")
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "FAIL", "Detection reported success but recorded no slot.")
                onStatusUpdate(currentPage)
                return@withContext currentPage
            }
            
            // 2b. Fallback Strategy: If 0 text regions found in Pass 1, trigger Pass 2 "Scan-All-Text" OCR Mode
            if (bubbles.isEmpty()) {
                RunLogger.logPageEvent(
                    context,
                    currentPage.jobId,
                    currentPage.pageIndex,
                    "DETECT_FALLBACK",
                    "0 bubbles detected in Pass 1 (${elapsedMs}ms). Triggering Pass 2 'Scan-All-Text' OCR Fallback..."
                )

                val fallbackStartTimeMs = System.currentTimeMillis()
                val fallbackResult = keyRouter.executeWithSlot(PipelineStage.DETECT_OCR, activeDetectSlot) { s ->
                    when (s.provider) {
                        ApiProvider.GEMINI -> geminiClient.scanAllText(s.baseUrl, s.apiKey, s.model, base64)
                        ApiProvider.OPENROUTER, ApiProvider.CUSTOM, ApiProvider.WORKSTATION, ApiProvider.HUGGINGFACE ->
                            openAiClient.scanAllText(s.baseUrl, s.apiKey, s.model, base64, provider = s.provider)
                        ApiProvider.GROQ -> Result.failure(IllegalArgumentException("Groq does not support image detection."))
                    }
                }
                val fallbackElapsedMs = System.currentTimeMillis() - fallbackStartTimeMs

                if (fallbackResult.isSuccess) {
                    val fallbackBubbles = fallbackResult.getOrNull().orEmpty()
                    if (fallbackBubbles.isNotEmpty()) {
                        bubbles = fallbackBubbles
                        RunLogger.logPageEvent(
                            context,
                            currentPage.jobId,
                            currentPage.pageIndex,
                            "DETECT_RECOVERED",
                            "Scan-All-Text Fallback recovered ${bubbles.size} Japanese text regions (${fallbackElapsedMs}ms):"
                        )
                        bubbles.forEach { b ->
                            val boxStr = "[${String.format(java.util.Locale.US, "%.3f,%.3f,%.3f,%.3f", b.x1, b.y1, b.x2, b.y2)}]"
                            RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "DETECT_ITEM", "  #${b.id} $boxStr (type=${b.type}, vert=${b.vertical}): \"${b.text.replace("\n", " ")}\"")
                        }
                    } else {
                        RunLogger.logPageEvent(
                            context,
                            currentPage.jobId,
                            currentPage.pageIndex,
                            "DETECT_DONE",
                            "0 text regions detected after 2-pass scan (${elapsedMs + fallbackElapsedMs}ms). Page treated as silent/non-dialogue."
                        )
                    }
                } else {
                    RunLogger.logPageEvent(
                        context,
                        currentPage.jobId,
                        currentPage.pageIndex,
                        "DETECT_DONE",
                        "Fallback scan completed with 0 detections. Page treated as silent/non-dialogue."
                    )
                }
            } else {
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "DETECT_DONE", "Detected ${bubbles.size} text regions in ${elapsedMs}ms:")
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

            // Multi-slot fallback: a slot that is rate-limited, policy-excluded (e.g. OpenRouter
            // ZDR guardrails), or otherwise unable to serve this page must not abort the whole job
            // when other configured translation slots could still handle it. Exhaust up to
            // MAX_TRANSLATE_SLOT_ATTEMPTS distinct slots, then surface the final failure.
            val attemptedSlotIds = mutableSetOf<String>()
            val attemptedSlotNotes = mutableListOf<String>()
            var lastDisposition: ErrorDisposition? = null
            var lastElapsedMs = 0L
            var translationSucceeded = false

            while (!translationSucceeded && attemptedSlotIds.size < MAX_TRANSLATE_SLOT_ATTEMPTS) {
                val (slot, waitTime) = keyRouter.getAvailableSlot(PipelineStage.TRANSLATE, attemptedSlotIds)
                if (slot == null) {
                    val waitUntil = waitTime ?: (System.currentTimeMillis() + 15000L)
                    val errorMsg = if (attemptedSlotIds.isEmpty()) {
                        "All translation keys cooling down or not configured."
                    } else {
                        "All translation slots exhausted. Tried: ${attemptedSlotNotes.joinToString("; ")}"
                    }
                    currentPage = currentPage.copy(
                        status = if (attemptedSlotIds.isEmpty()) PageStatus.WAITING else PageStatus.FAILED,
                        waitingUntilEpochMs = waitUntil,
                        errorMessage = errorMsg
                    )
                    RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "WAIT", errorMsg)
                    onStatusUpdate(currentPage)
                    return@withContext currentPage
                }

                attemptedSlotIds.add(slot.id)
                currentPage = currentPage.copy(activeSlotName = slot.displayTitle)
                val attemptSuffix = if (attemptedSlotIds.size > 1) " (fallback attempt ${attemptedSlotIds.size}/$MAX_TRANSLATE_SLOT_ATTEMPTS)" else ""
                RunLogger.logPageEvent(
                    context, currentPage.jobId, currentPage.pageIndex, "TRANSLATE_START",
                    "Translating ${bubbles.size} bubbles with ${slot.displayTitle}$attemptSuffix"
                )
                onStatusUpdate(currentPage)

                val startTimeMs = System.currentTimeMillis()
                val transResult = keyRouter.executeWithSlot(PipelineStage.TRANSLATE, slot) { s ->
                    when (s.provider) {
                        ApiProvider.GEMINI -> geminiClient.translateBubbles(s.baseUrl, s.apiKey, s.model, bubbles, storyContext = storyContext)
                        ApiProvider.GROQ, ApiProvider.OPENROUTER, ApiProvider.CUSTOM, ApiProvider.WORKSTATION, ApiProvider.HUGGINGFACE ->
                            openAiClient.translateBubbles(s.baseUrl, s.apiKey, s.model, bubbles, provider = s.provider, storyContext = storyContext)
                    }
                }
                val elapsedMs = System.currentTimeMillis() - startTimeMs
                lastElapsedMs = elapsedMs

                if (transResult.isFailure) {
                    // Policy exclusions (ZDR / data policy), auth failures, and exhausted quotas
                    // will never succeed on a same-slot retry, so advance to the next slot now.
                    val errorInfo = classifyException(transResult.exceptionOrNull())
                    lastDisposition = errorInfo
                    attemptedSlotNotes += "${slot.displayTitle} [${errorInfo.eventTag}]"
                    RunLogger.logPageEvent(
                        context, currentPage.jobId, currentPage.pageIndex, errorInfo.eventTag,
                        "${errorInfo.userMessage} (${elapsedMs}ms) — slot \"${slot.displayTitle}\", attempt ${attemptedSlotIds.size}/$MAX_TRANSLATE_SLOT_ATTEMPTS"
                    )
                    continue
                }

                val translations = transResult.getOrNull().orEmpty()
                bubbles = bubbles.map { b ->
                    val trans = translations[b.id] ?: b.translated
                    b.copy(translated = trans)
                }
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "TRANSLATE_DONE", "Translated ${translations.size} bubbles in ${elapsedMs}ms via ${slot.displayTitle}:")
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
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, errorInfo.eventTag, "$detail (${lastElapsedMs}ms)")
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
                (exception.message?.contains("Unable to resolve host", ignoreCase = true) == true) ||
                (exception.message?.contains("timeout", ignoreCase = true) == true) ||
                (exception.message?.contains("connection", ignoreCase = true) == true)

        if (isNetworkException) {
            return ErrorDisposition(
                isTransient = true,
                isRateLimit = false,
                backoffMs = 12_000L,
                eventTag = "NETWORK_RETRY",
                userMessage = "Network/DNS issue (${exception.message}). Auto-retrying..."
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

/**
 * Upper bound on distinct translation slots tried for a single page before the page is marked
 * failed. Keeps a page from cycling through every configured slot (and burning free-tier quota)
 * when the failures are systemic rather than slot-specific.
 */
private const val MAX_TRANSLATE_SLOT_ATTEMPTS = 3

/**
 * Upper bound on distinct vision slots tried for a single page's detect/OCR pass before the page
 * is marked failed or queued for retry.
 */
private const val MAX_DETECT_SLOT_ATTEMPTS = 3
