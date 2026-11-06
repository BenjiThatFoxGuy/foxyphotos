package gay.benjifox.foxyphotos.provider.google

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class GoogleUploadDiagnosticsTest {
    @Test fun `successful commit and existing duplicate never log provider keys`() = runBlocking {
        suspend fun exercise(present: Boolean) {
            val events = mutableListOf<UploadDiagnosticEvent>()
            val key = "private-provider-media-key".toByteArray()
            var byteUploads = 0
            fun bytes(field: Int, data: ByteArray) = byteArrayOf(((field shl 3) or 2).toByte(), data.size.toByte()) + data
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val body = when {
                    request.url.encodedPath.endsWith("5084965799730810217") -> if (present) bytes(1, bytes(2, bytes(2, bytes(1, key)))) else byteArrayOf()
                    request.url.encodedPath.endsWith("16538846908252377752") -> bytes(1, bytes(3, bytes(1, key)))
                    request.method == "PUT" -> { byteUploads++; byteArrayOf(8, 1, 18, 1, 99) }
                    else -> byteArrayOf()
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("ok").code(200)
                    .header("X-GUploader-UploadID", "private-token").body(body.toResponseBody()).build()
            }.build()
            val client = GoogleNativeUploadClient(UploadDiagnosticSink(events::add), http, { "private-auth" }, {})
            val result = client.upload(request())
            if (present) {
                assertEquals("private-provider-media-key", (result as GoogleUploadOutcome.Present).mediaKey)
                assertEquals(0, byteUploads)
            } else {
                assertEquals("private-provider-media-key", (result as GoogleUploadOutcome.Committed).mediaKey)
                assertEquals(1, byteUploads)
            }
            assertEquals(UploadAction.SUCCESS, events.last().action)
            assertFalse(events.joinToString { it.render() }.contains("private"))
        }
        exercise(false)
        exercise(true)
    }

    @Test fun `network retries retain all four attempts and stop uncertain without exception data`() = runBlocking {
        val events = mutableListOf<UploadDiagnosticEvent>()
        var attempts = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().method == "PUT") {
                attempts++
                throw java.io.IOException("https://private/?token=secret")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("ok").code(200)
                .header("X-GUploader-UploadID", "private-token").body(byteArrayOf().toResponseBody()).build()
        }.build()
        val client = GoogleNativeUploadClient(UploadDiagnosticSink(events::add), http, { "private-auth" }, {})
        try { client.upload(request()); fail("must remain uncertain") } catch (_: GoogleUploadUncertainException) { }
        assertEquals(4, attempts)
        assertEquals(listOf(1, 2, 3, 4), events.filter { it.stage == UploadStage.MEDIA_BYTES && it.action == UploadAction.START }.map { it.attempt })
        assertEquals(UploadFailure.UNCERTAIN, events.last().failure)
        assertFalse(events.joinToString { it.render() }.contains("private"))
    }

    @Test fun `late policy gate prevents first bytes and every retry`() = runBlocking {
        for (allowFirst in listOf(false, true)) {
            var puts = 0
            var gates = 0
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().method == "PUT") { puts++; throw java.io.IOException("offline") }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("ok").code(200)
                    .header("X-GUploader-UploadID", "test-token").body(byteArrayOf().toResponseBody()).build()
            }.build()
            val client = GoogleNativeUploadClient(UploadDiagnosticSink {}, http, { "test-auth" }, {})
            try {
                client.upload(request().copy(beforeBytes = { gates++; allowFirst && gates == 1 }))
                fail("must wait")
            } catch (_: GoogleUploadDeferredException) { }
            assertEquals(if (allowFirst) 1 else 0, puts)
            assertEquals(if (allowFirst) 2 else 1, gates)
        }
    }

    private fun request() = GoogleUploadRequest(GoogleCredential("private@example.com", "private-id", emptyList()),
        "/DCIM/private.jpg", 42, 0, ByteArray(20) { 123 }, { ByteArrayInputStream(byteArrayOf(1)) }, UploadFileType.JPEG)

    @Test fun `commit retries retain correlation status shape and failure without secrets`() = runBlocking {
        val events = mutableListOf<UploadDiagnosticEvent>()
        var commits = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("secret response")
            when {
                path.endsWith("5084965799730810217") -> response.code(200).body(byteArrayOf().toResponseBody())
                path.endsWith("16538846908252377752") -> {
                    commits++
                    if (commits == 1) response.code(503).body("secret server body".toResponseBody())
                    else response.code(200).body(byteArrayOf(10, 0).toResponseBody())
                }
                request.method == "PUT" -> response.code(200).body(byteArrayOf(8, 1, 18, 1, 99).toResponseBody())
                else -> response.code(200).header("X-GUploader-UploadID", "secret-upload-token").body(byteArrayOf().toResponseBody())
            }.build()
        }.build()
        val client = GoogleNativeUploadClient(UploadDiagnosticSink(events::add), http, { "secret-auth" }, {})
        val request = GoogleUploadRequest(GoogleCredential("private@example.com", "private-id", emptyList()),
            "/DCIM/private.jpg", 42, 0, ByteArray(20) { 123 }, { ByteArrayInputStream(byteArrayOf(1)) }, UploadFileType.JPEG)
        try {
            client.upload(request)
            fail("missing key must still fail")
        } catch (_: GoogleProtocolException) { }
        assertEquals(2, commits)
        assertEquals(1, events.map { it.correlationId.toString() }.distinct().size)
        assertTrue(events.any { it.stage == UploadStage.COMMIT && it.httpStatus == 503 && it.attempt == 1 })
        assertTrue(events.any { it.stage == UploadStage.COMMIT && it.action == UploadAction.RETRY && it.attempt == 1 })
        assertTrue(events.any { it.stage == UploadStage.COMMIT && it.responseShape?.encoded == "1:2:0[]" && it.attempt == 2 })
        assertTrue(events.any { it.stage == UploadStage.COMMIT && it.failure == UploadFailure.PROTOCOL && it.attempt == 2 })
        assertEquals(UploadAction.FAILURE, events.last().action)
        val text = events.joinToString { it.render() }
        listOf("secret", "private", "DCIM", "example.com").forEach { assertFalse(text.contains(it)) }
    }
}
