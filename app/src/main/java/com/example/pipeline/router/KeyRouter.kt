package com.example.pipeline.router

import com.example.data.slots.ApiProvider
import com.example.data.slots.ApiSlot
import com.example.data.slots.SlotRole
import com.example.data.slots.SlotStorage
import com.example.net.ApiException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

enum class PipelineStage {
    DETECT_OCR,
    TRANSLATE
}

class KeyRouter(private val slotStorage: SlotStorage) {

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

    suspend fun getAvailableSlot(stage: PipelineStage): Pair<ApiSlot?, Long?> = routerMutex.withLock {
        val now = System.currentTimeMillis()
        val slots = slotStorage.slots.value

        val matchingSlots = slots.filter { slot ->
            slot.enabled &&
            slot.apiKey.isNotBlank() &&
            !slot.isInvalidKey &&
            when (stage) {
                PipelineStage.DETECT_OCR -> (slot.role == SlotRole.DETECT_OCR || slot.role == SlotRole.ANY) && slot.isVisionSupported
                PipelineStage.TRANSLATE -> (slot.role == SlotRole.TRANSLATE || slot.role == SlotRole.ANY)
            }
        }

        if (matchingSlots.isEmpty()) {
            return Pair(null, null)
        }

        // Find first slot not cooling
        val readySlot = matchingSlots.firstOrNull { !it.isCoolingDown(now) }
        if (readySlot != null) {
            return Pair(readySlot, null)
        }

        // All matching slots are cooling down -> find earliest cooldown expiry
        val earliestCooldownSlot = matchingSlots.minByOrNull { it.cooldownUntilEpochMs }
        val earliestTime = earliestCooldownSlot?.cooldownUntilEpochMs ?: (now + 15000L)
        return Pair(null, earliestTime)
    }

    suspend fun <T> executeWithSlot(
        stage: PipelineStage,
        slot: ApiSlot,
        block: suspend (ApiSlot) -> Result<T>
    ): Result<T> {
        val slotSem = getSlotSemaphore(slot.id)
        val visionSem = if (stage == PipelineStage.DETECT_OCR) getVisionSemaphore(slot.id) else null

        return if (visionSem != null) {
            visionSem.acquire()
            try {
                slotSem.acquire()
                try {
                    val result = block(slot)
                    handleExecutionResult(stage, slot, result)
                    result
                } finally {
                    slotSem.release()
                }
            } finally {
                visionSem.release()
            }
        } else {
            slotSem.acquire()
            try {
                val result = block(slot)
                handleExecutionResult(stage, slot, result)
                result
            } finally {
                slotSem.release()
            }
        }
    }

    private fun <T> handleExecutionResult(stage: PipelineStage, slot: ApiSlot, result: Result<T>) {
        val exception = result.exceptionOrNull()
        if (exception is ApiException) {
            if (exception.isInvalidKey) {
                // 401 / 403: Badge slot invalid
                slotStorage.markSlotInvalidKey(slot.id, true)
            } else if (exception.isRateLimited) {
                // 429 / 503 / Quota: Calculate backoff
                val backoffMs = exception.retryAfterMs ?: calculateBackoffMs(slot.consecutiveRateLimits)
                slotStorage.setSlotCooldown(slot.id, backoffMs, incrementRateLimits = true)
            }
        } else if (result.isSuccess) {
            // Reset consecutive rate limits on success if was healthy
            if (slot.consecutiveRateLimits > 0 && !slot.isCoolingDown()) {
                slotStorage.resetSlotCooldown(slot.id)
            }
        }
    }

    private fun calculateBackoffMs(consecutiveFailures: Int): Long {
        return when (consecutiveFailures) {
            0 -> 15_000L  // 15s
            1 -> 30_000L  // 30s
            2 -> 60_000L  // 60s
            else -> 180_000L // Cap at 180s (3m)
        }
    }
}
