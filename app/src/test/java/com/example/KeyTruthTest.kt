package com.example

import com.example.net.ApiException
import com.example.net.PolicyRefusal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key screen and the runtime must never disagree.
 *
 * The runtime badged a slot invalid on ANY 403 - and OpenRouter answers a ZDR / data-policy
 * exclusion with 403 while the key itself is perfectly good. That is exactly what made Settings
 * ("Test key" -> auth probe) report the key as VALID while the pipeline reported INVALID_KEY.
 */
class KeyTruthTest {

    @Test
    fun only401CondemnsAKey() {
        assertTrue(ApiException(401, "unauthorized").isInvalidKey)
        assertFalse(ApiException(403, "No endpoints found matching your data policy").isInvalidKey)
        assertFalse(ApiException(429, "quota exceeded").isInvalidKey)
        assertFalse(ApiException(500, "server error").isInvalidKey)
    }

    @Test
    fun a403IsAPolicyRefusalNeverAnAuthFailure() {
        val policy = ApiException(403, "No endpoints found matching your data policy")
        assertTrue(policy.isPolicyForbidden)
        assertTrue(PolicyRefusal.isPolicyRefusal(policy))
        assertFalse(policy.isInvalidKey)
    }

    @Test
    fun policyProseIsRecognisedWithoutA403Wrapper() {
        assertTrue(PolicyRefusal.isPolicyMessage("ZDR violation detected for this account"))
        assertTrue(PolicyRefusal.isPolicyMessage("Request blocked by guardrail restrictions"))
        assertFalse(PolicyRefusal.isPolicyMessage("Invalid API key provided"))
        assertFalse(PolicyRefusal.isPolicyMessage(null))
    }

    @Test
    fun rateLimitsAreNotPolicyRefusals() {
        val limited = ApiException(429, "quota exceeded")
        assertTrue(limited.isRateLimited)
        assertFalse(PolicyRefusal.isPolicyRefusal(limited))
    }
}
