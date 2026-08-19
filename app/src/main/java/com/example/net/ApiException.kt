package com.example.net

class ApiException(
    val statusCode: Int,
    override val message: String,
    val retryAfterMs: Long? = null
) : Exception("API Error ($statusCode): $message") {
    val isRateLimited: Boolean
        get() = statusCode == 429 || statusCode == 503

    val isInvalidKey: Boolean
        get() = statusCode == 401 || statusCode == 403
}
