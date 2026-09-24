package com.example

import com.example.data.slots.ApiProvider
import com.example.data.slots.ProviderOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user-visible contract behind "Gemini should be the last option": the cloud ladder is
 * Groq -> OpenRouter -> Gemini, and local capacity always opens the page.
 *
 * Verified on plain strings so the order is testable without building Android slot objects -
 * the ordering itself is pure Kotlin.
 */
class ProviderOrderTest {

    @Test
    fun geminiIsTriedLast() {
        assertTrue(ProviderOrder.rankOf(ApiProvider.GEMINI) > ProviderOrder.rankOf(ApiProvider.OPENROUTER))
        assertTrue(ProviderOrder.rankOf(ApiProvider.OPENROUTER) > ProviderOrder.rankOf(ApiProvider.GROQ))
        assertTrue(ProviderOrder.rankOf(ApiProvider.GROQ) > ProviderOrder.rankOf(ApiProvider.WORKSTATION))
    }

    @Test
    fun theChainReadsGroqThenOpenRouterThenGemini() {
        assertEquals(
            listOf("groq", "openrouter", "gemini"),
            ProviderOrder.orderedProviderIds(listOf("gemini", "openrouter", "groq"))
        )
    }

    @Test
    fun unknownProvidersNeverJumpAheadOfGemini() {
        assertEquals(ProviderOrder.OTHER_RANK, ProviderOrder.rankOfProviderId("mystery"))
        assertTrue(ProviderOrder.rankOfProviderId("mystery") > ProviderOrder.rankOfProviderId("groq"))
        assertTrue(ProviderOrder.rankOfProviderId("mystery") < ProviderOrder.rankOfProviderId("gemini"))
    }

    @Test
    fun orderingIsStableWithinAProvider() {
        class Slot(val name: String, val provider: ApiProvider)

        val slots = listOf(
            Slot("gemini-a", ApiProvider.GEMINI),
            Slot("groq-1", ApiProvider.GROQ),
            Slot("groq-2", ApiProvider.GROQ),
            Slot("openrouter-1", ApiProvider.OPENROUTER)
        )
        // The user's own order survives inside one provider: ranking moves providers, not their slots.
        assertEquals(
            listOf("groq-1", "groq-2", "openrouter-1", "gemini-a"),
            ProviderOrder.ordered(slots) { it.provider }.map { it.name }
        )
    }
}
