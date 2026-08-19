package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min
import kotlin.math.pow

class BackoffLogicTest {

    private fun calculateBackoffSeconds(attempt: Int): Long {
        // Pipeline spec: backoff 15s, 30s, 60s, 120s, cap 180s
        val baseDelay = 15L
        val multiplier = 2.0.pow(attempt.toDouble()).toLong()
        val calculated = baseDelay * multiplier
        return min(calculated, 180L)
    }

    private fun parseRetryAfterHeader(headerValue: String?): Long? {
        if (headerValue == null) return null
        return headerValue.trim().toLongOrNull()
    }

    @Test
    fun testExponentialBackoffProgression() {
        assertEquals(15L, calculateBackoffSeconds(0))
        assertEquals(30L, calculateBackoffSeconds(1))
        assertEquals(60L, calculateBackoffSeconds(2))
        assertEquals(120L, calculateBackoffSeconds(3))
        assertEquals(180L, calculateBackoffSeconds(4))
        assertEquals(180L, calculateBackoffSeconds(5))
        assertEquals(180L, calculateBackoffSeconds(10))
    }

    @Test
    fun testRetryAfterHeaderParsing() {
        assertEquals(45L, parseRetryAfterHeader("45"))
        assertEquals(120L, parseRetryAfterHeader("  120  "))
        assertEquals(null, parseRetryAfterHeader(null))
        assertEquals(null, parseRetryAfterHeader("invalid-date-string"))
    }
}
