package spike

import uniffi.foxy_spike_codec.decodeCommittedMediaKey
import uniffi.foxy_spike_codec.encodeUploadTokenRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RustCodecTest {
    private fun bytesField(number: Int, value: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        var tag = (number shl 3) or 2
        while (tag and 0xffff_ff80.toInt() != 0) {
            out.add(((tag and 0x7f) or 0x80).toByte())
            tag = tag ushr 7
        }
        out.add(tag.toByte())
        var length = value.size
        while (length and 0xffff_ff80.toInt() != 0) {
            out.add(((length and 0x7f) or 0x80).toByte())
            length = length ushr 7
        }
        out.add(length.toByte())
        val result = ByteArray(out.size + value.size) { index ->
            if (index < out.size) out[index] else value[index - out.size]
        }
        return result
    }

    @Test
    fun uploadTokenBytesMatchKotlinCodec() {
        assertEquals(
            listOf<Byte>(8, 2, 16, 2, 24, 1, 32, 3, 56, 123),
            encodeUploadTokenRequest(123u).toList(),
        )
    }

    @Test
    fun committedMediaKeyUsesExpectedEnvelope() {
        val envelope = bytesField(1, bytesField(3, bytesField(1, "media-key".encodeToByteArray())))
        assertEquals("media-key", decodeCommittedMediaKey(envelope))
        val unrelated = bytesField(4, "media-key".encodeToByteArray())
        assertFailsWith<Exception> { decodeCommittedMediaKey(unrelated) }
    }
}
