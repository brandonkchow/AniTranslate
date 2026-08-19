package com.example.ui.editor

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.db.PageEntity
import com.example.data.models.Bubble
import com.example.pipeline.image.ImageScaler
import com.example.pipeline.typeset.Typesetter
import com.example.pipeline.wipe.FlatWiper
import com.example.utils.MediaStoreExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ViewMode(val displayName: String) {
    ORIGINAL("Original"),
    WIPED("Wiped"),
    FINAL("Final")
}

data class EditorUiState(
    val page: PageEntity? = null,
    val bubbles: List<Bubble> = emptyList(),
    val selectedBubbleId: Int? = null,
    val viewMode: ViewMode = ViewMode.FINAL,
    val isRendering: Boolean = false,
    val isExporting: Boolean = false,
    val allPagesInJob: List<PageEntity> = emptyList(),
    val currentImageFile: File? = null
) {
    val selectedBubble: Bubble?
        get() = bubbles.find { it.id == selectedBubbleId }
}

class EditorViewModel(
    application: Application,
    private val pageId: Long
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _eventFlow = MutableSharedFlow<EditorEvent>()
    val eventFlow: SharedFlow<EditorEvent> = _eventFlow.asSharedFlow()

    init {
        loadPage(pageId)
    }

    fun loadPage(targetPageId: Long) {
        viewModelScope.launch {
            val page = db.jobDao().getPageByIdDirect(targetPageId) ?: return@launch
            val allPages = db.jobDao().getPagesForJobDirect(page.jobId)
            val bubbles = page.getBubbles()

            val initialFile = when (_uiState.value.viewMode) {
                ViewMode.ORIGINAL -> File(page.originalCachePath)
                ViewMode.WIPED -> if (page.wipedImagePath.isNotBlank()) File(page.wipedImagePath) else File(page.originalCachePath)
                ViewMode.FINAL -> if (page.finalImagePath.isNotBlank()) File(page.finalImagePath) else File(page.originalCachePath)
            }

            _uiState.value = _uiState.value.copy(
                page = page,
                bubbles = bubbles,
                allPagesInJob = allPages,
                currentImageFile = initialFile
            )
        }
    }

    fun setViewMode(mode: ViewMode) {
        val page = _uiState.value.page ?: return
        val file = when (mode) {
            ViewMode.ORIGINAL -> File(page.originalCachePath)
            ViewMode.WIPED -> if (page.wipedImagePath.isNotBlank()) File(page.wipedImagePath) else File(page.originalCachePath)
            ViewMode.FINAL -> if (page.finalImagePath.isNotBlank()) File(page.finalImagePath) else File(page.originalCachePath)
        }
        _uiState.value = _uiState.value.copy(viewMode = mode, currentImageFile = file)
    }

    fun selectBubble(id: Int?) {
        _uiState.value = _uiState.value.copy(selectedBubbleId = id)
    }

    fun updateBubbleText(id: Int, newTranslated: String) {
        val updated = _uiState.value.bubbles.map {
            if (it.id == id) it.copy(translated = newTranslated) else it
        }
        _uiState.value = _uiState.value.copy(bubbles = updated)
        reRenderLocally()
    }

    fun updateBubbleBox(id: Int, newBox: List<Float>) {
        val updated = _uiState.value.bubbles.map {
            if (it.id == id) it.copy(box = newBox) else it
        }
        _uiState.value = _uiState.value.copy(bubbles = updated)
        reRenderLocally()
    }

    fun toggleBubbleVisibility(id: Int) {
        val updated = _uiState.value.bubbles.map {
            if (it.id == id) it.copy(visible = !it.visible) else it
        }
        _uiState.value = _uiState.value.copy(bubbles = updated)
        reRenderLocally()
    }

    fun adjustFontSize(id: Int, deltaSp: Float) {
        val updated = _uiState.value.bubbles.map {
            if (it.id == id) it.copy(fontSizeSp = (it.fontSizeSp + deltaSp).coerceIn(8f, 36f)) else it
        }
        _uiState.value = _uiState.value.copy(bubbles = updated)
        reRenderLocally()
    }

    fun deleteBubble(id: Int) {
        val updated = _uiState.value.bubbles.filter { it.id != id }
        _uiState.value = _uiState.value.copy(bubbles = updated, selectedBubbleId = null)
        reRenderLocally()
    }

    fun addNewBubble(x: Float = 0.3f, y: Float = 0.3f, width: Float = 0.2f, height: Float = 0.15f) {
        val newId = (_uiState.value.bubbles.maxOfOrNull { it.id } ?: 0) + 1
        val newBubble = Bubble(
            id = newId,
            text = "新テキスト",
            box = listOf(x, y, (x + width).coerceAtMost(0.95f), (y + height).coerceAtMost(0.95f)),
            translated = "New speech",
            fontSizeSp = 14f,
            visible = true
        )
        val updated = _uiState.value.bubbles + newBubble
        _uiState.value = _uiState.value.copy(bubbles = updated, selectedBubbleId = newId)
        reRenderLocally()
    }

    private fun reRenderLocally() {
        val page = _uiState.value.page ?: return
        val bubbles = _uiState.value.bubbles
        val origFile = File(page.originalCachePath)
        if (!origFile.exists()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRendering = true)
            withContext(Dispatchers.IO) {
                val context = getApplication<Application>()
                val origBmp = ImageScaler.loadBitmapFromFile(origFile) ?: return@withContext

                // Wipe
                val wipedBmp = FlatWiper.wipeBubbles(origBmp, bubbles)
                val wipedFile = File(context.cacheDir, "wiped_${page.id}.png")
                ImageScaler.saveBitmapPng(wipedBmp, wipedFile)

                // Typeset
                val finalBmp = Typesetter.typesetBubbles(context, wipedBmp, bubbles)
                val finalFile = File(context.cacheDir, "final_${page.id}.png")
                ImageScaler.saveBitmapPng(finalBmp, finalFile)

                // Sidecar JSON
                val sidecarFile = File(context.cacheDir, "sidecar_${page.id}.json")
                sidecarFile.writeText(Bubble.listToJsonString(bubbles))

                val updatedPage = page.copy(
                    bubblesJson = Bubble.listToJsonString(bubbles),
                    wipedImagePath = wipedFile.absolutePath,
                    finalImagePath = finalFile.absolutePath
                )
                db.jobDao().updatePage(updatedPage)

                val activeFile = when (_uiState.value.viewMode) {
                    ViewMode.ORIGINAL -> origFile
                    ViewMode.WIPED -> wipedFile
                    ViewMode.FINAL -> finalFile
                }

                _uiState.value = _uiState.value.copy(
                    page = updatedPage,
                    currentImageFile = activeFile,
                    isRendering = false
                )
            }
        }
    }

    fun exportCurrentPage() {
        val page = _uiState.value.page ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExporting = true)
            val result = MediaStoreExporter.exportPageToPictures(getApplication(), page)
            _uiState.value = _uiState.value.copy(isExporting = false)
            if (result.isSuccess) {
                _eventFlow.emit(EditorEvent.ShowMessage("Saved page to Pictures/Bubbleforge"))
            } else {
                _eventFlow.emit(EditorEvent.ShowMessage("Export failed: ${result.exceptionOrNull()?.message}"))
            }
        }
    }

    fun exportAllPagesInJob() {
        val pages = _uiState.value.allPagesInJob
        if (pages.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExporting = true)
            val result = MediaStoreExporter.exportBatchToPictures(getApplication(), pages)
            _uiState.value = _uiState.value.copy(isExporting = false)
            if (result.isSuccess) {
                _eventFlow.emit(EditorEvent.ShowMessage("Exported ${result.getOrNull()} pages to Pictures/Bubbleforge"))
            } else {
                _eventFlow.emit(EditorEvent.ShowMessage("Export failed: ${result.exceptionOrNull()?.message}"))
            }
        }
    }

    fun shareBatchZip() {
        val page = _uiState.value.page ?: return
        val pages = _uiState.value.allPagesInJob
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExporting = true)
            val result = MediaStoreExporter.createZipAndShare(getApplication(), page.jobId, pages)
            _uiState.value = _uiState.value.copy(isExporting = false)
            if (result.isSuccess) {
                val intent = result.getOrNull()
                if (intent != null) {
                    _eventFlow.emit(EditorEvent.LaunchShareIntent(intent))
                }
            } else {
                _eventFlow.emit(EditorEvent.ShowMessage("ZIP failed: ${result.exceptionOrNull()?.message}"))
            }
        }
    }
}

sealed class EditorEvent {
    data class ShowMessage(val message: String) : EditorEvent()
    data class LaunchShareIntent(val intent: Intent) : EditorEvent()
}
