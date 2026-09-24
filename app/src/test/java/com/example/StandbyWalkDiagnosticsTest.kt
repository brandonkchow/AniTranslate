package com.example

import com.example.data.slots.ApiProvider
import com.example.data.slots.ApiSlot
import com.example.data.slots.CatalogModel
import com.example.data.slots.SlotRole
import com.example.pipeline.router.ExclusionReason
import com.example.pipeline.router.PipelineStage
import com.example.pipeline.router.StandbyWalkDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyWalkDiagnosticsTest {

    private fun createCandidate(id: String, providerId: String): CatalogModel {
        return CatalogModel(
            id = id,
            displayName = id,
            providerId = providerId,
            free = true,
            acceptsImage = true,
            textOnlyOutput = false,
            supportsJson = true,
            contextLength = 131072
        )
    }

    private fun createSlot(
        id: String,
        provider: ApiProvider,
        enabled: Boolean = true,
        apiKey: String = "test-key",
        isInvalidKey: Boolean = false,
        cooldownUntilEpochMs: Long = 0L,
        role: SlotRole = SlotRole.ANY,
        isModelRetired: Boolean = false
    ): ApiSlot {
        return ApiSlot(
            id = id,
            provider = provider,
            enabled = enabled,
            apiKey = apiKey,
            isInvalidKey = isInvalidKey,
            cooldownUntilEpochMs = cooldownUntilEpochMs,
            role = role,
            isModelRetired = isModelRetired,
            model = provider.defaultModel
        )
    }

    @Test
    fun testExplainProviderIneligibilityReasons() {
        val now = 100_000L

        // DISABLED
        val disabledSlot = createSlot("openrouter-1", ApiProvider.OPENROUTER, enabled = false)
        assertEquals(
            ExclusionReason.DISABLED,
            StandbyWalkDiagnostics.explainProviderIneligibility("openrouter", listOf(disabledSlot), now)
        )

        // COOLDOWN
        val coolingSlot = createSlot("gemini-1", ApiProvider.GEMINI, enabled = true, cooldownUntilEpochMs = now + 10_000L)
        assertEquals(
            ExclusionReason.COOLDOWN,
            StandbyWalkDiagnostics.explainProviderIneligibility("gemini", listOf(coolingSlot), now)
        )

        // NO_API_KEY (no slot configured)
        assertEquals(
            ExclusionReason.NO_API_KEY,
            StandbyWalkDiagnostics.explainProviderIneligibility("groq", emptyList(), now)
        )

        // NO_API_KEY (blank key)
        val blankKeySlot = createSlot("openrouter-1", ApiProvider.OPENROUTER, enabled = true, apiKey = "   ")
        assertEquals(
            ExclusionReason.NO_API_KEY,
            StandbyWalkDiagnostics.explainProviderIneligibility("openrouter", listOf(blankKeySlot), now)
        )

        // INVALID_KEY
        val invalidKeySlot = createSlot("gemini-1", ApiProvider.GEMINI, enabled = true, isInvalidKey = true)
        assertEquals(
            ExclusionReason.INVALID_KEY,
            StandbyWalkDiagnostics.explainProviderIneligibility("gemini", listOf(invalidKeySlot), now)
        )

        // EXHAUSTED
        val exhaustedSlot = createSlot("gemini-1", ApiProvider.GEMINI, enabled = true)
        assertEquals(
            ExclusionReason.EXHAUSTED,
            StandbyWalkDiagnostics.explainProviderIneligibility("gemini", listOf(exhaustedSlot), now, attemptedSlotIds = setOf("gemini-1"))
        )

        // ROLE_MISMATCH
        val translateOnlySlot = createSlot("gemini-1", ApiProvider.GEMINI, enabled = true, role = SlotRole.TRANSLATE)
        assertEquals(
            ExclusionReason.ROLE_MISMATCH,
            StandbyWalkDiagnostics.explainProviderIneligibility("gemini", listOf(translateOnlySlot), now, stage = PipelineStage.DETECT_OCR)
        )

        // CAPABILITY_MISMATCH
        val noVisionSlot = createSlot("groq-1", ApiProvider.GROQ, enabled = true)
        assertEquals(
            ExclusionReason.CAPABILITY_MISMATCH,
            StandbyWalkDiagnostics.explainProviderIneligibility("groq", listOf(noVisionSlot), now, stage = PipelineStage.DETECT_OCR)
        )

        // RETIRED_MODEL
        val retiredSlot = createSlot("gemini-1", ApiProvider.GEMINI, enabled = true, isModelRetired = true)
        assertEquals(
            ExclusionReason.RETIRED_MODEL,
            StandbyWalkDiagnostics.explainProviderIneligibility("gemini", listOf(retiredSlot), now)
        )
    }

    @Test
    fun testStandbyArmReportsCandidateCountAndPerProviderReasonWhenNothingEligible() {
        val now = 100_000L
        // 53 candidates across openrouter and gemini (mimicking batch-18)
        val candidates = mutableListOf<CatalogModel>()
        repeat(33) { i -> candidates.add(createCandidate("openrouter/free-$i", "openrouter")) }
        repeat(20) { i -> candidates.add(createCandidate("gemini-free-$i", "gemini")) }

        val openRouterSlot = createSlot("openrouter-slot", ApiProvider.OPENROUTER, enabled = false)
        val geminiSlot = createSlot("gemini-slot", ApiProvider.GEMINI, enabled = true, cooldownUntilEpochMs = now + 30_000L)
        val slots = listOf(openRouterSlot, geminiSlot)

        // a) Candidates log
        val candidatesLog = StandbyWalkDiagnostics.formatCandidatesLog(candidates)
        assertEquals(
            "Standby vision candidates: 53 mapped to [openrouter, gemini]",
            candidatesLog
        )

        // b & c) Standby summary with per-provider reasons
        val summary = StandbyWalkDiagnostics.formatStandbySummary(
            standbyCandidates = candidates,
            slots = slots,
            now = now
        )
        assertEquals(
            "standby: 0 of 53 free vision candidates eligible (openrouter: DISABLED, gemini: COOLDOWN)",
            summary
        )
    }

    @Test
    fun testStandbySummaryWithEmptyCandidates() {
        val summary = StandbyWalkDiagnostics.formatStandbySummary(
            standbyCandidates = emptyList(),
            slots = emptyList()
        )
        assertEquals("standby: 0 free vision candidates available", summary)

        val candidatesLog = StandbyWalkDiagnostics.formatCandidatesLog(emptyList())
        assertEquals("Standby vision candidates: 0 available", candidatesLog)
    }

    @Test
    fun testStandbySummaryWhenSomeCandidatesAreEligible() {
        val now = 100_000L
        val candidates = listOf(
            createCandidate("openrouter/free-1", "openrouter"),
            createCandidate("gemini-free-1", "gemini")
        )
        // Gemini slot is healthy and eligible; OpenRouter slot is disabled
        val openRouterSlot = createSlot("openrouter-slot", ApiProvider.OPENROUTER, enabled = false)
        val geminiSlot = createSlot("gemini-slot", ApiProvider.GEMINI, enabled = true)
        val slots = listOf(openRouterSlot, geminiSlot)

        val summary = StandbyWalkDiagnostics.formatStandbySummary(
            standbyCandidates = candidates,
            slots = slots,
            now = now
        )
        assertEquals(
            "standby: 1 of 2 free vision candidates eligible (openrouter: DISABLED)",
            summary
        )
    }
}
