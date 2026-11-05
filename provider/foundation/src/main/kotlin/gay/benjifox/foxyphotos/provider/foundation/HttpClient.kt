package gay.benjifox.foxyphotos.provider.foundation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ProviderRetryable {
    data class TransientHttp(
        val statusCode: Int,
        val retryAfterMillis: Long?,
    ) : ProviderRetryable

    data class AuthExpired(val message: String) : ProviderRetryable
    object Cancellation : ProviderRetryable
    data class Unknown(val cause: Throwable) : ProviderRetryable
}

sealed interface ProviderResult<out T> {
    data class Success<T>(val value: T) : ProviderResult<T>
    data class Failure<T>(val error: ProviderError) : ProviderResult<T>
    data class Retryable<T>(val error: ProviderRetryable) : ProviderResult<T>
}

data class ProviderError(
    val code: Int,
    val message: String,
    val isPermanent: Boolean,
)

@kotlin.jvm.JvmInline
value class HttpStatus(val value: Int)

class HttpClient(
    private val client: okhttp3.OkHttpClient,
) {
    suspend fun execute(request: okhttp3.Request): okhttp3.Response {
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute()
        }
    }
}

class RetryableHttpClient(
    client: okhttp3.OkHttpClient,
    private val maxAttempts: Int = 3,
) : HttpClient(client) {
    override suspend fun execute(request: okhttp3.Request): okhttp3.Response {
        var attempt = 0
        var lastResponse: okhttp3.Response? = null
        while (attempt < maxAttempts) {
            val response = HttpClient(client).execute(request)
            if (response.isSuccessful || !isRetryableStatus(response.code)) {
                return response
            }
            response.close()
            attempt++
            if (attempt < maxAttempts) {
                kotlinx.coroutines.delay(calculateBackoff(attempt))
            }
        }
        return lastResponse ?: throw IllegalStateException("No response after retries")
    }

    private fun isRetryableStatus(code: Int): Boolean = code in setOf(429, 500, 502, 503, 504)

    private fun calculateBackoff(attempt: Int): Long {
        val base = 1000L
        val max = 30000L
        return (base * (1L shl attempt.coerceAtMost(5))).coerceAtMost(max)
    }
}