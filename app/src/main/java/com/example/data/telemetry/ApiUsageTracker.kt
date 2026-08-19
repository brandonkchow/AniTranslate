package com.example.data.telemetry

import com.example.data.slots.ApiProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max

enum class ApiHealthStatus {
    HEALTHY,
    APPROACHING_LIMIT,
    COOLDOWN_ACTIVE
}

data class ApiRequestEvent(
    val timestampMs: Long = System.currentTimeMillis(),
    val provider: ApiProvider,
    val model: String,
    val isSuccess: Boolean,
    val isRateLimit: Boolean,
    val latencyMs: Long,
    val retryAfterMs: Long? = null
)

data class ApiUsageMetrics(
    val provider: ApiProvider = ApiProvider.GEMINI,
    val rollingRpm: Int = 0,
    val rpmLimit: Int = 15,
    val rpmUsagePercent: Float = 0f,
    val totalRequestsToday: Int = 0,
    val successCount: Int = 0,
    val rateLimitCount: Int = 0,
    val avgLatencyMs: Long = 0L,
    val activeCooldownUntilMs: Long = 0L,
    val suggestedWaitSeconds: Long = 0L,
    val status: ApiHealthStatus = ApiHealthStatus.HEALTHY,
    val statusHeadline: String = "Quota Normal",
    val recommendationMessage: String = "Operating within safe RPM limits."
)

object ApiUsageTracker {

    private val eventHistory = ConcurrentLinkedQueue<ApiRequestEvent>()
    private val _geminiMetrics = MutableStateFlow(ApiUsageMetrics())
    val geminiMetrics: StateFlow<ApiUsageMetrics> = _geminiMetrics.asStateFlow()

    // Auto-throttle settings to prevent 429 rate limit spikes on free tier
    private val _autoThrottleEnabled = MutableStateFlow(true)
    val autoThrottleEnabled: StateFlow<Boolean> = _autoThrottleEnabled.asStateFlow()

    private val _throttleDelayMs = MutableStateFlow(2500L) // 2.5s between page dispatches
    val throttleDelayMs: StateFlow<Long> = _throttleDelayMs.asStateFlow()

    // Gemini Free Tier default RPM threshold (15 RPM default for 2.5 Flash / 3.5 Flash)
    private const val GEMINI_FREE_TIER_RPM = 15
    private const val WARNING_THRESHOLD_PERCENT = 0.65f // Alert when >= 65% of RPM limit (10+ requests/min)
    private const val DANGER_THRESHOLD_PERCENT = 0.90f  // Critical when >= 90% (14+ requests/min)

    fun recordRequest(
        provider: ApiProvider,
        model: String,
        isSuccess: Boolean,
        isRateLimit: Boolean,
        latencyMs: Long,
        retryAfterMs: Long? = null
    ) {
        val now = System.currentTimeMillis()
        val event = ApiRequestEvent(
            timestampMs = now,
            provider = provider,
            model = model,
            isSuccess = isSuccess,
            isRateLimit = isRateLimit,
            latencyMs = latencyMs,
            retryAfterMs = retryAfterMs
        )
        eventHistory.add(event)

        // Prune events older than 24 hours
        val dayAgo = now - 24 * 60 * 60 * 1000L
        eventHistory.removeIf { it.timestampMs < dayAgo }

        if (provider == ApiProvider.GEMINI) {
            updateGeminiMetrics(now, retryAfterMs = if (isRateLimit) (retryAfterMs ?: 15000L) else null)
        }
    }

    fun setAutoThrottle(enabled: Boolean, delayMs: Long = 2500L) {
        _autoThrottleEnabled.value = enabled
        _throttleDelayMs.value = delayMs
    }

    fun refreshMetrics() {
        updateGeminiMetrics(System.currentTimeMillis())
    }

    private fun updateGeminiMetrics(now: Long, retryAfterMs: Long? = null) {
        val oneMinuteAgo = now - 60_000L
        val recentEvents = eventHistory.filter { it.provider == ApiProvider.GEMINI }
        val rollingEvents = recentEvents.filter { it.timestampMs >= oneMinuteAgo }
        val rollingRpm = rollingEvents.size

        val totalToday = recentEvents.size
        val successCount = recentEvents.count { it.isSuccess }
        val rateLimitCount = recentEvents.count { it.isRateLimit }

        val avgLatency = if (rollingEvents.isNotEmpty()) {
            rollingEvents.map { it.latencyMs }.average().toLong()
        } else 0L

        // Calculate cooldown
        val latestRateLimit = recentEvents.filter { it.isRateLimit }.maxByOrNull { it.timestampMs }
        val cooldownExpiry = if (retryAfterMs != null) {
            now + retryAfterMs
        } else if (latestRateLimit != null && latestRateLimit.retryAfterMs != null) {
            latestRateLimit.timestampMs + latestRateLimit.retryAfterMs
        } else 0L

        val isCooldownActive = cooldownExpiry > now
        val usagePercent = (rollingRpm.toFloat() / GEMINI_FREE_TIER_RPM).coerceIn(0f, 2f)

        // Calculate suggested wait time to drop below safe threshold (e.g. down to 8 RPM)
        val suggestedWaitSec = if (isCooldownActive) {
            max(1L, (cooldownExpiry - now) / 1000L)
        } else if (usagePercent >= WARNING_THRESHOLD_PERCENT) {
            val oldestInWindow = rollingEvents.minByOrNull { it.timestampMs }?.timestampMs ?: oneMinuteAgo
            val waitMs = max(0L, 60_000L - (now - oldestInWindow))
            max(3L, waitMs / 1000L)
        } else {
            0L
        }

        val status: ApiHealthStatus
        val headline: String
        val recommendation: String

        when {
            isCooldownActive -> {
                status = ApiHealthStatus.COOLDOWN_ACTIVE
                headline = "Rate Limit Cooldown Active"
                recommendation = "Gemini free tier quota exceeded. Cooldown active for ${suggestedWaitSec}s. Pause batch or wait before resuming."
            }
            usagePercent >= DANGER_THRESHOLD_PERCENT -> {
                status = ApiHealthStatus.APPROACHING_LIMIT
                headline = "Near RPM Limit ($rollingRpm/$GEMINI_FREE_TIER_RPM)"
                recommendation = "Critical rate limit proximity (${(usagePercent * 100).toInt()}%). Suggest pausing for ${suggestedWaitSec}s to allow quota window to reset."
            }
            usagePercent >= WARNING_THRESHOLD_PERCENT -> {
                status = ApiHealthStatus.APPROACHING_LIMIT
                headline = "Approaching Rate Limit ($rollingRpm/$GEMINI_FREE_TIER_RPM)"
                recommendation = "Current pace is high (${rollingRpm} req/min). Auto-throttling active. Consider a ${suggestedWaitSec}s pause if 429 errors occur."
            }
            else -> {
                status = ApiHealthStatus.HEALTHY
                headline = "Quota Healthy ($rollingRpm/$GEMINI_FREE_TIER_RPM RPM)"
                recommendation = "Operating comfortably within Gemini free tier rate limits (15 RPM limit)."
            }
        }

        _geminiMetrics.value = ApiUsageMetrics(
            provider = ApiProvider.GEMINI,
            rollingRpm = rollingRpm,
            rpmLimit = GEMINI_FREE_TIER_RPM,
            rpmUsagePercent = usagePercent,
            totalRequestsToday = totalToday,
            successCount = successCount,
            rateLimitCount = rateLimitCount,
            avgLatencyMs = avgLatency,
            activeCooldownUntilMs = cooldownExpiry,
            suggestedWaitSeconds = suggestedWaitSec,
            status = status,
            statusHeadline = headline,
            recommendationMessage = recommendation
        )
    }
}
