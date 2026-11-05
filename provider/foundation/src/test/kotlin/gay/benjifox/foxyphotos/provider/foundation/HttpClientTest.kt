package gay.benjifox.foxyphotos.provider.foundation

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpClientTest {
    @Test
    fun `retries 503 and returns success response`() = runBlocking {
        val mockWebServer = MockWebServer(listOf(503, 200))
        val client = RetryableHttpClient(mockWebServer.client, maxAttempts = 3)

        val response = client.execute(
            Request.Builder()
                .url(mockWebServer.url("/"))
                .post("retry".toRequestBody("text/plain".toMediaType()))
                .build(),
        )

        assertEquals(200, response.code)
        assertEquals("retry-success", response.body?.string())
        assertTrue(mockWebServer.requestCount >= 2)
        mockWebServer.shutdown()
    }

    @Test
    fun `returns 401 without retry`() = runBlocking {
        val mockWebServer = MockWebServer(listOf(401))
        val client = RetryableHttpClient(mockWebServer.client, maxAttempts = 3)

        val response = client.execute(
            Request.Builder()
                .url(mockWebServer.url("/"))
                .get()
                .build(),
        )

        assertEquals(401, response.code)
        mockWebServer.shutdown()
    }

    @Test
    fun `returns final retryable response after exhausting attempts`() = runBlocking {
        val mockWebServer = MockWebServer(listOf(503, 503, 503))
        val client = RetryableHttpClient(mockWebServer.client, maxAttempts = 3)

        val response = client.execute(
            Request.Builder()
                .url(mockWebServer.url("/"))
                .get()
                .build(),
        )

        assertEquals(503, response.code)
        assertEquals(3, mockWebServer.requestCount)
        mockWebServer.shutdown()
    }
}
