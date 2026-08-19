package com.example.data.slots

import org.json.JSONObject
import java.util.UUID

enum class ApiProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val isVisionCapable: Boolean,
    val recommendedModels: List<Pair<String, String>> = emptyList() // Pair<ModelId, DisplayLabel>
) {
    GEMINI(
        displayName = "Gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        defaultModel = "gemini-2.5-flash",
        isVisionCapable = true,
        recommendedModels = listOf(
            "gemini-2.5-flash" to "Gemini 2.5 Flash (Recommended · Multimodal)",
            "gemini-2.5-flash-image" to "Gemini 2.5 Flash Image (Vision Optimized)",
            "gemini-3.5-flash" to "Gemini 3.5 Flash (High Speed · Multimodal)",
            "gemini-3.1-pro-preview" to "Gemini 3.1 Pro Preview (Complex Reasoning)"
        )
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "nvidia/nemotron-3-super-120b-a12b:free",
        isVisionCapable = true,
        recommendedModels = listOf(
            "nvidia/nemotron-3-super-120b-a12b:free" to "NVIDIA: Nemotron 3 Super (Free · 120B Translate)",
            "google/gemini-2.0-flash-exp:free" to "Gemini 2.0 Flash Exp (Free · Vision + Translate)",
            "meta-llama/llama-3.3-70b-instruct:free" to "Meta: Llama 3.3 70B Instruct (Free)",
            "qwen/qwen-2.5-vl-72b-instruct:free" to "Qwen 2.5 VL 72B (Free · Vision + Translate)",
            "deepseek/deepseek-chat:free" to "DeepSeek V3 (Free · Translate)",
            "openai/gpt-4o-mini" to "OpenAI: GPT-4o Mini (Paid)"
        )
    ),
    GROQ(
        displayName = "Groq",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        defaultModel = "llama-3.3-70b-versatile",
        isVisionCapable = false, // Groq is text-only — never send images to Groq
        recommendedModels = listOf(
            "llama-3.3-70b-versatile" to "Llama 3.3 70B Versatile (Fast & Free)",
            "llama-3.1-8b-instant" to "Llama 3.1 8B Instant (Free)",
            "gemma2-9b-it" to "Gemma 2 9B IT (Free)"
        )
    ),
    CUSTOM(
        displayName = "Custom",
        defaultBaseUrl = "",
        defaultModel = "",
        isVisionCapable = true,
        recommendedModels = emptyList()
    );

    companion object {
        fun fromString(value: String): ApiProvider {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: GEMINI
        }
    }
}

enum class SlotRole(val displayName: String) {
    DETECT_OCR("Detect+OCR"),
    TRANSLATE("Translate"),
    ANY("Any");

    companion object {
        fun fromString(value: String): SlotRole {
            return entries.find { it.name.equals(value, ignoreCase = true) || it.displayName.equals(value, ignoreCase = true) } ?: ANY
        }
    }
}

data class ApiSlot(
    val id: String = UUID.randomUUID().toString(),
    val provider: ApiProvider = ApiProvider.GEMINI,
    val label: String = "",
    val apiKey: String = "",
    val model: String = provider.defaultModel,
    val baseUrl: String = provider.defaultBaseUrl,
    val role: SlotRole = SlotRole.ANY,
    val enabled: Boolean = true,
    val isInvalidKey: Boolean = false,
    val cooldownUntilEpochMs: Long = 0L,
    val consecutiveRateLimits: Int = 0
) {
    val displayTitle: String
        get() = if (label.isNotBlank()) label else "${provider.displayName} ($model)"

    val isVisionSupported: Boolean
        get() = provider.isVisionCapable

    fun isCoolingDown(nowMs: Long = System.currentTimeMillis()): Boolean {
        return cooldownUntilEpochMs > nowMs
    }

    fun remainingCooldownSeconds(nowMs: Long = System.currentTimeMillis()): Long {
        val diff = cooldownUntilEpochMs - nowMs
        return if (diff > 0) (diff + 999) / 1000 else 0L
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("provider", provider.name)
            put("label", label)
            put("apiKey", apiKey)
            put("model", model)
            put("baseUrl", baseUrl)
            put("role", role.name)
            put("enabled", enabled)
            put("isInvalidKey", isInvalidKey)
            put("cooldownUntilEpochMs", cooldownUntilEpochMs)
            put("consecutiveRateLimits", consecutiveRateLimits)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ApiSlot {
            val provider = ApiProvider.fromString(json.optString("provider", ApiProvider.GEMINI.name))
            val role = SlotRole.fromString(json.optString("role", SlotRole.ANY.name))
            return ApiSlot(
                id = json.optString("id", UUID.randomUUID().toString()),
                provider = provider,
                label = json.optString("label", ""),
                apiKey = json.optString("apiKey", ""),
                model = json.optString("model", provider.defaultModel),
                baseUrl = json.optString("baseUrl", provider.defaultBaseUrl),
                role = role,
                enabled = json.optBoolean("enabled", true),
                isInvalidKey = json.optBoolean("isInvalidKey", false),
                cooldownUntilEpochMs = json.optLong("cooldownUntilEpochMs", 0L),
                consecutiveRateLimits = json.optInt("consecutiveRateLimits", 0)
            )
        }
    }
}
