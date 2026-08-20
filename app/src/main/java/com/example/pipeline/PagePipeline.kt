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

            val (slot, waitTime) = keyRouter.getAvailableSlot(PipelineStage.DETECT_OCR)
            if (slot == null) {
                val waitUntil = waitTime ?: (System.currentTimeMillis() + 15000L)
                currentPage = currentPage.copy(
                    status = PageStatus.WAITING,
                    waitingUntilEpochMs = waitUntil,
                    errorMessage = "All vision keys cooling down or not configured."
                )
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "WAIT", "Waiting for available vision key.")
                onStatusUpdate(currentPage)
                return@withContext currentPage
            }

            currentPage = currentPage.copy(activeSlotName = slot.displayTitle)
            RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "DETECT_START", "Detecting with ${slot.displayTitle} (${slot.model})")
            onStatusUpdate(currentPage)

            val workingBmp = ImageScaler.loadBitmapFromFile(workingFile) ?: run {
                currentPage = currentPage.copy(status = PageStatus.FAILED, errorMessage = "Failed to load working image.")
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "ERROR", "Failed to load working image: ${workingFile.name}")
                onStatusUpdate(currentPage)
                return@withContext currentPage
            }
            val base64 = ImageScaler.bitmapToBase64(workingBmp, quality = 85)
            val startTimeMs = System.currentTimeMillis()

            val detectResult = keyRouter.executeWithSlot(PipelineStage.DETECT_OCR, slot) { s ->
                when (s.provider) {
                    ApiProvider.GEMINI -> geminiClient.detectBubbles(s.baseUrl, s.apiKey, s.model, base64)
                    ApiProvider.OPENROUTER, ApiProvider.CUSTOM -> openAiClient.detectBubbles(s.baseUrl, s.apiKey, s.model, base64, provider = s.provider)
                    ApiProvider.GROQ -> Result.failure(IllegalArgumentException("Groq does not support image detection."))
                }
            }
            val elapsedMs = System.currentTimeMillis() - startTimeMs

            if (detectResult.isFailure) {
                val exception = detectResult.exceptionOrNull()
                val errorInfo = classifyException(exception)
                currentPage = currentPage.copy(
                    status = if (errorInfo.isTransient) PageStatus.WAITING else PageStatus.FAILED,
                    waitingUntilEpochMs = if (errorInfo.isTransient) System.currentTimeMillis() + errorInfo.backoffMs else 0L,
                    errorMessage = errorInfo.userMessage
                )
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, errorInfo.eventTag, "${errorInfo.userMessage} (${elapsedMs}ms)")
                onStatusUpdate(currentPage)
                return@withContext currentPage
            }

            bubbles = detectResult.getOrNull().orEmpty()
            
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
                val fallbackResult = keyRouter.executeWithSlot(PipelineStage.DETECT_OCR, slot) { s ->
                    when (s.provider) {
                        ApiProvider.GEMINI -> geminiClient.scanAllText(s.baseUrl, s.apiKey, s.model, base64)
                        ApiProvider.OPENROUTER, ApiProvider.CUSTOM -> openAiClient.scanAllText(s.baseUrl, s.apiKey, s.model, base64, provider = s.provider)
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

            val (slot, waitTime) = keyRouter.getAvailableSlot(PipelineStage.TRANSLATE)
            if (slot == null) {
                val waitUntil = waitTime ?: (System.currentTimeMillis() + 15000L)
                currentPage = currentPage.copy(
                    status = PageStatus.WAITING,
                    waitingUntilEpochMs = waitUntil,
                    errorMessage = "All translation keys cooling down or not configured."
                )
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "WAIT", "Waiting for available translate key.")
                onStatusUpdate(currentPage)
                return@withContext currentPage
            }

            currentPage = currentPage.copy(activeSlotName = slot.displayTitle)
            RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "TRANSLATE_START", "Translating ${bubbles.size} bubbles with ${slot.displayTitle}")
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

            val startTimeMs = System.currentTimeMillis()
            val transResult = keyRouter.executeWithSlot(PipelineStage.TRANSLATE, slot) { s ->
                when (s.provider) {
                    ApiProvider.GEMINI -> geminiClient.translateBubbles(s.baseUrl, s.apiKey, s.model, bubbles, storyContext = storyContext)
                    ApiProvider.GROQ, ApiProvider.OPENROUTER, ApiProvider.CUSTOM ->
                        openAiClient.translateBubbles(s.baseUrl, s.apiKey, s.model, bubbles, provider = s.provider, storyContext = storyContext)
                }
            }
            val elapsedMs = System.currentTimeMillis() - startTimeMs

            if (transResult.isFailure) {
                val exception = transResult.exceptionOrNull()
                val errorInfo = classifyException(exception)
                currentPage = currentPage.copy(
                    status = if (errorInfo.isTransient) PageStatus.WAITING else PageStatus.FAILED,
                    waitingUntilEpochMs = if (errorInfo.isTransient) System.currentTimeMillis() + errorInfo.backoffMs else 0L,
                    errorMessage = errorInfo.userMessage
                )
                RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, errorInfo.eventTag, "${errorInfo.userMessage} (${elapsedMs}ms)")
                onStatusUpdate(currentPage)
                return@withContext currentPage
            }

            val translations = transResult.getOrNull().orEmpty()
            bubbles = bubbles.map { b ->
                val trans = translations[b.id] ?: b.translated
                b.copy(translated = trans)
            }
            RunLogger.logPageEvent(context, currentPage.jobId, currentPage.pageIndex, "TRANSLATE_DONE", "Translated ${translations.size} bubbles in ${elapsedMs}ms:")
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
