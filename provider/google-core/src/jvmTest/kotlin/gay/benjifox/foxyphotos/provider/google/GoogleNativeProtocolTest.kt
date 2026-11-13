package gay.benjifox.foxyphotos.provider.google

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleNativeProtocolTest {
    private fun field(number: Int, value: ByteArray) = GoogleNativeProtocol.bytesField(number, value)

    @Test fun decodesConfirmedMediaKeyOnlyFromExpectedEnvelope() {
        val expected = field(1, field(3, field(1, "media-key".encodeToByteArray())))
        assertEquals("media-key", GoogleNativeProtocol.decodeCommittedMediaKey(expected))
        val unrelated = field(4, "media-key".encodeToByteArray())
        try {
            GoogleNativeProtocol.decodeCommittedMediaKey(unrelated)
            throw AssertionError("An unrelated field cannot confirm an upload")
        } catch (_: GoogleProtocolException) { }
    }

    @Test fun decodesExistingKeyOnlyFromHashCheckEnvelope() {
        assertNull(GoogleNativeProtocol.decodeExistingMediaKey(byteArrayOf()))
        val existing = field(1, field(2, field(2, field(1, "already-there".encodeToByteArray()))))
        assertEquals("already-there", GoogleNativeProtocol.decodeExistingMediaKey(existing))
    }

    @Test fun decodesUploadCommitToken() {
        val response = GoogleNativeProtocol.varintField(1, 42) + field(2, byteArrayOf(1, 2))
        val token = GoogleNativeProtocol.decodeCommitToken(response)
        assertEquals(42L, token.number)
        assertArrayEquals(byteArrayOf(1, 2), token.opaque)
    }
}
