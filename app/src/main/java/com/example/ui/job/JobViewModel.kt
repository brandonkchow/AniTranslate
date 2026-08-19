package com.example.ui.job

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.db.JobEntity
import com.example.data.db.PageEntity
import com.example.data.db.PageStatus
import com.example.data.slots.SlotStorage
import com.example.pipeline.JobManager
import com.example.utils.MediaStoreExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class JobUiState(
    val job: JobEntity? = null,
    val pages: List<PageEntity> = emptyList(),
    val isPaused: Boolean = false,
    val waitingBanner: String? = null,
    val isExporting: Boolean = false
) {
    val doneCount: Int get() = pages.count { it.status == PageStatus.DONE }
    val runningCount: Int get() = pages.count { it.status.isInProgress }
    val waitingCount: Int get() = pages.count { it.status == PageStatus.WAITING || it.status == PageStatus.QUEUED }
    val failedCount: Int get() = pages.count { it.status == PageStatus.FAILED }

    val summaryText: String
        get() = "$doneCount done · $runningCount running · $waitingCount waiting · $failedCount failed"

    val allDone: Boolean
        get() = pages.isNotEmpty() && pages.all { it.status == PageStatus.DONE }
}

class JobViewModel(
    application: Application,
    private val jobId: Long
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val slotStorage = SlotStorage(application)
    private val jobManager = JobManager(application, db.jobDao(), slotStorage)

    private val _isExporting = MutableStateFlow(false)
    private val _eventFlow = MutableSharedFlow<JobEvent>()
    val eventFlow: SharedFlow<JobEvent> = _eventFlow.asSharedFlow()

    val uiState: StateFlow<JobUiState> = combine(
        db.jobDao().getJobById(jobId),
        db.jobDao().getPagesForJob(jobId),
        jobManager.waitingBannerText,
        _isExporting
    ) { job, pages, banner, isExporting ->
        JobUiState(
            job = job,
            pages = pages,
            isPaused = job?.status == "PAUSED",
            waitingBanner = banner,
            isExporting = isExporting
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = JobUiState()
    )

    init {
        // Auto-start or resume job
        jobManager.startOrResumeJob(jobId)
    }

    fun pauseJob() {
        jobManager.pauseJob(jobId)
    }

    fun resumeJob() {
        jobManager.startOrResumeJob(jobId)
    }

    fun retryPage(page: PageEntity) {
        val stageToRetry = when {
            page.bubblesJson == "[]" || page.getBubbles().isEmpty() -> "DETECT"
            page.getBubbles().any { it.translated.isBlank() && it.text.isNotBlank() } -> "TRANSLATE"
            else -> "WIPE"
        }
        jobManager.retryPage(page.id, stageToRetry)
    }

    fun exportAllPages() {
        viewModelScope.launch {
            _isExporting.value = true
            val pages = db.jobDao().getPagesForJobDirect(jobId)
            val result = MediaStoreExporter.exportBatchToPictures(getApplication(), pages)
            _isExporting.value = false
            if (result.isSuccess) {
                _eventFlow.emit(JobEvent.ShowMessage("Exported ${result.getOrNull()} pages to Pictures/Bubbleforge"))
            } else {
                _eventFlow.emit(JobEvent.ShowMessage("Export failed: ${result.exceptionOrNull()?.message}"))
            }
        }
    }

    fun shareBatchZip() {
        viewModelScope.launch {
            _isExporting.value = true
            val pages = db.jobDao().getPagesForJobDirect(jobId)
            val result = MediaStoreExporter.createZipAndShare(getApplication(), jobId, pages)
            _isExporting.value = false
            if (result.isSuccess) {
                val intent = result.getOrNull()
                if (intent != null) {
                    _eventFlow.emit(JobEvent.LaunchShareIntent(intent))
                }
            } else {
                _eventFlow.emit(JobEvent.ShowMessage("ZIP creation failed: ${result.exceptionOrNull()?.message}"))
            }
        }
    }
}

sealed class JobEvent {
    data class ShowMessage(val message: String) : JobEvent()
    data class LaunchShareIntent(val intent: Intent) : JobEvent()
}
