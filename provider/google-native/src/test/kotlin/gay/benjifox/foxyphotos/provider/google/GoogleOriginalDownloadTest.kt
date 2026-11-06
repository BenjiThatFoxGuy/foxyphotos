package gay.benjifox.foxyphotos.provider.google

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class GoogleOriginalDownloadTest {
    private fun hash(code: Int, content: String, expected: Long, unknownLength: Boolean = false): Result<String> = runCatching {
        val client = GoogleLibraryClient({ error("No live credentials in fixtures") })
        val method = client.javaClass.getDeclaredMethod("hashOriginalResponse", Response::class.java, java.lang.Long.TYPE)
        method.isAccessible = true
        Response.Builder().request(Request.Builder().url("https://example.invalid/fixture").build())
            .protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .body(if (!unknownLength) content.toResponseBody() else object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength() = -1L
                override fun source(): okio.BufferedSource = okio.Buffer().writeUtf8(content)
            }).build().use { method.invoke(client, it, expected) as String }
    }
    @Test fun actualBodyBytesDetermineHash() {
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash(200, "hello", 5).getOrThrow())
        assertNotEquals(hash(200, "hello", 5).getOrThrow(), hash(200, "HELLO", 5).getOrThrow())
    }
    @Test fun authExpiryRedirectPartialTruncationAndOversizeFailClosed() {
        for (code in listOf(206, 302, 401, 403, 404, 429, 500)) assertTrue(hash(code, "hello", 5).isFailure)
        assertTrue(hash(200, "hell", 5).isFailure)
        assertTrue(hash(200, "hello!", 5).isFailure)
        assertTrue(hash(200, "hello!", 5, unknownLength = true).isFailure)
        assertTrue(hash(200, "hell", 5, unknownLength = true).isFailure)
        assertTrue(hash(200, "hello", 256L * 1024 * 1024 + 1).isFailure)
    }
}
