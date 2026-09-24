package com.example

import com.example.data.slots.ApiProvider
import com.example.data.slots.ApiSlot
import com.example.data.slots.CatalogModel
import com.example.data.slots.ModelCapability
import com.example.data.slots.ModelWalk
import com.example.data.slots.SlotRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the free-model walk itself: which models a slot will try, in what order, and the capability
 * badges the Settings screen shows. Pure JVM — no Android, no network.
 */
class ModelWalkTest {

    private fun model(
        id: String,
        provider: String = "openrouter",
        acceptsImage: Boolean = false,
        free: Boolean = true,
        supportsJson: Boolean = true,
        contextLength: Int = 128000
    ) = CatalogModel(
        id = id,
        displayName = id,
        free = free,
        acceptsImage = acceptsImage,
        textOnlyOutput = true,
        supportsJson = supportsJson,
        contextLength = contextLength,
        providerId = provider
    )

    private val catalogue = listOf(
        model("openai/gpt-4o-mini"),
        model("meta-llama/llama-3.3-70b-instruct:free", contextLength = 131072),
        model("nex-agi/nex-n2.5-pro:free", acceptsImage = true, contextLength = 262144),
        model("qwen/qwen2.5-vl-72b-instruct:free", acceptsImage = true, contextLength = 32768),
        model("google/paid-flagship", free = false, contextLength = 1000000),
        model("llama-3.3-70b-versatile", provider = "groq", contextLength = 131072)
    )

    private fun openRouterSlot(model: String = "openai/gpt-4o-mini", walk: Boolean = true, role: SlotRole = SlotRole.TRANSLATE) =
        ApiSlot(
            provider = ApiProvider.OPENROUTER,
            model = model,
            role = role,
            walkFreeModels = walk
        )

    @Test
    fun walkOffTriesOnlyTheModelYouChose() {
        val plan = ModelWalk.planFor(openRouterSlot(walk = false), catalogue, needVision = false)

        assertFalse(plan.walking)
        assertTrue(plan.fallbacks.isEmpty())
        assertEquals(listOf("openai/gpt-4o-mini"), plan.all)
    }

    @Test
    fun walkOnOffersFreeFallbacksOnlyAndKeepsThePinnedModelFirst() {
        val plan = ModelWalk.planFor(openRouterSlot(), catalogue, needVision = false)

        assertTrue(plan.walking)
        assertEquals("openai/gpt-4o-mini", plan.all.first())
        assertTrue(plan.fallbacks.isNotEmpty())
        assertFalse("a paid model must never be walked", plan.fallbacks.contains("google/paid-flagship"))
        assertEquals("no duplicate of the pinned model", 1, plan.all.count { it == "openai/gpt-4o-mini" })
    }

    @Test
    fun visionWalkOffersOnlyModelsThatCanReadAPage() {
        val plan = ModelWalk.planFor(openRouterSlot(role = SlotRole.DETECT_OCR), catalogue, needVision = true)

        assertTrue(plan.fallbacks.isNotEmpty())
        assertTrue(
            "every fallback must accept images",
            plan.fallbacks.all { id -> catalogue.first { it.id == id }.acceptsImage }
        )
    }

    @Test
    fun providerCatalogueIsIsolated() {
        val groqSlot = ApiSlot(provider = ApiProvider.GROQ, model = "llama-3.3-70b-versatile")
        val plan = ModelWalk.planFor(groqSlot, catalogue, needVision = false)

        assertTrue(plan.all.all { it == "llama-3.3-70b-versatile" })
        assertFalse(plan.fallbacks.contains("openai/gpt-4o-mini"))
    }

    @Test
    fun capabilityIsMeasuredFromTheCatalogueNotTheName() {
        assertEquals(ModelCapability.VISION_AND_TEXT, ModelWalk.capabilityOf("nex-agi/nex-n2.5-pro:free", catalogue))
        assertEquals(ModelCapability.TEXT_ONLY, ModelWalk.capabilityOf("openai/gpt-4o-mini", catalogue))
        assertEquals(ModelCapability.UNKNOWN, ModelWalk.capabilityOf("not-in-any-catalogue", catalogue))
    }

    @Test
    fun freeCountsMatchTheCatalogue() {
        val counts = ModelWalk.freeCounts(ApiProvider.OPENROUTER, catalogue)

        assertEquals(4, counts.translatable)
        assertEquals(2, counts.visionCapable)
        assertTrue(counts.translatable >= counts.visionCapable)
    }

    @Test
    fun standbyWalkIsTheFreeVisionList() {
        val standby = ModelWalk.standbyModels(catalogue)

        assertTrue(standby.isNotEmpty())
        assertTrue(standby.all { it.acceptsImage && it.free })
    }

    @Test
    fun standingByIsOffOnlyWhenEverySlotHasTheWalkOff() {
        val on = openRouterSlot()
        val off = openRouterSlot(walk = false)

        assertTrue(ModelWalk.isWalkingAnywhere(listOf(off, on)))
        assertFalse(ModelWalk.isWalkingAnywhere(listOf(off)))
        assertFalse(ModelWalk.isWalkingAnywhere(listOf(off.copy(enabled = false))))
    }

    @Test
    fun providerSideFallbacksAreCappedAtThree() {
        val plan = ModelWalk.planFor(openRouterSlot(), catalogue, needVision = false)

        assertNotEquals(0, plan.providerSideFallbacks().size)
        assertTrue(plan.providerSideFallbacks().size <= 3)
    }

    @Test
    fun thePageBudgetCoversEveryConfiguredSlot() {
        // One slot must not starve the slots behind it: with a flat 3-request page budget, Gemini and
        // Groq spent it and OpenRouter was never reached — the walk looked broken but was starved.
        assertEquals(9, ModelWalk.translateBudget(enabledSlots = 3, floor = 3, perSlotCap = 3))
        assertEquals(3, ModelWalk.translateBudget(enabledSlots = 1, floor = 3, perSlotCap = 3))
        assertEquals(3, ModelWalk.translateBudget(enabledSlots = 0, floor = 3, perSlotCap = 3))
        assertEquals(300, ModelWalk.translateBudget(enabledSlots = 100, floor = 3, perSlotCap = 3))
    }
}
