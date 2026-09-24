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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two contracts the user asked for, pinned as executable assertions:
 *
 *  1. **Chain order** - local first, then Groq -> OpenRouter -> Gemini LAST, with Gemini's free
 *     tier no longer opening the page and burning the budget. Groq is text-only, so a capable slot
 *     can never be queued behind an incapable one.
 *  2. **Key truth** - a 403 policy refusal (ZDR / guardrails / data policy) must never condemn a
 *     key, because the Settings "Test key" action legitimately reports that same key as valid. Only
 *     a 401 does, and a stale badge self-heals once the provider accepts the key.
 *
 * Note the status codes: the older test injected the ZDR message as 400, while the device actually
 * receives **403** - which is what made the runtime contradict the key screen in the first place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderChainOrderTest {

    private lateinit var context: Context
    private lateinit var storage: SlotStorage
    private lateinit var router: KeyRouter

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storage = SlotStorage.getInstance(context)
        // Deterministic starting state: every slot disabled, keyless, healthy, off cooldown.
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

    /** Enables one provider's slot as a keyed slot for the given stage. */
    private fun enableSlot(provider: ApiProvider, apiKey: String, role: SlotRole): ApiSlot {
        val existing = storage.loadSlots().first { it.provider == provider }
        storage.updateSlot(
            existing.copy(
                enabled = true,
                apiKey = apiKey,
                model = provider.defaultModel,
                isModelRetired = false,
                role = role,
                isInvalidKey = false
            )
        )
        storage.resetSlotCooldown(existing.id)
        return storage.loadSlots().first { it.id == existing.id }
    }

    private fun liveSlot(id: String): ApiSlot = storage.loadSlots().first { it.id == id }

    @Test
    fun theRouterOpensWithGroqAndGeminiIsLast() = runBlocking {
        val gemini = enableSlot(ApiProvider.GEMINI, "key-gemini", SlotRole.ANY)
        val openRouter = enableSlot(ApiProvider.OPENROUTER, "key-openrouter", SlotRole.ANY)
        val groq = enableSlot(ApiProvider.GROQ, "key-groq", SlotRole.ANY)

        val first = router.getAvailableSlot(PipelineStage.TRANSLATE).first
        assertNotNull("A configured TRANSLATE slot must be selectable", first)
        assertEquals("Groq must open the page - Gemini no longer burns the budget first", groq.id, first!!.id)

        // Groq rate-limits: OpenRouter is next, and Gemini keeps waiting behind it.
        router.executeWithSlot<Map<Int, String>>(PipelineStage.TRANSLATE, first) {
            Result.failure(ApiException(429, "quota exceeded"))
        }
        val second = router.getAvailableSlot(PipelineStage.TRANSLATE, setOf(groq.id)).first
        assertEquals("OpenRouter is second in the chain", openRouter.id, second!!.id)

        val third = router.getAvailableSlot(PipelineStage.TRANSLATE, setOf(groq.id, openRouter.id)).first
        assertEquals("Gemini is the last option", gemini.id, third!!.id)
    }

    @Test
    fun visionNeverGoesToGroqAndTheLadderStillPutsGeminiLast() = runBlocking {
        val gemini = enableSlot(ApiProvider.GEMINI, "key-gemini", SlotRole.ANY)
        val openRouter = enableSlot(ApiProvider.OPENROUTER, "key-openrouter", SlotRole.ANY)
        enableSlot(ApiProvider.GROQ, "key-groq", SlotRole.ANY)

        val vision = router.getAvailableSlot(PipelineStage.DETECT_OCR).first
        assertNotNull("A vision-capable slot must be selectable", vision)
        assertNotEquals(
            "Groq is text-only (never send images to Groq), so it must not open the vision stage",
            ApiProvider.GROQ,
            vision!!.provider
        )
        assertEquals(openRouter.id, vision.id)
        assertNotEquals("Gemini must not be the first vision slot", gemini.id, vision.id)
    }

    @Test
    fun a403PolicyRefusalNeverCondemnsTheKeyButStillBenchesTheSlot() = runBlocking {
        val openRouter = enableSlot(ApiProvider.OPENROUTER, "key-openrouter", SlotRole.ANY)
        val gemini = enableSlot(ApiProvider.GEMINI, "key-gemini", SlotRole.ANY)

        // The exact on-device phrasing, arriving as the real status code (403, not 400).
        val zdr = "No endpoints found matching your data policy. " +
            "ZDR violation (account settings): 1 endpoint excluded"
        router.executeWithSlot<Map<Int, String>>(PipelineStage.TRANSLATE, openRouter) {
            Result.failure(ApiException(403, zdr))
        }
        assertFalse(
            "A 403 policy refusal must never badge a valid key - that is what made Settings say valid",
            liveSlot(openRouter.id).isInvalidKey
        )
        assertTrue(
            "The slot is still benched so the walk moves to the next model/provider",
            liveSlot(openRouter.id).isCoolingDown()
        )
        assertEquals(
            "A policy refusal is not a rate-limit strike",
            0,
            liveSlot(openRouter.id).consecutiveRateLimits
        )

        router.executeWithSlot<Map<Int, String>>(PipelineStage.TRANSLATE, gemini) {
            Result.failure(ApiException(401, "API key not valid. Please pass a valid API key."))
        }
        assertTrue("A 401 is the one status that condemns a key", liveSlot(gemini.id).isInvalidKey)
    }

    @Test
    fun aStaleInvalidKeyBadgeSelfHealsWhenTheProviderAcceptsTheKey() = runBlocking {
        val openRouter = enableSlot(ApiProvider.OPENROUTER, "key-openrouter", SlotRole.ANY)
        storage.markSlotInvalidKey(openRouter.id, true)
        assertTrue(liveSlot(openRouter.id).isInvalidKey)

        val cleared = router.revalidateBadgedKeys { Result.success("Key valid (HTTP 200)") }
        assertEquals(listOf(openRouter.id), cleared)
        assertFalse(
            "After the provider accepts the key the runtime must agree with the key screen",
            liveSlot(openRouter.id).isInvalidKey
        )

        // One-shot per process: no auth probe on every single page.
        assertEquals(emptyList<String>(), router.revalidateBadgedKeys { Result.success("Key valid") })
    }

    @Test
    fun aPolicyBlockedModelIsRememberedSoTheNextPageSkipsIt() = runBlocking {
        enableSlot(ApiProvider.OPENROUTER, "key-openrouter", SlotRole.ANY)
        val blocked = "nvidia/nemotron-3-super-120b-a12b:free"
        val allowed = "google/gemma-4-31b-it:free"

        assertFalse(router.isModelPolicyBlocked(ApiProvider.OPENROUTER, blocked))
        router.rememberPolicyBlock(ApiProvider.OPENROUTER, blocked)
        assertTrue(router.isModelPolicyBlocked(ApiProvider.OPENROUTER, blocked))
        assertTrue("Provider ids match case-insensitively", router.isModelPolicyBlocked("openrouter", blocked))
        assertFalse("A different model on the same provider is untouched", router.isModelPolicyBlocked(ApiProvider.OPENROUTER, allowed))
    }
}
