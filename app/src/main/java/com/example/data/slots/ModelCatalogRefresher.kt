package com.example.data.slots

/**
 * The one and only place the live model catalogue is fetched.
 *
 * `ModelCatalogFetcher` previously had **no call site anywhere in the app**, which silently reduced
 * the free-model walk to a no-op: the candidate list collapsed to the single pinned model and the
 * Settings UI had nothing to show. Every refresh therefore routes through here, and a test asserts
 * that the fetch is genuinely invoked — the coverage that was missing before.
 *
 * Blocking (OkHttp under the hood): call it off the main thread.
 */
object ModelCatalogRefresher {

    /** Matches the store's own freshness window. */
    const val TTL_MS = 24 * 60 * 60 * 1000L

    data class Outcome(
        val models: List<CatalogModel>,
        val fetched: Boolean,
        val detail: String
    )

    /** Groq and Gemini catalogues need a key; OpenRouter's is keyless. */
    fun keysFrom(slots: List<ApiSlot>): Pair<String, String> {
        val groq = slots.firstOrNull { it.provider == ApiProvider.GROQ && it.apiKey.isNotBlank() }
            ?.apiKey.orEmpty()
        val gemini = slots.firstOrNull { it.provider == ApiProvider.GEMINI && it.apiKey.isNotBlank() }
            ?.apiKey.orEmpty()
        return groq.trim() to gemini.trim()
    }

    /**
     * Refreshes the catalogue when it is stale (or when [force] is set). Never throws: a failed
     * refresh leaves the previous cache in place so a job can still run.
     */
    fun refreshIfStale(
        store: ModelCatalogStore,
        slots: List<ApiSlot>,
        force: Boolean = false,
        fetch: (groqKey: String, geminiKey: String) -> List<CatalogModel> = { groq, gemini ->
            ModelCatalogFetcher.fetchAll(store, groq, gemini)
        }
    ): Outcome {
        val cached = store.load()
        if (!force && store.isFresh(TTL_MS)) {
            return Outcome(cached, fetched = false, detail = "cached")
        }

        val (groqKey, geminiKey) = keysFrom(slots)
        val fresh = try {
            fetch(groqKey, geminiKey)
        } catch (_: Throwable) {
            emptyList()
        }

        return when {
            fresh.isNotEmpty() -> {
                store.save(fresh)
                Outcome(fresh, fetched = true, detail = "fetched ${fresh.size} models")
            }

            cached.isNotEmpty() -> Outcome(cached, fetched = false, detail = "fetch failed, using cache")

            else -> Outcome(emptyList(), fetched = false, detail = "fetch failed, no catalogue yet")
        }
    }
}
