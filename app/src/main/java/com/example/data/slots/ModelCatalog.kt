package com.example.data.slots

import com.example.net.HttpClientProvider
import com.squareup.moshi.Moshi
import java.io.File

/**
 * Representation of a model entry from a provider catalog.
 */
data class CatalogModel(
    val id: String,
    val displayName: String,
    val free: Boolean,
    val acceptsImage: Boolean,
    val textOnlyOutput: Boolean,
    val supportsJson: Boolean,
    val contextLength: Int,
    val providerId: String
)

/**
 * Pure JVM parser for catalog responses from OpenRouter, Groq, and Gemini.
 */
object ModelCatalogParser {
    private val moshi = Moshi.Builder().build()
    private val anyAdapter = moshi.adapter(Any::class.java)

    /** Audio/embedding/guard endpoint id markers — never chat-completable, so never candidates. */
    private val NON_CHAT_ID_MARKERS =
        listOf("whisper", "tts", "guard", "safety", "moderation", "embed")

    fun parseOpenRouter(json: String): List<CatalogModel> =
        parseOpenAiStyleCatalog(json, fixedProviderId = "openrouter")

    fun parseProxyCatalog(json: String): List<CatalogModel> =
        parseOpenAiStyleCatalog(json, fixedProviderId = null)

    private fun parseOpenAiStyleCatalog(
        json: String,
        fixedProviderId: String?
    ): List<CatalogModel> {
        val root = try {
            anyAdapter.fromJson(json)
        } catch (_: Exception) {
            return emptyList()
        }
        val data = (root as? List<*>)
            ?: ((root as? Map<*, *>)?.get("data") as? List<*>)
            ?: emptyList<Any>()

        val result = mutableListOf<CatalogModel>()
        for (item in data) {
            val obj = item as? Map<*, *> ?: continue
            val providerId = fixedProviderId
                ?: (obj["provider"] as? String)?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                ?: continue
            val id = obj["id"] as? String ?: continue
            if (id.isBlank()) continue
            val name = (obj["name"] as? String)?.takeIf { it.isNotBlank() } ?: id
            val pricing = obj["pricing"] as? Map<*, *>
            val isFree = isZeroPrice(pricing?.get("prompt")) && isZeroPrice(pricing?.get("completion"))

            val arch = obj["architecture"] as? Map<*, *>
            val inputMods = (arch?.get("input_modalities") as? List<*>)
                ?.mapNotNull { it?.toString()?.trim()?.lowercase() } ?: emptyList()
            val outputMods = (arch?.get("output_modalities") as? List<*>)
                ?.mapNotNull { it?.toString()?.trim()?.lowercase() } ?: emptyList()

            // A chat model must accept a text prompt; an image-only encoder would fail every call.
            if (!inputMods.contains("text")) continue

            val acceptsImage = inputMods.contains("image")
            val textOnlyOutput = outputMods.size == 1 && outputMods[0] == "text"

            val supportedParams = (obj["supported_parameters"] as? List<*>)
                ?.mapNotNull { it?.toString()?.trim() } ?: emptyList()
            val supportsJson = supportedParams.contains("response_format")

            val contextLength = (obj["context_length"] as? Number)?.toInt() ?: 0

            result.add(
                CatalogModel(
                    id = id,
                    displayName = name,
                    free = isFree,
                    acceptsImage = acceptsImage,
                    textOnlyOutput = textOnlyOutput,
                    supportsJson = supportsJson,
                    contextLength = contextLength,
                    providerId = providerId
                )
            )
        }
        return result
    }

    private fun isZeroPrice(value: Any?): Boolean = when (value) {
        is Number -> value.toDouble() == 0.0
        is String -> {
            val trimmed = value.trim()
            trimmed == "0" || trimmed == "0.0" || trimmed.toDoubleOrNull() == 0.0
        }
        else -> false
    }

    fun parseGroq(json: String): List<CatalogModel> {
        val root = try {
            anyAdapter.fromJson(json)
        } catch (_: Exception) {
            return emptyList()
        }
        val data = (root as? List<*>)
            ?: ((root as? Map<*, *>)?.get("data") as? List<*>)
            ?: emptyList<Any>()

        val result = mutableListOf<CatalogModel>()
        for (item in data) {
            val obj = item as? Map<*, *> ?: continue
            val id = obj["id"] as? String ?: continue
            if (id.isBlank()) continue
            val active = (obj["active"] as? Boolean) ?: true
            if (!active) continue
            // Audio/embedding/guard endpoints can never serve a translation prompt.
            val lowerId = id.lowercase()
            if (NON_CHAT_ID_MARKERS.any { lowerId.contains(it) }) continue

            val contextLength = (obj["context_window"] as? Number)?.toInt()
                ?: (obj["context_length"] as? Number)?.toInt()
                ?: 8192

            result.add(
                CatalogModel(
                    id = id,
                    displayName = id,
                    free = true,
                    acceptsImage = false,
                    textOnlyOutput = true,
                    supportsJson = true,
                    contextLength = contextLength,
                    providerId = "groq"
                )
            )
        }
        return result
    }

    fun parseGemini(json: String): List<CatalogModel> {
        val root = try {
            anyAdapter.fromJson(json)
        } catch (_: Exception) {
            return emptyList()
        }
        val data = (root as? List<*>)
            ?: ((root as? Map<*, *>)?.get("models") as? List<*>)
            ?: ((root as? Map<*, *>)?.get("data") as? List<*>)
            ?: emptyList<Any>()

        val result = mutableListOf<CatalogModel>()
        for (item in data) {
            val obj = item as? Map<*, *> ?: continue
            val rawName = obj["name"] as? String ?: continue
            val id = rawName.removePrefix("models/")
            if (id.isBlank()) continue

            val methods = (obj["supportedGenerationMethods"] as? List<*>)
                ?.mapNotNull { it?.toString() }
                ?: emptyList()
            if (methods.isNotEmpty() && !methods.contains("generateContent")) {
                continue
            }

            val displayName = (obj["displayName"] as? String)?.takeIf { it.isNotBlank() } ?: id
            val contextLength = (obj["inputTokenLimit"] as? Number)?.toInt() ?: 32768

            result.add(
                CatalogModel(
                    id = id,
                    displayName = displayName,
                    free = true,
                    acceptsImage = true,
                    textOnlyOutput = true,
                    supportsJson = true,
                    contextLength = contextLength,
                    providerId = "gemini"
                )
            )
        }
        return result
    }
}

/**
 * Filter and deterministic ranking logic for model catalogs.
 */
object ModelFilter {
    private val EXCLUDED_CATEGORIES = listOf("content-safety", "safety", "guard", "moderation")

    fun usable(
        models: List<CatalogModel>,
        needVision: Boolean,
        minContext: Int = 8192
    ): List<CatalogModel> {
        return models.filter { model ->
            // Gate (a): Free-ness
            model.free &&
            // Gate (b): Text-only output exactly
            model.textOnlyOutput &&
            // Gate (c): Classifier / moderation category exclusion
            !isCategoryExcluded(model.id) &&
            // Gate (d): Exclude openrouter/* meta-router ids
            !model.id.startsWith("openrouter/", ignoreCase = true) &&
            // Context length gate
            model.contextLength >= minContext &&
            // Vision gate: if vision required, must accept images
            (!needVision || model.acceptsImage)
        }
    }

    fun rank(
        models: List<CatalogModel>,
        pinned: List<String> = emptyList()
    ): List<CatalogModel> {
        val modelsById = models.groupBy { it.id }
        val pinnedSet = pinned.toSet()
        val pinnedResult = mutableListOf<CatalogModel>()
        for (id in pinned) {
            modelsById[id]?.let { pinnedResult.addAll(it) }
        }
        val unpinned = models.filter { it.id !in pinnedSet }
        val sortedUnpinned = unpinned.sortedWith(
            // Anonymous "cloak" models may be withdrawn at any moment and their prompts may be used
            // for training, so they rank last — ranked last, never removed.
            compareByDescending<CatalogModel> { !isAnonymousCloak(it.id) }
                .thenByDescending { it.supportsJson }
                // Soft quality ordering from the catalogue's own id tokens (`120b`, `2.6b`):
                // OpenRouter exposes no parameter count, so an id with no size token is treated as
                // unknown and outranks a known-small model. A preference, never a gate.
                .thenByDescending { advertisedSizeB(it.id) ?: UNKNOWN_SIZE_RANK }
                .thenByDescending { it.contextLength }
                .thenBy { it.id }
        )
        return pinnedResult + sortedUnpinned
    }

    /** OpenRouter's anonymous cloak models (ids under the `stealth/` namespace): volatile, and their prompts may train the model. */
    fun isAnonymousCloak(id: String): Boolean {
        val lower = id.lowercase()
        return lower.startsWith("stealth/") || lower.contains("/cloak")
    }

    /** Size in billions parsed from the model id's own tokens, or null when it advertises none. */
    fun advertisedSizeB(id: String): Double? =
        SIZE_TOKEN.find(id.substringBeforeLast(':').substringAfterLast('/'))
            ?.groupValues?.get(1)?.toDoubleOrNull()

    private const val UNKNOWN_SIZE_RANK = 50.0
    private val SIZE_TOKEN = Regex("(\\d+(?:\\.\\d+)?)b(?![a-z0-9])")

    fun isCategoryExcluded(id: String): Boolean {
        val lower = id.lowercase()
        return EXCLUDED_CATEGORIES.any { lower.contains(it) }
    }
}

/**
 * Local cache store for fetched catalog models. Backed by `model_catalog.json` with a 24h TTL.
 */
class ModelCatalogStore(private val dir: File) {
    private val catalogFile = File(dir, "model_catalog.json")
    private val moshi = Moshi.Builder().build()
    private val anyAdapter = moshi.adapter(Any::class.java)

    @Synchronized
    fun load(): List<CatalogModel> {
        if (!catalogFile.exists()) return emptyList()
        return try {
            val json = catalogFile.readText()
            val root = anyAdapter.fromJson(json) as? Map<*, *> ?: return emptyList()
            val modelsRaw = root["models"] as? List<*> ?: return emptyList()
            modelsRaw.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val id = map["id"] as? String ?: return@mapNotNull null
                val displayName = map["displayName"] as? String ?: id
                val free = map["free"] as? Boolean ?: false
                val acceptsImage = map["acceptsImage"] as? Boolean ?: false
                val textOnlyOutput = map["textOnlyOutput"] as? Boolean ?: true
                val supportsJson = map["supportsJson"] as? Boolean ?: false
                val contextLength = (map["contextLength"] as? Number)?.toInt() ?: 0
                val providerId = map["providerId"] as? String ?: ""
                CatalogModel(
                    id = id,
                    displayName = displayName,
                    free = free,
                    acceptsImage = acceptsImage,
                    textOnlyOutput = textOnlyOutput,
                    supportsJson = supportsJson,
                    contextLength = contextLength,
                    providerId = providerId
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(models: List<CatalogModel>) {
        try {
            dir.mkdirs()
            val modelsData = models.map { m ->
                mapOf(
                    "id" to m.id,
                    "displayName" to m.displayName,
                    "free" to m.free,
                    "acceptsImage" to m.acceptsImage,
                    "textOnlyOutput" to m.textOnlyOutput,
                    "supportsJson" to m.supportsJson,
                    "contextLength" to m.contextLength,
                    "providerId" to m.providerId
                )
            }
            val data = mapOf(
                "fetchedAt" to System.currentTimeMillis(),
                "models" to modelsData
            )
            catalogFile.writeText(anyAdapter.toJson(data))
        } catch (_: Exception) {
            // Ignore write failures to never throw into the pipeline
        }
    }

    /** Epoch millis of the last successful fetch, or 0 when no catalogue has ever been written. */
    fun lastFetchedEpochMs(): Long =
        if (catalogFile.exists()) catalogFile.lastModified() else 0L

    fun isFresh(ttlMs: Long = 24 * 60 * 60 * 1000L): Boolean {
        if (!catalogFile.exists()) return false
        return try {
            val json = catalogFile.readText()
            val root = anyAdapter.fromJson(json) as? Map<*, *> ?: return false
            val fetchedAt = (root["fetchedAt"] as? Number)?.toLong() ?: return false
            val now = System.currentTimeMillis()
            (now - fetchedAt) in 0 until ttlMs
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Thin HTTP fetcher for live model catalogs from OpenRouter, Groq, and Gemini.
 * Never throws into the pipeline or blocks a job.
 */
object ModelCatalogFetcher {
    fun fetchOpenRouter(): List<CatalogModel> {
        return try {
            val request = okhttp3.Request.Builder()
                .url("https://openrouter.ai/api/v1/models")
                .addHeader("HTTP-Referer", "https://github.com/bubbleforge/bubbleforge")
                .addHeader("X-Title", "Bubbleforge")
                .get()
                .build()
            val response = HttpClientProvider.client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            ModelCatalogParser.parseOpenRouter(body)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun fetchGroq(apiKey: String): List<CatalogModel> {
        if (apiKey.isBlank()) return emptyList()
        return try {
            val request = okhttp3.Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .get()
                .build()
            val response = HttpClientProvider.client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            ModelCatalogParser.parseGroq(body)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun fetchGemini(apiKey: String): List<CatalogModel> {
        if (apiKey.isBlank()) return emptyList()
        return try {
            val request = okhttp3.Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey.trim()}")
                .get()
                .build()
            val response = HttpClientProvider.client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            ModelCatalogParser.parseGemini(body)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun fetchProxyCatalog(baseUrl: String, apiKey: String): List<CatalogModel> {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val cleanKey = apiKey.trim()
        if (cleanBase.isBlank() || cleanKey.isBlank()) return emptyList()
        return try {
            val request = okhttp3.Request.Builder()
                .url("$cleanBase/models")
                .addHeader("Authorization", "Bearer $cleanKey")
                .get()
                .build()
            HttpClientProvider.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string().orEmpty()
                ModelCatalogParser.parseProxyCatalog(body)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun fetchAll(
        store: ModelCatalogStore,
        groqKey: String = "",
        geminiKey: String = "",
        proxyEndpoints: List<Pair<String, String>> = emptyList()
    ): List<CatalogModel> {
        val cached = store.load()
        return try {
            val proxyModels = proxyEndpoints.flatMap { (baseUrl, apiKey) ->
                fetchProxyCatalog(baseUrl, apiKey)
            }
            val orModels = fetchOpenRouter()
            val groqModels = if (groqKey.isNotBlank()) fetchGroq(groqKey) else emptyList()
            val geminiModels = if (geminiKey.isNotBlank()) fetchGemini(geminiKey) else emptyList()

            val cachedByProvider = cached.groupBy { it.providerId.lowercase() }
            val finalOr = if (orModels.isNotEmpty()) orModels else cachedByProvider["openrouter"].orEmpty()
            val finalGroq = if (groqModels.isNotEmpty()) groqModels else cachedByProvider["groq"].orEmpty()
            val finalGemini = if (geminiModels.isNotEmpty()) geminiModels else cachedByProvider["gemini"].orEmpty()

            val combined = (proxyModels + finalOr + finalGroq + finalGemini)
                .distinctBy { it.providerId + "|" + it.id }
            if (combined.isNotEmpty()) {
                store.save(combined)
                combined
            } else {
                cached
            }
        } catch (_: Throwable) {
            cached
        }
    }
}

/**
 * Migration helper to check stored slots against the cached catalog.
 */
object ModelCatalogMigration {
    fun applyRetirementMigration(
        slots: List<ApiSlot>,
        catalog: List<CatalogModel>
    ): Pair<List<ApiSlot>, Boolean> {
        if (catalog.isEmpty()) {
            return Pair(slots, false)
        }

        val catalogProviders = catalog.map { it.providerId.lowercase() }.toSet()
        val catalogModelIdsByProvider = catalog.groupBy { it.providerId.lowercase() }
            .mapValues { entry -> entry.value.map { it.id }.toSet() }

        var modified = false
        val updated = slots.map { slot ->
            val providerKey = slot.provider.name.lowercase()
            if (providerKey in catalogProviders) {
                val providerModelIds = catalogModelIdsByProvider[providerKey].orEmpty()
                val isRetired = !providerModelIds.contains(slot.model)
                if (slot.isModelRetired != isRetired) {
                    modified = true
                    slot.copy(isModelRetired = isRetired)
                } else {
                    slot
                }
            } else {
                slot
            }
        }

        return Pair(updated, modified)
    }

    /**
     * One-time repair of the app's OWN shipped defaults that upstream has since retired. Only ids
     * this app shipped are touched, and only to the id the app now ships — a model the user chose by
     * hand is never silently replaced. Runs offline (no catalogue needed), so a phone that has not
     * fetched a catalogue yet still recovers the moment it updates.
     */
    fun applyKnownRetiredRepair(slots: List<ApiSlot>): Pair<List<ApiSlot>, Boolean> {
        var modified = false
        val updated = slots.map { slot ->
            val replacement = KNOWN_RETIRED_DEFAULTS[slot.model.trim()]
            if (replacement != null) {
                modified = true
                slot.copy(model = replacement, isModelRetired = false)
            } else {
                slot
            }
        }
        return Pair(updated, modified)
    }

    /**
     * Shipped default → the id the app ships in its place. Groq decommissioned the llama 3.x ids on
     * 2026-08-16 and delisted Gemma 2; the OpenRouter `:free` variants below are absent from the live
     * catalogue. Verified against both provider catalogues, not assumed. This is a code migration
     * for ids this app shipped — not a pricing or availability snapshot.
     */
    val KNOWN_RETIRED_DEFAULTS: Map<String, String> = mapOf(
        "llama-3.3-70b-versatile" to "openai/gpt-oss-120b",
        "llama-3.1-8b-instant" to "openai/gpt-oss-120b",
        "gemma2-9b-it" to "openai/gpt-oss-20b",
        "meta-llama/llama-3.3-70b-instruct:free" to "nvidia/nemotron-3.5-super-vl:free",
        "google/gemini-2.0-flash-exp:free" to "nvidia/nemotron-3.5-super-vl:free",
        "qwen/qwen-2.5-vl-72b-instruct:free" to "nvidia/nemotron-3.5-super-vl:free",
        "deepseek/deepseek-chat:free" to "nvidia/nemotron-3.5-super-vl:free"
    )
}
