package com.example.pipeline

import android.content.Context
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.db.JobDao
import com.example.data.db.PageEntity
import com.example.data.db.PageStatus
import com.example.data.slots.SlotStorage
import com.example.net.GeminiClient
import com.example.net.OpenAiCompatibleClient
import com.example.pipeline.router.KeyRouter
import com.example.utils.RunLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class JobManager(
    private val context: Context,
    private val jobDao: JobDao,
    private val slotStorage: SlotStorage
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyRouter = KeyRouter(slotStorage)
    private val geminiClient = GeminiClient()
    private val openAiClient = OpenAiCompatibleClient()
    private val pagePipeline = PagePipeline(context, keyRouter, geminiClient, openAiClient)

    private val isPaused = AtomicBoolean(false)
    private var batchLoopJob: Job? = null
    private val activePageJobs = ConcurrentHashMap<Long, Job>()

    private val _waitingBannerText = MutableStateFlow<String?>(null)
    val waitingBannerText: StateFlow<String?> = _waitingBannerText.asStateFlow()

    fun startOrResumeJob(jobId: Long) {
        isPaused.set(false)
        scope.launch {
            jobDao.updateJobStatus(jobId, "RUNNING")
            val pages = jobDao.getPagesForJobDirect(jobId)
            RunLogger.startRun(context, jobId, pages.size, "Job started or resumed")
            startBatchLoop(jobId)
        }
    }

    fun pauseJob(jobId: Long) {
        isPaused.set(true)
        scope.launch {
            jobDao.updateJobStatus(jobId, "PAUSED")
            RunLogger.log(context, jobId, "PAUSE", "Job paused by user")
            _waitingBannerText.value = null
        }
    }

    fun retryPage(pageId: Long, stage: String = "") {
        scope.launch {
            val page = jobDao.getPageByIdDirect(pageId) ?: return@launch
            RunLogger.logPageEvent(context, page.jobId, page.pageIndex, "RETRY", "Retrying page from stage: ${stage.ifBlank { "START" }}")
            val updated = page.copy(
                status = PageStatus.QUEUED,
                errorMessage = "",
                waitingUntilEpochMs = 0L,
                lastStageAttempted = stage
            )
            jobDao.updatePage(updated)
            // Resume if job was paused
            startOrResumeJob(page.jobId)
        }
    }

    private fun startBatchLoop(jobId: Long) {
        if (batchLoopJob?.isActive == true) return
        batchLoopJob = scope.launch {
            while (isActive && !isPaused.get()) {
                val pages = jobDao.getPagesForJobDirect(jobId)
                if (pages.isEmpty()) break

                val now = System.currentTimeMillis()

                // Check pending/in-progress pages
                val pendingPages = pages.filter { p ->
                    p.status == PageStatus.QUEUED ||
                    (p.status == PageStatus.WAITING && (p.waitingUntilEpochMs <= now || p.waitingUntilEpochMs == 0L))
                }

                // Check if any pages are currently parked/waiting
                val waitingPages = pages.filter { it.status == PageStatus.WAITING && it.waitingUntilEpochMs > now }
                if (waitingPages.isNotEmpty()) {
                    val earliest = waitingPages.minByOrNull { it.waitingUntilEpochMs }
                    val remainingSec = earliest?.remainingWaitSeconds(now) ?: 0L
                    val slots = slotStorage.slots.value
                    val coolingSlot = slots.find { it.isCoolingDown(now) }
                    val nextSlot = slots.find { !it.isCoolingDown(now) && it.enabled && it.apiKey.isNotBlank() }

                    val msg = if (coolingSlot != null && nextSlot != null) {
                        "Waiting ${remainingSec}s — ${coolingSlot.displayTitle} rate limited. Next: ${nextSlot.displayTitle}."
                    } else if (coolingSlot != null) {
                        "Waiting ${remainingSec}s — ${coolingSlot.displayTitle} cooling down."
                    } else {
                        "Waiting ${remainingSec}s for available API slot."
                    }
                    _waitingBannerText.value = msg
                } else {
                    _waitingBannerText.value = null
                }

                // All done or failed?
                val allCompleted = pages.all { it.status.isTerminal }
                if (allCompleted) {
                    val doneCount = pages.count { it.status == PageStatus.DONE }
                    val failedCount = pages.count { it.status == PageStatus.FAILED }
                    val status = if (failedCount == 0) "COMPLETED" else if (doneCount == 0) "FAILED" else "PARTIAL_SUCCESS"
                    jobDao.updateJobStatus(jobId, status)
                    RunLogger.finishRun(context, jobId, status, "$doneCount done, $failedCount failed out of ${pages.size} pages")
                    _waitingBannerText.value = null
                    break
                }

                // Run up to 2 concurrent page jobs
                val currentlyRunning = activePageJobs.size
                val maxConcurrent = 2
                val availableSlots = maxConcurrent - currentlyRunning

                if (availableSlots > 0 && pendingPages.isNotEmpty()) {
                    val toLaunch = pendingPages.take(availableSlots)
                    for (page in toLaunch) {
                        if (activePageJobs.containsKey(page.id)) continue
                        val job = launch {
                            try {
                                pagePipeline.processPage(page) { updatedPage ->
                                    jobDao.updatePage(updatedPage)
                                }
                            } catch (e: Exception) {
                                Log.e("JobManager", "Page processing error", e)
                                RunLogger.logPageEvent(context, page.jobId, page.pageIndex, "CRASH", e.message ?: "Unknown error")
                                jobDao.updatePage(page.copy(status = PageStatus.FAILED, errorMessage = e.message ?: "Unknown error"))
                            } finally {
                                activePageJobs.remove(page.id)
                            }
                        }
                        activePageJobs[page.id] = job
                    }
                }

                delay(1000)
            }
        }
    }
}
