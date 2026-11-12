package gay.benjifox.foxyphotos.provider.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    @Test
    fun `retries only transient upload statuses`() {
        assertTrue(ProviderRetryPolicy.isRetryableHttpStatus(429))
        assertTrue(ProviderRetryPolicy.isRetryableHttpStatus(500))
        assertTrue(ProviderRetryPolicy.isRetryableHttpStatus(502))
        assertTrue(ProviderRetryPolicy.isRetryableHttpStatus(503))
        assertTrue(ProviderRetryPolicy.isRetryableHttpStatus(504))
        assertFalse(ProviderRetryPolicy.isRetryableHttpStatus(401))
        assertFalse(ProviderRetryPolicy.isRetryableHttpStatus(415))
    }

    @Test
    fun `backs off exponentially and respects an explicit retry delay`() {
        assertEquals(1_000, ProviderRetryPolicy.delayMillis(attempt = 0, retryAfterMillis = null))
        assertEquals(8_000, ProviderRetryPolicy.delayMillis(attempt = 3, retryAfterMillis = null))
        assertEquals(30_000, ProviderRetryPolicy.delayMillis(attempt = 8, retryAfterMillis = null))
        assertEquals(45_000, ProviderRetryPolicy.delayMillis(attempt = 0, retryAfterMillis = 45_000))
    }
}
