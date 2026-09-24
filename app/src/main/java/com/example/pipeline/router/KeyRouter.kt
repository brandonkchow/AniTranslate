package com.example.pipeline.router

import com.example.data.slots.ApiProvider
import com.example.data.slots.ApiSlot
import com.example.data.slots.CatalogModel
import com.example.data.slots.ModelCatalogStore
import com.example.data.slots.ProviderOrder
import com.example.data.slots.SlotRole
import com.example.data.slots.SlotStorage
import com.example.net.ApiException
import com.example.net.PolicyRefusal
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

enum class PipelineStage {
    DETECT_OCR,
    TRANSLATE
}

enum class ExclusionReason {
    NO_API_KEY,
    DISABLED,
    INVALID_KEY,
    ROLE_MISMATCH,
    COOLDOWN,
    RETIRED_MODEL,
    CAPABILITY_MISMATCH,
    EXHAUSTED
}

data class SlotExclusion(
    val slotId: String,
    val model: String,
    val reason: ExclusionReason
)

class KeyRouter(
    private val slotStorage: SlotStorage,
    private val catalogStore: ModelCatalogStore? = null,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
) {
    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_ATTEMPT_TIMEOUT_MS = 60_000L
    }

    // Concurrency controls per slot:
    // 1) Max 2 in-flight HTTP requests per slot
    // 2) Max 1 in-flight Detect+OCR (vision) request per slot
    private val slotSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val visionSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val routerMutex = Mutex()

    private fun getSlotSemaphore(slotId: String): Semaphore {
        return slotSemaphores.computeIfAbsent(slotId) { Semaphore(2) }
    }

    private fun getVisionSemaphore(slotId: String): Semaphore {
        return visionSemaphores.computeIfAbsent(slotId) { Semaphore(1) }
    }

    suspend fun getAvailableSlot(
        stage: PipelineStage,
        excludeSlotIds: Set<String> = emptySet(),
        maxAttempts: Int = this.maxAttempts
    ): Pair<ApiSlot?, Long?> = routerMutex.withLock {
        val now = System.currentTimeMillis()
        val slots = slotStorage.slots.value

        val matchingSlots = slots.filter { slot ->
            slot.enabled &&
            !slot.isModelRetired &&
            (slot.apiKey.isNotBlank() || slot.provider == ApiProvider.WORKSTATION || slot.provider == ApiProvider.HUGGINGFACE) &&
            !slot.isInvalidKey &&
            slot.id !in excludeSlotIds &&
            when (stage) {
                PipelineStage.DETECT_OCR -> (slot.role == SlotRole.DETECT_OCR || slot.role == SlotRole.ANY) && slot.isVisionSupported
                PipelineStage.TRANSLATE -> (slot.role == SlotRole.TRANSLATE || slot.role == SlotRole.ANY)
            }
        }

        if (matchingSlots.isEmpty()) {
            return Pair(null, null)
        }

        // Ordering is decided HERE, after capability/role filtering, so a capable slot can never be
        // queued behind an incapable one (Groq is text-only and still never receives an image):
        // local -> Groq -> OpenRouter -> ... -> Gemini last (see [ProviderOrder]).
        val rankedSlots = ProviderOrder.ordered(matchingSlots) { it.provider }

        // Find first slot not cooling
        val readySlot = rankedSlots.firstOrNull { !it.isCoolingDown(now) }
        if (readySlot != null) {
            return Pair(readySlot, null)
        }

        // All matching slots are cooling down -> find earliest cooldown expiry
        val earliestCooldownSlot = matchingSlots.minByOrNull { it.cooldownUntilEpochMs }
        val earliestTime = earliestCooldownSlot?.cooldownUntilEpochMs ?: (now + 15000L)
        return Pair(null, earliestTime)
    }

    fun explain(
        stage: PipelineStage,
        excludeSlotIds: Set<String> = emptySet(),
        catalog: List<CatalogModel>? = null
    ): List<SlotExclusion> {
        val now = System.currentTimeMillis()
        val slots = slotStorage.slots.value
        val activeCatalog = catalog ?: catalogStore?.load()

        val exclusions = mutableListOf<SlotExclusion>()
        for (slot in slots) {
            val reason: ExclusionReason? = when {
                !slot.enabled -> ExclusionReason.DISABLED
                slot.isModelRetired || isRetiredInCatalog(slot, activeCatalog) -> ExclusionReason.RETIRED_MODEL
                slot.apiKey.isBlank() && slot.provider != ApiProvider.WORKSTATION && slot.provider != ApiProvider.HUGGINGFACE -> ExclusionReason.NO_API_KEY
                slot.isInvalidKey -> ExclusionReason.INVALID_KEY
                stage == PipelineStage.DETECT_OCR && !slot.isVisionSupported -> ExclusionReason.CAPABILITY_MISMATCH
                when (stage) {
                    PipelineStage.DETECT_OCR -> slot.role != SlotRole.DETECT_OCR && slot.role != SlotRole.ANY
                    PipelineStage.TRANSLATE -> slot.role != SlotRole.TRANSLATE && slot.role != SlotRole.ANY
                } -> ExclusionReason.ROLE_MISMATCH
                slot.id in excludeSlotIds -> ExclusionReason.EXHAUSTED
                slot.isCoolingDown(now) -> ExclusionReason.COOLDOWN
                else -> null
            }
            if (reason != null) {
                exclusions.add(SlotExclusion(slot.id, slot.model, reason))
            }
        }
        return exclusions
    }

    private fun isRetiredInCatalog(slot: ApiSlot, catalog: List<CatalogModel>?): Boolean {
        if (catalog.isNullOrEmpty()) return false
        val providerKey = slot.provider.name.lowercase()
        val providerModels = catalog.filter { it.providerId.lowercase() == providerKey }
        if (providerModels.isEmpty()) return false
        return providerModels.none { it.id == slot.model }
    }

    suspend fun <T> executeWithSlot(
        stage: PipelineStage,
        slot: ApiSlot,
        timeoutMs: Long = DEFAULT_ATTEMPT_TIMEOUT_MS,
        block: suspend (ApiSlot) -> Result<T>
    ): Result<T> {
        val slotSem = getSlotSemaphore(slot.id)
        val visionSem = if (stage == PipelineStage.DETECT_OCR) getVisionSemaphore(slot.id) else null
        val startTime = System.currentTimeMillis()

        val result = try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                if (visionSem != null) {
                    visionSem.acquire()
                    try {
                        slotSem.acquire()
                        try {
                            block(slot)
                        } finally {
                            slotSem.release()
                        }
                    } finally {
                        visionSem.release()
                    }
                } else {
                    slotSem.acquire()
                    try {
                        block(slot)
                    } finally {
                        slotSem.release()
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.failure(java.util.concurrent.TimeoutException("Request timed out after ${timeoutMs}ms"))
        } catch (e: Exception) {
            Result.failure(e)
        }

        val latencyMs = System.currentTimeMillis() - startTime
        handleExecutionResult(stage, slot, result, latencyMs)
        return result
    }

    /**
     * Models a provider refused on **policy** grounds during this process. The key is valid; those
     * specific models are not permitted for this account. Keeping this per-process memory stops a
     * poisoned pin (e.g. a ZDR-excluded model) from burning an attempt on every single page.
     */
    private val policyBlockedModels = ConcurrentHashMap<String, MutableSet<String>>()

    /** One-shot guard: badged keys are re-probed once per process, not once per page. */
    private val keysRevalidated = AtomicBoolean(false)

    /** A policy refusal is per-model, not per-key: cool briefly so the walk moves to the next. */
    private val policyCooldownMs = 45_000L

    fun rememberPolicyBlock(providerId: String, modelId: String) {
        if (modelId.isBlank()) return
        policyBlockedModels.getOrPut(providerId.lowercase()) { ConcurrentHashMap.newKeySet() }.add(modelId)
    }

    fun rememberPolicyBlock(provider: ApiProvider, modelId: String) = rememberPolicyBlock(provider.name, modelId)

    fun isModelPolicyBlocked(providerId: String, modelId: String): Boolean =
        policyBlockedModels[providerId.lowercase()]?.contains(modelId) == true

    fun isModelPolicyBlocked(provider: ApiProvider, modelId: String): Boolean =
        isModelPolicyBlocked(provider.name, modelId)

    /**
     * Self-heal for badges written by the old `403 => invalid key` logic (or by a 401 that has since
     * been fixed): re-probes each badged slot's key once per process and clears the badge when the
     * provider genuinely accepts it. Without this a stale badge keeps a working slot out of the
     * chain until the user re-types a key that was never wrong.
     *
     * @param tester provider auth probe, e.g. the client's `testKey`
     * @return ids of slots whose badge was cleared
     */
    suspend fun revalidateBadgedKeys(tester: suspend (ApiSlot) -> Result<String>): List<String> {
        if (!keysRevalidated.compareAndSet(false, true)) return emptyList()
        val cleared = mutableListOf<String>()
        for (slot in slotStorage.slots.value) {
            if (!slot.isInvalidKey) continue
            val accepted = runCatching { tester(slot) }.getOrNull()?.isSuccess == true
            if (accepted) {
                slotStorage.markSlotInvalidKey(slot.id, false)
                cleared += slot.id
            }
        }
        return cleared
    }

    private fun <T> handleExecutionResult(stage: PipelineStage, slot: ApiSlot, result: Result<T>, latencyMs: Long) {
        val exception = result.exceptionOrNull()
        if (exception is ApiException) {
            val isRateLimit = exception.isRateLimited
            com.example.data.telemetry.ApiUsageTracker.recordRequest(
                provider = slot.provider,
                model = slot.model,
                isSuccess = false,
                isRateLimit = isRateLimit,
                latencyMs = latencyMs,
                retryAfterMs = exception.retryAfterMs
            )

            if (exception.isInvalidKey) {
                // 401 ONLY: the credentials themselves were rejected. This is the one status that may
                // condemn a key - and the badge the Settings "Test key" action clears. A 403 means the
                // key AUTHENTICATED and the request was refused by policy, so badging it here made the
                // runtime contradict the key screen and silently killed a working slot.
                slotStorage.markSlotInvalidKey(slot.id, true)
            } else if (PolicyRefusal.isPolicyRefusal(exception)) {
                // Policy refusal (403: ZDR / guardrail restrictions / data policy). The key is fine, so
                // it is NOT badged: cool just long enough to walk past this model, and remember the
                // model so the next page starts from a different one.
                slotStorage.setSlotCooldown(slot.id, policyCooldownMs, incrementRateLimits = false)
            } else if (isRateLimit) {
                // 429 / 503 / Quota: Calculate backoff with parsed retry delay
                val backoffMs = (exception.retryAfterMs ?: calculateBackoffMs(slot.consecutiveRateLimits)).coerceAtLeast(10_000L)
                slotStorage.setSlotCooldown(slot.id, backoffMs, incrementRateLimits = true)
            }
        } else if (result.isSuccess) {
            com.example.data.telemetry.ApiUsageTracker.recordRequest(
                provider = slot.provider,
                model = slot.model,
                isSuccess = true,
                isRateLimit = false,
                latencyMs = latencyMs
            )
            // Reset consecutive rate limits on success if was healthy
            if (slot.consecutiveRateLimits > 0 && !slot.isCoolingDown()) {
                slotStorage.resetSlotCooldown(slot.id)
            }
        } else {
            com.example.data.telemetry.ApiUsageTracker.recordRequest(
                provider = slot.provider,
                model = slot.model,
                isSuccess = false,
                isRateLimit = false,
                latencyMs = latencyMs
            )
        }
    }

    private fun calculateBackoffMs(consecutiveFailures: Int): Long {
        return when (consecutiveFailures) {
            0 -> 20_000L  // 20s
            1 -> 35_000L  // 35s
            2 -> 65_000L  // 65s
            else -> 180_000L // Cap at 180s (3m)
        }
    }
}
