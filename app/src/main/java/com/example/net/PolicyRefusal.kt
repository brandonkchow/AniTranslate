package com.example.net

/**
 * Recognises a *policy* refusal, which is not an authentication failure.
 *
 * Providers answer "your key is fine, this model/request is not permitted for you" with HTTP 403
 * (OpenRouter: ZDR-excluded accounts, data-policy/guardrail restrictions, regional limits). Reading
 * that as a bad key is what made the runtime contradict the Settings key screen, and it permanently
 * excluded a working slot from the chain until the user re-typed a key that was never wrong.
 */
object PolicyRefusal {
    /** Providers name the exclusion in prose; these are the phrasings seen in the wild. */
    private val MARKERS = listOf(
        "zdr violation",
        "guardrail restrictions",
        "data policy",
        "policy violation",
        "not permitted",
        "no endpoints found matching"
    )

    fun isPolicyMessage(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return MARKERS.any { text.contains(it) }
    }

    /** A 403 body, or any status whose prose names a policy exclusion. */
    fun isPolicyRefusal(exception: ApiException): Boolean =
        exception.isPolicyForbidden || isPolicyMessage(exception.message)
}
