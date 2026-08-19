package com.example.ui.settings

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.slots.ApiProvider
import com.example.data.slots.ApiSlot
import com.example.data.slots.SlotRole
import com.example.data.slots.SlotStorage
import com.example.net.GeminiClient
import com.example.net.OpenAiCompatibleClient
import com.example.utils.RunLogEntry
import com.example.utils.RunLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class SlotTestResult(
    val slotId: String,
    val isTesting: Boolean = false,
    val isSuccess: Boolean? = null,
    val message: String = ""
)

data class SettingsUiState(
    val slots: List<ApiSlot> = emptyList(),
    val targetLanguage: String = "English",
    val inpaintEngine: String = "FLAT_FILL", // FLAT_FILL, REPLICATE, FAL
    val runLogs: List<RunLogEntry> = emptyList(),
    val activeViewingLogText: String? = null,
    val activeViewingLogTitle: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val slotStorage = SlotStorage.getInstance(application)
    private val geminiClient = GeminiClient()
    private val openAiClient = OpenAiCompatibleClient()

    private val _testResults = MutableStateFlow<Map<String, SlotTestResult>>(emptyMap())
    val testResults: StateFlow<Map<String, SlotTestResult>> = _testResults.asStateFlow()

    private val _saveFeedback = MutableSharedFlow<String>()
    val saveFeedback: SharedFlow<String> = _saveFeedback.asSharedFlow()

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            slots = slotStorage.slots.value,
            runLogs = RunLogger.getRecentRuns(application)
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            slotStorage.slots.collect { slots ->
                _uiState.value = _uiState.value.copy(slots = slots)
            }
        }
        loadRunLogs()
    }

    fun loadRunLogs() {
        val logs = RunLogger.getRecentRuns(getApplication())
        _uiState.value = _uiState.value.copy(runLogs = logs)
    }

    fun openLogDetails(log: RunLogEntry) {
        val content = RunLogger.getRunLogContent(getApplication(), log.id)
        _uiState.value = _uiState.value.copy(
            activeViewingLogText = content,
            activeViewingLogTitle = "Log: Run #${log.jobId} (${log.formattedDate})"
        )
    }

    fun dismissLogDialog() {
        _uiState.value = _uiState.value.copy(
            activeViewingLogText = null,
            activeViewingLogTitle = null
        )
    }

    fun clearAllLogs() {
        RunLogger.clearLogs(getApplication())
        loadRunLogs()
    }

    fun getShareIntentForLog(logId: String): Intent? {
        return RunLogger.createShareIntentForLog(getApplication(), logId)
    }

    fun updateSlot(slot: ApiSlot) {
        slotStorage.updateSlot(slot)
    }

    fun addSlot(slot: ApiSlot) {
        slotStorage.addSlot(slot)
        viewModelScope.launch {
            _saveFeedback.emit("New slot '${slot.displayTitle}' added and saved.")
        }
    }

    fun deleteSlot(slotId: String) {
        slotStorage.deleteSlot(slotId)
        viewModelScope.launch {
            _saveFeedback.emit("Slot removed.")
        }
    }

    fun moveSlot(fromIndex: Int, toIndex: Int) {
        slotStorage.moveSlot(fromIndex, toIndex)
    }

    fun saveAllSettings() {
        slotStorage.saveSlots(_uiState.value.slots)
        val validCount = _uiState.value.slots.count { it.enabled && it.apiKey.isNotBlank() }
        viewModelScope.launch {
            _saveFeedback.emit("✓ Settings saved! ($validCount active API ${if (validCount == 1) "key" else "keys"} ready)")
        }
    }

    fun resetToDefaultRecommendedSlots() {
        val defaults = slotStorage.createDefaultSlots()
        slotStorage.saveSlots(defaults)
        viewModelScope.launch {
            _saveFeedback.emit("Reset to default slots (Priority #1 OpenRouter Nemotron + Priority #2 Gemini 2.0 Flash).")
        }
    }

    fun testSlotKey(slot: ApiSlot) {
        if (slot.apiKey.isBlank()) {
            _testResults.value = _testResults.value + (slot.id to SlotTestResult(
                slotId = slot.id,
                isTesting = false,
                isSuccess = false,
                message = "API key is empty"
            ))
            return
        }

        viewModelScope.launch {
            _testResults.value = _testResults.value + (slot.id to SlotTestResult(
                slotId = slot.id,
                isTesting = true
            ))

            val result = when (slot.provider) {
                ApiProvider.GEMINI -> geminiClient.testKey(slot.baseUrl, slot.apiKey)
                ApiProvider.GROQ, ApiProvider.OPENROUTER, ApiProvider.CUSTOM ->
                    openAiClient.testKey(slot.baseUrl, slot.apiKey, slot.provider)
            }

            if (result.isSuccess) {
                slotStorage.markSlotInvalidKey(slot.id, false)
                _testResults.value = _testResults.value + (slot.id to SlotTestResult(
                    slotId = slot.id,
                    isTesting = false,
                    isSuccess = true,
                    message = result.getOrNull() ?: "Success"
                ))
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Failed"
                _testResults.value = _testResults.value + (slot.id to SlotTestResult(
                    slotId = slot.id,
                    isTesting = false,
                    isSuccess = false,
                    message = errorMsg
                ))
            }
        }
    }
}
