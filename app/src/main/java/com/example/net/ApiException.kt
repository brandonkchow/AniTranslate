package com.example.net

class ApiException(
    val statusCode: Int,
    override val message: String,
    val retryAfterMs: Long? = null
) : Exception("API Error ($statusCode): $message") {
    val isRateLimited: Boolean
        get() = statusCode == 429 || statusCode == 503

    /**
     * The credentials themselves were rejected. **401 is the only status that may condemn a key.**
     *
     * A 403 means the request authenticated fine and the *request* was refused (Zero-Data-Retention
     * exclusion, guardrail restrictions, regional access). Treating it as an auth failure made the
     * runtime contradict the Settings key test — which asks the one question that matters ("is this
     * key accepted?") and correctly reports the same key as valid — and it silently shadowed the
     * dedicated policy branch in [com.example.pipeline.router.KeyRouter.handleExecutionResult],
     * leaving a valid OpenRouter key unusable for the rest of time.
     */
    val isInvalidKey: Boolean
        get() = statusCode == 401

    /**
     * Authenticated, then refused by provider policy (403). Carries no auth meaning: the key is
     * fine, this particular model/request is not permitted. Must never badge a key.
     */
    val isPolicyForbidden: Boolean
        get() = statusCode == 403
}
