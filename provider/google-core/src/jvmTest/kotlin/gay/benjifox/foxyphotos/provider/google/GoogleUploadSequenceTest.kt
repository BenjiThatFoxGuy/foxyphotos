package gay.benjifox.foxyphotos.provider.google

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class GoogleUploadSequenceTest {
    private val credential = GoogleCredentialParser.parse(
        "androidId=device&app=photos&client_sig=signature&Email=user%40example.com&Token=placeholder&lang=en&service=photos"
    )
    private val digest = ByteArray(20) { it.toByte() }
    private fun field(number: Int, bytes: ByteArray) = GoogleNativeProtocol.bytesField(number, bytes)
    private fun response(body: ByteArray, headers: Map<String, String> = emptyMap()) =
        GoogleHttpResponse(200, headers, Base64.getEncoder().encodeToString(body))

    private class Fake(private val responses: List<GoogleHttpResponse>) : GoogleHttpTransport {
        val requests = mutableListOf<GoogleHttpRequest>()
        override fun send(request: GoogleHttpRequest, completion: GoogleHttpCompletion) {
            requests += request
            completion.complete(responses[requests.size - 1], null)
        }
        override fun cancelActive() = Unit
    }

    @Test fun `confirmed commit returns receipt in same run after disk byte request`() = runBlocking {
        val token = GoogleNativeProtocol.varintField(1, 23) + field(2, byteArrayOf(4, 5))
        val committed = field(1, field(3, field(1, "confirmed-key".encodeToByteArray())))
        val fake = Fake(listOf(
            response("Auth=bearer\nExpiry=123\n".encodeToByteArray()),
            response(byteArrayOf()),
            response(byteArrayOf(), mapOf("X-GUploader-UploadID" to "upload-id")),
            response(token), response(committed),
        ))
        val receipt = GoogleUploadSequence(fake).upload(credential, "/original.mov", "original.mov", 42, 100, digest)
        assertEquals(ConfirmedGoogleUpload("confirmed-key", false), receipt)
        assertEquals(listOf("POST", "POST", "POST", "PUT", "POST"), fake.requests.map { it.method })
        assertEquals("/original.mov", fake.requests[3].originalFilePath)
        assertNull(fake.requests[3].body)
        assertEquals("sha1=${Base64.getEncoder().encodeToString(digest)}", fake.requests[2].headers["X-Goog-Hash"])
    }

    @Test fun `existing key stops without token bytes or commit`() = runBlocking {
        val existing = field(1, field(2, field(2, field(1, "present-key".encodeToByteArray()))))
        val fake = Fake(listOf(response("Auth=bearer\n".encodeToByteArray()), response(existing)))
        assertEquals(ConfirmedGoogleUpload("present-key", true),
            GoogleUploadSequence(fake).upload(credential, "/original.jpg", "original.jpg", 42, 100, digest))
        assertEquals(2, fake.requests.size)
    }

    @Test fun `HTTP success without confirmed key remains uncertain`() = runBlocking {
        val token = GoogleNativeProtocol.varintField(1, 23) + field(2, byteArrayOf(4, 5))
        val fake = Fake(listOf(
            response("Auth=bearer\n".encodeToByteArray()), response(byteArrayOf()),
            response(byteArrayOf(), mapOf("X-GUploader-UploadID" to "upload-id")),
            response(token), response(byteArrayOf()),
        ))
        try {
            GoogleUploadSequence(fake).upload(credential, "/original.jpg", "original.jpg", 42, 100, digest)
            fail("No commit receipt is not a confirmed upload")
        } catch (failure: GoogleUploadSequenceException) {
            assertTrue(failure.message!!.contains("uncertain"))
        }
    }
}
