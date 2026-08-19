package com.example.ui.home

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.db.JobEntity
import com.example.data.db.PageEntity
import com.example.data.db.PageStatus
import com.example.data.slots.ApiSlot
import com.example.data.slots.SlotRole
import com.example.data.slots.SlotStorage
import com.example.pipeline.image.ImageScaler
import com.example.utils.ZipHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SelectedImageItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: Uri,
    val name: String = "",
    val sizeBytes: Long = 0L
)

data class HomeUiState(
    val selectedImages: List<SelectedImageItem> = emptyList(),
    val slots: List<ApiSlot> = emptyList(),
    val isCreatingJob: Boolean = false,
    val recentJobs: List<JobEntity> = emptyList()
) {
    val configuredSlots: List<ApiSlot>
        get() = slots.filter { it.enabled && it.apiKey.isNotBlank() && !it.isInvalidKey }

    val visionSlots: List<ApiSlot>
        get() = configuredSlots.filter { (it.role == SlotRole.DETECT_OCR || it.role == SlotRole.ANY) && it.isVisionSupported }

    val translateSlots: List<ApiSlot>
        get() = configuredSlots.filter { it.role == SlotRole.TRANSLATE || it.role == SlotRole.ANY }

    val hasVisionKey: Boolean
        get() = visionSlots.isNotEmpty()

    val hasTranslateKey: Boolean
        get() = translateSlots.isNotEmpty()

    val canTranslate: Boolean
        get() = selectedImages.isNotEmpty() && hasVisionKey && hasTranslateKey && !isCreatingJob

    val keyStatusMessage: String
        get() = when {
            configuredSlots.isEmpty() -> "No active API keys detected. Tap here to configure Gemini or OpenRouter in Settings."
            !hasVisionKey && !hasTranslateKey -> "Key entered, but no active role matched. Please enable vision or translate roles."
            !hasVisionKey -> "Translation key ready (${translateSlots.firstOrNull()?.displayTitle ?: "Ready"}). Add a Vision key (e.g. Gemini 2.0 Flash) for Detect+OCR."
            !hasTranslateKey -> "Vision key ready (${visionSlots.firstOrNull()?.displayTitle ?: "Ready"}). Add a Translation key (e.g. Nemotron / Gemini) for text translation."
            else -> {
                val primaryVision = visionSlots.first().displayTitle
                val primaryTranslate = translateSlots.first().displayTitle
                "Ready! Detect+OCR: $primaryVision · Translate: $primaryTranslate"
            }
        }
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val slotStorage = SlotStorage.getInstance(application)

    private val _selectedImages = MutableStateFlow<List<SelectedImageItem>>(emptyList())
    private val _isCreatingJob = MutableStateFlow(false)
    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    val uiState: StateFlow<HomeUiState> = combine(
        _selectedImages,
        slotStorage.slots,
        _isCreatingJob,
        db.jobDao().getAllJobs()
    ) { images, slots, isCreating, recentJobs ->
        HomeUiState(
            selectedImages = images,
            slots = slots,
            isCreatingJob = isCreating,
            recentJobs = recentJobs.take(5)
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState(slots = slotStorage.slots.value)
    )

    fun onImagesSelected(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val currentList = _selectedImages.value.toMutableList()
            var skippedCount = 0

            val maxCap = 30
            val maxSizeBytes = 8 * 1024 * 1024L // 8 MB

            for (uri in uris) {
                if (currentList.size >= maxCap) break

                var fileSize = 0L
                var fileName = "Page ${currentList.size + 1}"

                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst()) {
                            if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
                            if (nameIdx != -1) fileName = cursor.getString(nameIdx) ?: fileName
                        }
                    }
                } catch (e: Exception) {
                    // Fallback
                }

                if (fileSize > maxSizeBytes) {
                    skippedCount++
                    continue
                }

                // Check if already in list
                if (currentList.none { it.uri == uri }) {
                    currentList.add(SelectedImageItem(uri = uri, name = fileName, sizeBytes = fileSize))
                }
            }

            _selectedImages.value = currentList.take(maxCap)

            if (skippedCount > 0) {
                _snackbarMessage.emit("Skipped $skippedCount file(s) exceeding 8 MB size limit.")
            }
            if (uris.size + currentList.size > maxCap && currentList.size == maxCap) {
                _snackbarMessage.emit("Maximum 30 pages reached.")
            }
        }
    }

    fun onZipFileSelected(uri: Uri) {
        viewModelScope.launch {
            _isCreatingJob.value = true
            val result = ZipHelper.importPagesFromZip(getApplication(), uri, maxPages = 30)
            _isCreatingJob.value = false
            if (result.isSuccess) {
                val pages = result.getOrNull().orEmpty()
                _selectedImages.value = pages
                _snackbarMessage.emit("Imported ${pages.size} manga pages from ZIP archive in order.")
            } else {
                _snackbarMessage.emit("ZIP import failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun removeImage(id: String) {
        _selectedImages.value = _selectedImages.value.filter { it.id != id }
    }

    fun moveImage(fromIndex: Int, toIndex: Int) {
        val current = _selectedImages.value.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices && fromIndex != toIndex) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            _selectedImages.value = current
        }
    }

    fun clearImages() {
        _selectedImages.value = emptyList()
    }

    fun startTranslationJob(onJobCreated: (Long) -> Unit) {
        val images = _selectedImages.value
        if (images.isEmpty()) return

        viewModelScope.launch {
            _isCreatingJob.value = true
            try {
                val jobId = withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val jobEntity = JobEntity(
                        pageCount = images.size,
                        status = "RUNNING",
                        targetLang = "en"
                    )
                    val newJobId = db.jobDao().insertJob(jobEntity)

                    val pagesDir = File(context.cacheDir, "job_$newJobId").apply { mkdirs() }
                    val pageEntities = mutableListOf<PageEntity>()

                    images.forEachIndexed { index, item ->
                        val origFile = File(pagesDir, "orig_${index + 1}.png")
                        ImageScaler.loadAndCacheOriginal(context, item.uri, origFile)

                        pageEntities.add(
                            PageEntity(
                                jobId = newJobId,
                                pageIndex = index,
                                originalImageUri = item.uri.toString(),
                                originalCachePath = origFile.absolutePath,
                                status = PageStatus.QUEUED
                            )
                        )
                    }

                    db.jobDao().insertPages(pageEntities)
                    newJobId
                }

                _selectedImages.value = emptyList()
                _isCreatingJob.value = false
                onJobCreated(jobId)
            } catch (e: Exception) {
                _isCreatingJob.value = false
                _snackbarMessage.emit("Failed to create job: ${e.message}")
            }
        }
    }

    fun deleteJob(jobId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            db.jobDao().deleteJob(jobId)
        }
    }
}
