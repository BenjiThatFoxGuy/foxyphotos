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
