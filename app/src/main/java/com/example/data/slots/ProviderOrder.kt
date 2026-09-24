package com.example.data.slots

/**
 * Which slot opens a page, when more than one slot could serve the stage.
 *
 * Two rules, in this order:
 *  1. **Local capacity first** — workstation / on-device models cost no quota and no key.
 *  2. **Then the cloud ladder, Gemini LAST**: Groq, then OpenRouter, then Gemini.
 *
 * Gemini's free tier is the scarcest resource a page touches (20 requests/day). Opening with it
 * spent the page's attempt budget on a quota error and starved the slots queued behind it, which
 * read as "the chain keeps failing on Gemini" — the chain was working, its opener was exhausted.
 * Gemini remains fully reachable: it is the fallback, not the opener.
 *
 * Ordering is applied **after** capability filtering, so this can never queue a capable slot behind
 * an incapable one — Groq is text-only and therefore still never receives an image, and a stage only
 * Gemini can serve (vision + OCR) is unaffected.
 *
 * Pure Kotlin, no Android dependencies: the order is verifiable in plain JVM unit tests.
 */
object ProviderOrder {

    /** Ranks are tried lowest-first; gaps are deliberate so new providers can slot in. */
    const val LOCAL_RANK = 0
    const val GROQ_RANK = 1
    const val OPENROUTER_RANK = 2
    const val OTHER_RANK = 3
    const val GEMINI_RANK = 9

    fun rankOf(provider: ApiProvider): Int = when (provider) {
        ApiProvider.WORKSTATION, ApiProvider.HUGGINGFACE -> LOCAL_RANK
        ApiProvider.GROQ -> GROQ_RANK
        ApiProvider.OPENROUTER -> OPENROUTER_RANK
        ApiProvider.GEMINI -> GEMINI_RANK
        else -> OTHER_RANK
    }

    /** Same ranking for a provider *id* (catalogue/standby candidates carry ids, not enums). */
    fun rankOfProviderId(providerId: String): Int {
        val provider = ApiProvider.values().firstOrNull { it.name.equals(providerId, ignoreCase = true) }
            ?: return OTHER_RANK
        return rankOf(provider)
    }

    /**
     * Orders slots by provider rank. [List.sortedBy] is a **stable** sort, so two slots on the same
     * provider keep the order the user arranged them in: ranking never reshuffles their config.
     */
    /**
     * Stable sort ([List.sortedBy] is stable), so slots on the same provider keep the order the user
     * arranged them in: ranking reorders providers, never the user's own configuration.
     *
     * Generic over the item, so the order is verifiable in a plain JVM unit test - this is the one
     * place that decides which slot opens a page, and it is worth testing without building slots.
     */
    fun <T> ordered(items: List<T>, providerOf: (T) -> ApiProvider): List<T> =
        items.sortedBy { rankOf(providerOf(it)) }

    /** Orders provider ids the same way, for the standby walk and its diagnostics. */
    fun orderedProviderIds(providerIds: Collection<String>): List<String> =
        providerIds.distinct().sortedBy { rankOfProviderId(it) }

    /** The order, spelled out once, for logs and the settings blurb. */
    fun describe(): String = "local -> Groq -> OpenRouter -> ... -> Gemini (last)"
}
