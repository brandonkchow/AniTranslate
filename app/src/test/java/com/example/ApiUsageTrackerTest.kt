package com.example

import com.example.data.slots.ApiProvider
import com.example.data.telemetry.ApiHealthStatus
import com.example.data.telemetry.ApiUsageTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiUsageTrackerTest {

    @Before
    fun setUp() {
        ApiUsageTracker.setAutoThrottle(true, 2500L)
    }

    @Test
    fun testInitialMetricsHealthy() {
        ApiUsageTracker.refreshMetrics()
        val metrics = ApiUsageTracker.geminiMetrics.value
        assertEquals(ApiProvider.GEMINI, metrics.provider)
        assertEquals(15, metrics.rpmLimit)
    }

    @Test
    fun testRecordRequestsAndAlerts() {
        // Record 11 successful requests (11 / 15 RPM = 73% capacity -> APPROACHING_LIMIT)
        repeat(11) {
            ApiUsageTracker.recordRequest(
                provider = ApiProvider.GEMINI,
                model = "gemini-2.5-flash",
                isSuccess = true,
                isRateLimit = false,
                latencyMs = 1200L
            )
        }

        val metrics = ApiUsageTracker.geminiMetrics.value
        assertTrue(metrics.rollingRpm >= 11)
        assertEquals(ApiHealthStatus.APPROACHING_LIMIT, metrics.status)
        assertTrue(metrics.suggestedWaitSeconds > 0)
    }

    @Test
    fun testRateLimitCooldown() {
        // Record a 429 rate limit with a 30-second retry-after
        ApiUsageTracker.recordRequest(
            provider = ApiProvider.GEMINI,
            model = "gemini-2.5-flash",
            isSuccess = false,
            isRateLimit = true,
            latencyMs = 500L,
            retryAfterMs = 30_000L
        )

        val metrics = ApiUsageTracker.geminiMetrics.value
        assertEquals(ApiHealthStatus.COOLDOWN_ACTIVE, metrics.status)
        assertTrue(metrics.suggestedWaitSeconds in 20..30)
        assertTrue(metrics.recommendationMessage.contains("Cooldown active"))
    }
}
