package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.slots.ApiProvider
import com.example.data.slots.ApiSlot
import com.example.data.slots.SlotRole
import com.example.data.slots.SlotStorage
import com.example.net.ApiException
import com.example.pipeline.router.KeyRouter
import com.example.pipeline.router.PipelineStage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deterministic coverage for the multi-slot fallback contract that PagePipeline relies on.
 *
 * These tests never touch the network: `executeWithSlot` accepts the request lambda, so a provider
 * failure is injected as a plain [ApiException]. That is exactly how the on-device ZDR rejection
 * (OpenRouter excluding an endpoint by account data policy) and 429 rate limits arrive, so this
 * pins the behaviour that must hold when they do:
 *
 *   1. An excluded slot id is never handed back out.
 *   2. A policy/ZDR failure benches that slot so the next attempt gets a *different* slot.
 *   3. A rate-limited slot likewise falls through to the next slot.
 *   4. Exhausting every eligible slot reports "nothing available" instead of looping forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyRouterFallbackTest {

    private lateinit var context: Context
    private lateinit var storage: SlotStorage
    private lateinit var router: KeyRouter

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storage = SlotStorage.getInstance(context)
        // Deterministic starting state: every slot disabled, keyless, healthy, off cooldown, not retired.
        storage.loadSlots().forEach { slot ->
            storage.updateSlot(
                slot.copy(
                    enabled = false,
                    apiKey = "",
                    isInvalidKey = false,
                    isModelRetired = false,
                    model = slot.provider.defaultModel
                )
            )
            storage.resetSlotCooldown(slot.id)
        }
        router = KeyRouter(storage)
    }

    /** Enables a single provider's slot as a keyed TRANSLATE slot and returns its live state. */
    private fun enableTranslateSlot(provider: ApiProvider, apiKey: String): ApiSlot {
        val existing = storage.loadSlots().first { it.provider == provider }
        storage.updateSlot(
            existing.copy(
                enabled = true,
                apiKey = apiKey,
                model = provider.defaultModel,
                isModelRetired = false,
                role = SlotRole.TRANSLATE,
                isInvalidKey = false
            )
        )
        storage.resetSlotCooldown(existing.id)
        return storage.loadSlots().first { it.id == existing.id }
    }

    private fun liveSlot(id: String): ApiSlot = storage.loadSlots().first { it.id == id }

    @Test
    fun excludedSlotIdIsNeverSelected() = runBlocking {
        val openRouter = enableTranslateSlot(ApiProvider.OPENROUTER, "key-openrouter")
        val gemini = enableTranslateSlot(ApiProvider.GEMINI, "key-gemini")

        val first = router.getAvailableSlot(PipelineStage.TRANSLATE).first
        assertNotNull("A configured TRANSLATE slot must be selectable", first)
        assertEquals(openRouter.id, first!!.id)

        val second = router.getAvailableSlot(PipelineStage.TRANSLATE, setOf(openRouter.id)).first
        assertNotNull("Excluding one slot must leave the other selectable", second)
        assertNotEquals(openRouter.id, second!!.id)
        assertEquals(gemini.id, second.id)
    }

    @Test
    fun zdrPolicyFailureBenchesSlotAndFallsThroughToNextSlot() = runBlocking {
        val openRouter = enableTranslateSlot(ApiProvider.OPENROUTER, "key-openrouter")
        enableTranslateSlot(ApiProvider.GEMINI, "key-gemini")

        val selected = router.getAvailableSlot(PipelineStage.TRANSLATE).first
        assertEquals(openRouter.id, selected!!.id)

        // The exact failure observed in the on-device run log for job #1.
        val zdrMessage = "0 endpoints out of 1 requested are available matching your guardrail " +
            "restrictions and data policy. We removed them for the following reasons: " +
            "ZDR violation (account settings): 1 endpoint excluded"

        val result = router.executeWithSlot<Map<Int, String>>(PipelineStage.TRANSLATE, selected) {
            Result.failure(ApiException(400, zdrMessage))
        }

        assertTrue("Injected provider failure must surface as a failed result", result.isFailure)
        assertTrue(
            "A policy/ZDR rejection must bench the slot so it is not immediately re-selected",
            liveSlot(openRouter.id).isCoolingDown()
        )
        assertEquals(
            "Benching on a policy error must not count as a rate-limit strike",
            0,
            liveSlot(openRouter.id).consecutiveRateLimits
        )

        val fallback = router.getAvailableSlot(PipelineStage.TRANSLATE).first
        assertNotNull("A fallback slot must still be available after the policy rejection", fallback)
        assertNotEquals(
            "The policy-excluded slot must not be selected again",
            openRouter.id,
            fallback!!.id
        )
        assertEquals(ApiProvider.GEMINI, fallback.provider)
    }

    @Test
    fun rateLimitedSlotFallsThroughToNextSlot() = runBlocking {
        val openRouter = enableTranslateSlot(ApiProvider.OPENROUTER, "key-openrouter")
        enableTranslateSlot(ApiProvider.GEMINI, "key-gemini")

        val selected = router.getAvailableSlot(PipelineStage.TRANSLATE).first
        assertEquals(openRouter.id, selected!!.id)

        // The exact failure observed on-device during bubble detection.
        val result = router.executeWithSlot<Map<Int, String>>(PipelineStage.TRANSLATE, selected) {
            Result.failure(
                ApiException(
                    statusCode = 429,
                    message = "This model is currently experiencing high demand. Spikes in demand " +
                        "are usually temporary. Please try again later.",
                    retryAfterMs = 20_000L
                )
            )
        }

        assertTrue(result.isFailure)
        assertTrue("A 429 must bench the slot", liveSlot(openRouter.id).isCoolingDown())

        val fallback = router.getAvailableSlot(PipelineStage.TRANSLATE).first
        assertNotNull("A fallback slot must remain available after a 429", fallback)
        assertNotEquals(openRouter.id, fallback!!.id)
    }

    @Test
    fun exhaustingEveryEligibleSlotReportsNothingAvailable() = runBlocking {
        val openRouter = enableTranslateSlot(ApiProvider.OPENROUTER, "key-openrouter")
        val gemini = enableTranslateSlot(ApiProvider.GEMINI, "key-gemini")

        val everyEligibleId = setOf(openRouter.id, gemini.id)
        val (slot, waitTime) = router.getAvailableSlot(PipelineStage.TRANSLATE, everyEligibleId)

        assertNull("With every eligible slot excluded the router must report no slot", slot)
        assertNull("No cooldown is pending, so no wait time should be advertised", waitTime)
    }

    @Test
    fun disabledOrKeylessSlotIsNotEligibleForTranslation() = runBlocking {
        val openRouter = enableTranslateSlot(ApiProvider.OPENROUTER, "key-openrouter")
        // Disable it again: nothing eligible should remain.
        storage.updateSlot(liveSlot(openRouter.id).copy(enabled = false))

        val (slot, _) = router.getAvailableSlot(PipelineStage.TRANSLATE)
        assertNull("A disabled slot must never be handed out", slot)
    }

    @Test
    fun workStationSlotIsEligibleWithoutAnApiKey() = runBlocking {
        val workstation = storage.loadSlots().firstOrNull { it.provider == ApiProvider.WORKSTATION }
        assertNotNull("A WORKSTATION slot must ship in the defaults", workstation)

        storage.updateSlot(
            workstation!!.copy(enabled = true, apiKey = "", role = SlotRole.TRANSLATE)
        )

        val (slot, _) = router.getAvailableSlot(PipelineStage.TRANSLATE)
        assertNotNull("A self-hosted workstation endpoint authenticates via the tunnel, not a key", slot)
        assertEquals(ApiProvider.WORKSTATION, slot!!.provider)
    }

    @Test
    fun defaultOpenRouterModelIsNotTheZdrExcludedNemotron() {
        val defaults = storage.createDefaultSlots()
        val openRouterDefault = defaults.first { it.provider == ApiProvider.OPENROUTER }

        assertFalse(
            "The stock OpenRouter model must not be one that ZDR-enforcing accounts reject",
            openRouterDefault.model.contains("nemotron", ignoreCase = true)
        )
        assertEquals("google/gemma-4-31b-it:free", openRouterDefault.model)
    }

    @Test
    fun explainReportsNoApiKeyForEnabledKeylessSlot() {
        val openRouter = storage.loadSlots().first { it.provider == ApiProvider.OPENROUTER }
        storage.updateSlot(
            openRouter.copy(
                enabled = true,
                apiKey = "",
                role = SlotRole.TRANSLATE,
                isInvalidKey = false,
                isModelRetired = false
            )
        )
        val exclusions = router.explain(PipelineStage.TRANSLATE)
        val openRouterExclusion = exclusions.find { it.slotId == openRouter.id }
        assertNotNull("Keyless enabled slot must be reported in exclusions", openRouterExclusion)
        assertEquals(com.example.pipeline.router.ExclusionReason.NO_API_KEY, openRouterExclusion!!.reason)
    }

    @Test
    fun explainReportsDisabledForDisabledSlot() {
        val gemini = storage.loadSlots().first { it.provider == ApiProvider.GEMINI }
        storage.updateSlot(
            gemini.copy(
                enabled = false,
                apiKey = "test-key",
                role = SlotRole.TRANSLATE,
                isInvalidKey = false
            )
        )
        val exclusions = router.explain(PipelineStage.TRANSLATE)
        val geminiExclusion = exclusions.find { it.slotId == gemini.id }
        assertNotNull("Disabled slot must be reported in exclusions", geminiExclusion)
        assertEquals(com.example.pipeline.router.ExclusionReason.DISABLED, geminiExclusion!!.reason)
    }

    @Test
    fun explainReportsRetiredModelForUnknownOrRetiredModel() {
        val openRouter = storage.loadSlots().first { it.provider == ApiProvider.OPENROUTER }
        storage.updateSlot(
            openRouter.copy(
                enabled = true,
                apiKey = "test-key",
                model = "retired/old-model-v1",
                role = SlotRole.TRANSLATE,
                isInvalidKey = false,
                isModelRetired = true
            )
        )
        val exclusions = router.explain(PipelineStage.TRANSLATE)
        val retiredExclusion = exclusions.find { it.slotId == openRouter.id }
        assertNotNull("Slot with retired model must be reported in exclusions", retiredExclusion)
        assertEquals(com.example.pipeline.router.ExclusionReason.RETIRED_MODEL, retiredExclusion!!.reason)
    }

    @Test
    fun explainReportsRetiredModelWhenModelAbsentFromCatalog() {
        val groq = storage.loadSlots().first { it.provider == ApiProvider.GROQ }
        storage.updateSlot(
            groq.copy(
                enabled = true,
                apiKey = "gsk-test",
                model = "defunct-groq-model",
                role = SlotRole.TRANSLATE,
                isInvalidKey = false,
                isModelRetired = false
            )
        )
        val catalog = listOf(
            com.example.data.slots.CatalogModel(
                id = "openai/gpt-oss-120b",
                displayName = "GPT-OSS 120B",
                free = true,
                acceptsImage = false,
                textOnlyOutput = true,
                supportsJson = true,
                contextLength = 131072,
                providerId = "groq"
            )
        )
        val exclusions = router.explain(PipelineStage.TRANSLATE, catalog = catalog)
        val groqExclusion = exclusions.find { it.slotId == groq.id }
        assertNotNull("Slot with unknown model absent from catalog must report RETIRED_MODEL", groqExclusion)
        assertEquals(com.example.pipeline.router.ExclusionReason.RETIRED_MODEL, groqExclusion!!.reason)
    }

    @Test
    fun executeWithSlotEnforcesAttemptTimeoutBound() = runBlocking {
        val gemini = enableTranslateSlot(ApiProvider.GEMINI, "key-gemini")

        val startTime = System.currentTimeMillis()
        val result = router.executeWithSlot<String>(
            stage = PipelineStage.TRANSLATE,
            slot = gemini,
            timeoutMs = 150L
        ) {
            kotlinx.coroutines.delay(1000L)
            Result.success("finished")
        }
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("Execution should fail due to timeout", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Exception should be TimeoutException, got $ex", ex is java.util.concurrent.TimeoutException)
        assertTrue("Elapsed time ($elapsed ms) should be bounded well below block duration (1000 ms)", elapsed < 800L)
    }
}
