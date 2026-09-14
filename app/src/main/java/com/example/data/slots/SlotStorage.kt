package com.example.data.slots

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class SlotStorage private constructor(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "bubbleforge_secure_slots",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w("SlotStorage", "EncryptedSharedPreferences failed, fallback to standard prefs", e)
            context.getSharedPreferences("bubbleforge_slots_fallback", Context.MODE_PRIVATE)
        }
    }

    private val _slots = MutableStateFlow<List<ApiSlot>>(emptyList())
    val slots: StateFlow<List<ApiSlot>> = _slots.asStateFlow()

    init {
        loadSlots()
    }

    fun loadSlots(): List<ApiSlot> {
        val envGeminiKey = getEnvGeminiKey()
        val jsonStr = prefs.getString(KEY_SLOTS, null)
        var loaded = if (jsonStr.isNullOrBlank()) {
            val defaults = createDefaultSlots()
            saveSlotsInternal(defaults)
            defaults
        } else {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<ApiSlot>()
                for (i in 0 until array.length()) {
                    list.add(ApiSlot.fromJson(array.getJSONObject(i)))
                }
                list
            } catch (e: Exception) {
                Log.e("SlotStorage", "Error parsing slots json", e)
                val defaults = createDefaultSlots()
                saveSlotsInternal(defaults)
                defaults
            }
        }

        // Migrate deprecated gemini models (e.g. gemini-2.0-flash / gemini-1.5-flash) and auto-inject env key
        var modified = false
        loaded = loaded.map { slot ->
            var updated = slot
            if (slot.provider == ApiProvider.GEMINI) {
                if (slot.model == "gemini-2.0-flash" || slot.model == "gemini-1.5-flash" || slot.model == "gemini-1.5-pro") {
                    updated = updated.copy(
                        model = "gemini-2.5-flash",
                        label = if (slot.label.contains("Gemini 2.0") || slot.label.contains("Gemini 1.5")) "Gemini 2.5 Flash" else slot.label
                    )
                    modified = true
                }
                if (updated.apiKey.isBlank() && envGeminiKey.isNotBlank()) {
                    updated = updated.copy(apiKey = envGeminiKey)
                    modified = true
                }
            }
            updated
        }

        if (modified) {
            saveSlotsInternal(loaded)
        }

        _slots.value = loaded
        return loaded
    }

    private fun getEnvGeminiKey(): String {
        return try {
            val key = com.example.BuildConfig.GEMINI_API_KEY
            if (key.isNotBlank() && key != "MY_GEMINI_API_KEY") key.trim() else ""
        } catch (e: Throwable) {
            ""
        }
    }

    fun createDefaultSlots(): List<ApiSlot> {
        val envGeminiKey = getEnvGeminiKey()
        return listOf(
            ApiSlot(
                provider = ApiProvider.OPENROUTER,
                label = "NVIDIA: Nemotron 3 Super (free)",
                apiKey = "",
                model = "nvidia/nemotron-3-super-120b-a12b:free",
                baseUrl = "https://openrouter.ai/api/v1",
                role = SlotRole.TRANSLATE,
                enabled = true
            ),
            ApiSlot(
                provider = ApiProvider.GEMINI,
                label = "Gemini 2.5 Flash",
                apiKey = envGeminiKey,
                model = "gemini-2.5-flash",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                role = SlotRole.ANY,
                enabled = true
            ),
            ApiSlot(
                provider = ApiProvider.GROQ,
                label = "Groq: Llama 3.3 70B (Fast & Free)",
                apiKey = "",
                model = "llama-3.3-70b-versatile",
                baseUrl = "https://api.groq.com/openai/v1",
                role = SlotRole.TRANSLATE,
                enabled = true
            ),
            ApiSlot(
                provider = ApiProvider.WORKSTATION,
                label = "Workstation (RTX 5080 via Tailscale)",
                apiKey = "",
                model = "manga-image-translator",
                baseUrl = "http://100.110.101.42:5003",
                role = SlotRole.ANY,
                enabled = false
            )
        )
    }

    @Synchronized
    fun saveSlots(newSlots: List<ApiSlot>) {
        saveSlotsInternal(newSlots)
        _slots.value = newSlots
    }

    private fun saveSlotsInternal(newSlots: List<ApiSlot>) {
        val array = JSONArray()
        for (slot in newSlots) {
            array.put(slot.toJson())
        }
        prefs.edit().putString(KEY_SLOTS, array.toString()).apply()
    }

    fun updateSlot(slot: ApiSlot) {
        val current = _slots.value.toMutableList()
        val index = current.indexOfFirst { it.id == slot.id }
        if (index >= 0) {
            current[index] = slot
        } else {
            current.add(slot)
        }
        saveSlots(current)
    }

    fun addSlot(slot: ApiSlot) {
        val current = _slots.value.toMutableList()
        current.add(slot)
        saveSlots(current)
    }

    fun deleteSlot(slotId: String) {
        val current = _slots.value.filter { it.id != slotId }
        saveSlots(current)
    }

    fun moveSlot(fromIndex: Int, toIndex: Int) {
        val current = _slots.value.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices && fromIndex != toIndex) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            saveSlots(current)
        }
    }

    fun setSlotCooldown(slotId: String, cooldownDurationMs: Long, incrementRateLimits: Boolean = true) {
        val now = System.currentTimeMillis()
        val current = _slots.value.map { slot ->
            if (slot.id == slotId) {
                val newRateLimits = if (incrementRateLimits) slot.consecutiveRateLimits + 1 else slot.consecutiveRateLimits
                slot.copy(
                    cooldownUntilEpochMs = now + cooldownDurationMs,
                    consecutiveRateLimits = newRateLimits
                )
            } else slot
        }
        saveSlots(current)
    }

    fun resetSlotCooldown(slotId: String) {
        val current = _slots.value.map { slot ->
            if (slot.id == slotId) {
                slot.copy(cooldownUntilEpochMs = 0L, consecutiveRateLimits = 0)
            } else slot
        }
        saveSlots(current)
    }

    fun markSlotInvalidKey(slotId: String, isInvalid: Boolean = true) {
        val current = _slots.value.map { slot ->
            if (slot.id == slotId) {
                slot.copy(isInvalidKey = isInvalid)
            } else slot
        }
        saveSlots(current)
    }

    companion object {
        private const val KEY_SLOTS = "api_slots_v1"

        @Volatile
        private var INSTANCE: SlotStorage? = null

        fun getInstance(context: Context): SlotStorage {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SlotStorage(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
