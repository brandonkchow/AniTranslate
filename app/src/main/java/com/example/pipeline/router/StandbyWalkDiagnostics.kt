package com.example.pipeline.router

import com.example.data.slots.ApiProvider
import com.example.data.slots.ApiSlot
import com.example.data.slots.CatalogModel
import com.example.data.slots.SlotRole

/**
 * Diagnostic explanation helpers for standby model walk operations and exclusions.
 * Pure Kotlin logic with zero Android framework dependencies so it can be verified in standard JVM unit tests.
 */
object StandbyWalkDiagnostics {

    /**
     * Determines whether a slot can serve a given provider.
     */
    fun isSlotEligibleForProvider(
        slot: ApiSlot,
        providerId: String,
        now: Long = System.currentTimeMillis(),
        attemptedSlotIds: Set<String> = emptySet(),
        stage: PipelineStage = PipelineStage.DETECT_OCR
    ): Boolean {
        if (!slot.enabled) return false
        if (!slot.provider.name.equals(providerId, ignoreCase = true)) return false
        if (slot.apiKey.isBlank() && slot.provider != ApiProvider.WORKSTATION && slot.provider != ApiProvider.HUGGINGFACE) return false
        if (slot.isInvalidKey) return false
        if (slot.isCoolingDown(now)) return false
        if (slot.id in attemptedSlotIds) return false
        if (stage == PipelineStage.DETECT_OCR && !slot.isVisionSupported) return false
        if (slot.isModelRetired) return false
        return true
    }

    /**
     * Determines whether a slot can serve a given standby candidate model.
     */
    fun isSlotEligibleForCandidate(
        slot: ApiSlot,
        candidate: CatalogModel,
        now: Long = System.currentTimeMillis(),
        attemptedSlotIds: Set<String> = emptySet(),
        stage: PipelineStage = PipelineStage.DETECT_OCR
    ): Boolean = isSlotEligibleForProvider(slot, candidate.providerId, now, attemptedSlotIds, stage)

    /**
     * Explains why slots for a given provider are not eligible, reusing existing
     * ExclusionReason values from [KeyRouter.explain].
     */
    fun explainProviderIneligibility(
        providerId: String,
        slots: List<ApiSlot>,
        now: Long = System.currentTimeMillis(),
        attemptedSlotIds: Set<String> = emptySet(),
        stage: PipelineStage = PipelineStage.DETECT_OCR
    ): ExclusionReason {
        val matchingSlots = slots.filter { it.provider.name.equals(providerId, ignoreCase = true) }
        if (matchingSlots.isEmpty()) {
            return ExclusionReason.NO_API_KEY
        }
        if (matchingSlots.all { !it.enabled }) {
            return ExclusionReason.DISABLED
        }
        val enabledSlots = matchingSlots.filter { it.enabled }
        if (enabledSlots.all { it.isInvalidKey }) {
            return ExclusionReason.INVALID_KEY
        }
        if (enabledSlots.all { it.apiKey.isBlank() && it.provider != ApiProvider.WORKSTATION && it.provider != ApiProvider.HUGGINGFACE }) {
            return ExclusionReason.NO_API_KEY
        }
        if (enabledSlots.all { it.isCoolingDown(now) }) {
            return ExclusionReason.COOLDOWN
        }
        if (enabledSlots.all { it.id in attemptedSlotIds }) {
            return ExclusionReason.EXHAUSTED
        }
        if (stage == PipelineStage.DETECT_OCR && enabledSlots.all { !it.isVisionSupported }) {
            return ExclusionReason.CAPABILITY_MISMATCH
        }
        if (enabledSlots.all {
            when (stage) {
                PipelineStage.DETECT_OCR -> it.role != SlotRole.DETECT_OCR && it.role != SlotRole.ANY
                PipelineStage.TRANSLATE -> it.role != SlotRole.TRANSLATE && it.role != SlotRole.ANY
            }
        }) {
            return ExclusionReason.ROLE_MISMATCH
        }
        if (enabledSlots.all { it.isModelRetired }) {
            return ExclusionReason.RETIRED_MODEL
        }
        return ExclusionReason.DISABLED
    }

    /**
     * Formats the standby outcome summary stating candidate count and per-provider reason.
     * Example: "standby: 0 of 53 free vision candidates eligible (openrouter: DISABLED, gemini: COOLDOWN)"
     */
    fun formatStandbySummary(
        standbyCandidates: List<CatalogModel>,
        slots: List<ApiSlot>,
        eligibleCount: Int? = null,
        now: Long = System.currentTimeMillis(),
        attemptedSlotIds: Set<String> = emptySet(),
        stage: PipelineStage = PipelineStage.DETECT_OCR
    ): String {
        if (standbyCandidates.isEmpty()) {
            return "standby: 0 free vision candidates available"
        }
        val actualEligibleCount = eligibleCount ?: standbyCandidates.count { candidate ->
            slots.any { isSlotEligibleForCandidate(it, candidate, now, attemptedSlotIds, stage) }
        }
        val distinctProviders = standbyCandidates.map { it.providerId.lowercase() }.distinct()
        val ineligibleProviders = distinctProviders.filter { provider ->
            slots.none { s ->
                isSlotEligibleForProvider(s, provider, now, attemptedSlotIds, stage)
            }
        }
        val reasonsMap = ineligibleProviders.associateWith { provider ->
            explainProviderIneligibility(provider, slots, now, attemptedSlotIds, stage)
        }
        val reasonsStr = reasonsMap.entries.joinToString(", ") { "${it.key}: ${it.value}" }
        return if (reasonsStr.isNotBlank()) {
            "standby: $actualEligibleCount of ${standbyCandidates.size} free vision candidates eligible ($reasonsStr)"
        } else {
            "standby: $actualEligibleCount of ${standbyCandidates.size} free vision candidates eligible"
        }
    }

    /**
     * Formats one log line stating how many standby candidates exist and which providers they map to.
     */
    fun formatCandidatesLog(standbyCandidates: List<CatalogModel>): String {
        if (standbyCandidates.isEmpty()) {
            return "Standby vision candidates: 0 available"
        }
        val providers = standbyCandidates.map { it.providerId.lowercase() }.distinct()
        return "Standby vision candidates: ${standbyCandidates.size} mapped to [${providers.joinToString(", ")}]"
    }
}
