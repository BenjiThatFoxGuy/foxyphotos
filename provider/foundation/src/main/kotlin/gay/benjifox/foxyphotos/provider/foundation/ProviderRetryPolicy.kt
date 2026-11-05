package gay.benjifox.foxyphotos.provider.foundation

object ProviderRetryPolicy {
    private const val initialDelayMillis = 1_000L
    private const val maximumDelayMillis = 30_000L

    fun isRetryableHttpStatus(statusCode: Int): Boolean = statusCode in setOf(429, 500, 502, 503, 504)

    fun delayMillis(attempt: Int, retryAfterMillis: Long?): Long {
        require(attempt >= 0) { "Attempt cannot be negative." }
        if (retryAfterMillis != null) {
            return retryAfterMillis.coerceAtLeast(0)
        }

        val multiplier = 1L shl attempt.coerceAtMost(5)
        return (initialDelayMillis * multiplier).coerceAtMost(maximumDelayMillis)
    }
}
