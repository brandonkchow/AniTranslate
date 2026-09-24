package com.example.data.slots

/**
 * What a concrete model id can actually do, measured from the live catalogue rather than guessed
 * from its name. Drives the Settings badges so the model type is never a mystery.
 */
enum class ModelCapability(val label: String) {
    /** Accepts images: can read the artwork (vision + OCR). */
    VISION_AND_TEXT("Vision + OCR"),

    /** Text in, text out: translates what it is given but cannot see the page. */
    TEXT_ONLY("Translation only"),

    /** No catalogue entry yet: capability unverified until a catalogue is fetched. */
    UNKNOWN("Not in catalogue")
}

/** Free-model availability for a provider, for the Settings summary line. */
data class FreeCounts(val translatable: Int, val visionCapable: Int)

/**
 * The ordered list of models one slot will try: the model the user pinned first, then the ranked
 * free fallbacks. [walking] is false when the user turned the walk off or no catalogue is loaded,
 * in which case the slot tries exactly one model — the one the user chose.
 */
data class WalkPlan(
    val pinned: String,
    val fallbacks: List<String>,
    val walking: Boolean
) {
    val all: List<String> get() = (listOf(pinned) + fallbacks).distinct()

    /** OpenRouter walks provider-side inside a single request: pinned + up to [cap] fallbacks. */
    fun providerSideFallbacks(cap: Int = PROVIDER_SIDE_CAP): List<String> = fallbacks.take(cap)

    companion object {
        /** OpenRouter's models array is capped at 4 entries total, so 3 fallbacks beside the pinned model. */
        const val PROVIDER_SIDE_CAP = 3
    }
}

/**
 * Single source of truth for "which models will this slot try, and what can the chosen one do".
 * Shared by the pipeline and the Settings UI so the two can never disagree, and pure so it is
 * plain-JVM testable.
 */
object ModelWalk {

    fun catalogFor(provider: ApiProvider, catalog: List<CatalogModel>): List<CatalogModel> =
        catalog.filter { it.providerId.equals(provider.name, ignoreCase = true) }

    fun find(modelId: String, catalog: List<CatalogModel>): CatalogModel? =
        catalog.firstOrNull { it.id == modelId }

    /** Free models for this provider, ranked best-first (pinned model first when present). */
    fun rankedFreeModels(
        provider: ApiProvider,
        catalog: List<CatalogModel>,
        needVision: Boolean,
        pinned: List<String> = emptyList()
    ): List<CatalogModel> = ModelFilter.rank(
        ModelFilter.usable(catalogFor(provider, catalog), needVision = needVision),
        pinned = pinned
    )

    /** Plan for one slot at one stage. Honours the user's explicit walk switch. */
    fun planFor(slot: ApiSlot, catalog: List<CatalogModel>, needVision: Boolean): WalkPlan {
        if (!slot.walkFreeModels) return WalkPlan(slot.model, emptyList(), walking = false)
        val ranked = rankedFreeModels(slot.provider, catalog, needVision, pinned = listOf(slot.model))
        val fallbacks = ranked.map { it.id }.filter { it != slot.model }.distinct()
        return WalkPlan(slot.model, fallbacks, walking = fallbacks.isNotEmpty())
    }

    /** Capability of the model a slot is pinned to, as measured from the catalogue. */
    fun capabilityOf(modelId: String, catalog: List<CatalogModel>): ModelCapability =
        when (find(modelId, catalog)?.acceptsImage) {
            true -> ModelCapability.VISION_AND_TEXT
            false -> ModelCapability.TEXT_ONLY
            null -> ModelCapability.UNKNOWN
        }

    /** Free-model availability per role, for the Settings summary. */
    fun freeCounts(provider: ApiProvider, catalog: List<CatalogModel>): FreeCounts {
        val forProvider = catalogFor(provider, catalog)
        return FreeCounts(
            translatable = ModelFilter.usable(forProvider, needVision = false).size,
            visionCapable = ModelFilter.usable(forProvider, needVision = true).size
        )
    }

    /** The cross-provider free vision standby walk: free models that accept images and return text. */
    fun standbyModels(catalog: List<CatalogModel>): List<CatalogModel> =
        ModelFilter.rank(ModelFilter.usable(catalog, needVision = true))

    /**
     * Whether any enabled slot has the walk switched on. When every slot has it off, the standby
     * walk is off too: "walk free models" off everywhere means nothing walks.
     */
    fun isWalkingAnywhere(slots: List<ApiSlot>): Boolean =
        slots.any { it.enabled && it.walkFreeModels }

    /**
     * How many requests the translate stage may spend on one page.
     *
     * Each slot walks at most [perSlotCap] candidates, so the page budget has to cover *every* slot the
     * user configured. A flat budget was spent by the early slots and the slots behind them were never
     * reached — which looked like a broken walk but was starvation.
     */
    fun translateBudget(enabledSlots: Int, floor: Int, perSlotCap: Int): Int =
        maxOf(floor, perSlotCap * enabledSlots.coerceAtLeast(1))
}
