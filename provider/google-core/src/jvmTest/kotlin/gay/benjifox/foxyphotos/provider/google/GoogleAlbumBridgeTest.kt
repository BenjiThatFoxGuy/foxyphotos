package gay.benjifox.foxyphotos.provider.google

import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GoogleAlbumBridgeTest {
    private val credential = "androidId=device&app=photos&client_sig=signature&Email=user%40example.com&Token=placeholder&lang=en&service=photos"
    private fun bytes(number: Int, value: ByteArray) = GoogleNativeProtocol.bytesField(number, value)
    private fun text(number: Int, value: String) = bytes(number, value.encodeToByteArray())
    private fun response(body: ByteArray) = GoogleHttpResponse(200, emptyMap(), Base64.getEncoder().encodeToString(body))

    private class Fake(private val responses: List<GoogleHttpResponse>) : GoogleHttpTransport {
        val requests = mutableListOf<GoogleHttpRequest>()
        override fun send(request: GoogleHttpRequest, completion: GoogleHttpCompletion) {
            requests += request
            completion.complete(responses[requests.size - 1], null)
        }
        override fun cancelActive() = Unit
    }

    @Test fun `lists complete album pages and preserves opaque native id`() = runBlocking {
        val entry = text(1, "native-id") + bytes(2, text(5, "Camera Roll") + GoogleNativeProtocol.varintField(7, 3))
        val fake = Fake(listOf(response("Auth=bearer\n".encodeToByteArray()), response(bytes(1, bytes(3, entry)))))
        val albums = GoogleAlbumBridge(fake).list(credential)
        assertEquals(listOf(GoogleAlbum("native-id", "Camera Roll", 3L, null)), albums)
        assertEquals(2, fake.requests.size)
        assertEquals("https://photosdata-pa.googleapis.com/6439526531001121323/18047484249733410717", fake.requests[1].url)
    }

    @Test fun `lists beyond ten pages with one authentication`() = runBlocking {
        val pages = (0..11).map { index ->
            val entry = text(1, "id-$index") + bytes(2, text(5, "Album $index") + GoogleNativeProtocol.varintField(7, 1))
            val container = (if (index < 11) text(1, "cursor-$index") else byteArrayOf()) + bytes(3, entry)
            response(bytes(1, container))
        }
        val fake = Fake(listOf(response("Auth=bearer\n".encodeToByteArray())) + pages)
        val albums = GoogleAlbumBridge(fake).list(credential)
        assertEquals(12, albums.size)
        assertEquals(13, fake.requests.size)
        assertEquals(1, fake.requests.count { it.url == "https://android.googleapis.com/auth" })
        val nextPage = GoogleNativeProtocol.ProtoFields(Base64.getDecoder().decode(fake.requests[2].bodyBase64))
        assertEquals("cursor-0", nextPage.message(1)?.string(4))
    }

    @Test fun `listing failure exposes only bounded HTTP status`() = runBlocking {
        val fake = Fake(listOf(response("Auth=bearer\n".encodeToByteArray()), GoogleHttpResponse(503, emptyMap(), "")))
        val result = GoogleAlbumBridge(fake).listDiagnosed(credential)
        assertNull(result.albums)
        assertEquals("Google listing Provider returned HTTP 503", result.issue)
    }

    @Test fun `listing failure carries the underlying transport reason`() = runBlocking {
        val failing = object : GoogleHttpTransport {
            override fun send(request: GoogleHttpRequest, completion: GoogleHttpCompletion) {
                if (request.url == "https://android.googleapis.com/auth") {
                    completion.complete(response("Auth=bearer\n".encodeToByteArray()), null)
                } else {
                    completion.complete(null, "Provider transport failed: The Internet connection appears to be offline.")
                }
            }
            override fun cancelActive() = Unit
        }
        val result = GoogleAlbumBridge(failing).listDiagnosed(credential)
        assertNull(result.albums)
        assertEquals("Google listing Provider transport failed: The Internet connection appears to be offline.", result.issue)
    }

    @Test fun `delta refresh request carries the library sync token`() {
        val request = GoogleNativeProtocol.ProtoFields(GoogleAlbumListRequest.syncRequest("sync-1"))
        assertEquals("sync-1", request.message(1)?.string(6))
        assertNull(request.message(1)?.string(4))
        assertEquals("", GoogleNativeProtocol.ProtoFields(GoogleAlbumListRequest.request(null)).message(1)?.string(6))
    }

    @Test fun `decode reads library sync token`() {
        val container = bytes(1, "cursor".encodeToByteArray()) + bytes(6, "sync-1".encodeToByteArray())
        val page = GoogleAlbumProtocol.decode(bytes(1, container))
        assertEquals("sync-1", page.syncToken)
        assertEquals("cursor", page.nextCursor)
    }

    @Test fun `delta refresh listing sends sync token and no cursor`() = runBlocking {
        val entry = text(1, "id") + bytes(2, text(5, "Album") + GoogleNativeProtocol.varintField(7, 1))
        val container = bytes(1, byteArrayOf()) + bytes(6, "sync-2".encodeToByteArray()) + bytes(3, entry)
        val fake = Fake(listOf(response("Auth=bearer\n".encodeToByteArray()), response(bytes(1, container))))
        val result = GoogleAlbumBridge(fake).listDetailed(credential, "sync-1")
        assertEquals(1, result.albums.size)
        assertEquals("sync-2", result.syncToken)
        val sent = GoogleNativeProtocol.ProtoFields(Base64.getDecoder().decode(fake.requests[1].bodyBase64))
        assertEquals("sync-1", sent.message(1)?.string(6))
        assertNull(sent.message(1)?.string(4))
    }

    @Test fun `page progress reports each page as it is read`() = runBlocking {
        fun entry(id: String) = text(1, id) + bytes(2, text(5, "Album") + GoogleNativeProtocol.varintField(7, 1))
        val first = text(1, "cursor") + bytes(3, entry("id-1"))
        val second = bytes(3, entry("id-2"))
        val fake = Fake(listOf(
            response("Auth=bearer\n".encodeToByteArray()),
            response(bytes(1, first)),
            response(bytes(1, second))
        ))
        val pages = mutableListOf<String>()
        val result = GoogleAlbumBridge(fake).listDetailed(credential, onPage = { page, count, syncToken ->
            pages += "$page:$count:${syncToken.orEmpty()}"
        })
        assertEquals(2, result.albums.size)
        assertEquals(listOf("1:1:", "2:2:"), pages)
    }

    @Test fun `stalled library request returns bounded diagnostic and cancels its transport`() = runBlocking {
        val hanging = object : GoogleHttpTransport {
            var cancelled = false
            override fun send(request: GoogleHttpRequest, completion: GoogleHttpCompletion) {
                if (request.url == "https://android.googleapis.com/auth") {
                    completion.complete(response("Auth=bearer\n".encodeToByteArray()), null)
                }
            }
            override fun cancelActive() { cancelled = true }
        }
        val result = GoogleAlbumBridge(hanging).listDiagnosedWithin(credential, 50)
        assertNull(result.albums)
        assertEquals("Google album listing timed out", result.issue)
        assertTrue(hanging.cancelled)
    }

    @Test fun `creation receipt proves album id but addition is separate`() = runBlocking {
        val fake = Fake(listOf(response("Auth=bearer\n".encodeToByteArray()),
            response(bytes(1, text(1, "created-id"))),
            response("Auth=bearer\n".encodeToByteArray()), response(byteArrayOf())))
        val bridge = GoogleAlbumBridge(fake)
        assertEquals("created-id", bridge.create(credential, "Camera Roll", "media-id", 123L))
        bridge.add(credential, "created-id", "media-id", 124L)
        val creation = GoogleNativeProtocol.ProtoFields(Base64.getDecoder().decode(fake.requests[1].bodyBase64))
        assertEquals("Camera Roll", creation.string(1))
        assertFalse(creation.messages(4).isEmpty())
        val add = GoogleNativeProtocol.ProtoFields(Base64.getDecoder().decode(fake.requests[3].bodyBase64))
        assertEquals("media-id", add.string(1))
        assertEquals("created-id", add.string(2))
        assertEquals(4, fake.requests.size)
    }
}
