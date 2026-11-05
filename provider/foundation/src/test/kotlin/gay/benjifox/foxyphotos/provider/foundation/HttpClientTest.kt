package gay.benjifox.foxyphotos.provider.foundation

import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpClientTest {
    @Test
    fun `retries 503 and returns success response`() {
        val mockWebServer = MockWebServer(listOf(503, 200))
        val client = RetryableHttpClient(mockWebServer.client, maxAttempts = 3)

        val response = client.execute(
            Request.Builder()
                .url(mockWebServer.url("/"))
                .post("retry".toRequestBody(MediaType.get("text/plain")))
                .build(),
        )

        assertEquals(200, response.code)
        assertEquals("retry-success", response.body?.string())
        assertTrue(mockWebServer.requestCount >= 2)
        mockWebServer.shutdown()
    }

    @Test
    fun `returns 401 without retry`() {
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
    fun `retries on timeout`() {
        val mockWebServer = MockWebServer(listOf(503))
        val client = RetryableHttpClient(mockWebServer.client, maxAttempts = 3)

        val response = client.execute(
            Request.Builder()
                .url(mockWebServer.url("/"))
                .get()
                .build(),
        )

        assertEquals(503, response.code)
        mockWebServer.shutdown()
    }
}
