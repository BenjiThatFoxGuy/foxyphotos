package gay.benjifox.foxyphotos.provider.foundation

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

/**
 * HTTP status code.
 *
 * A plain data class rather than a value class: `@JvmInline` is absent from the
 * common standard library, so value classes cannot be declared in shared code
 * and still compile for every target.
 */
data class HttpStatus(val value: Int)
