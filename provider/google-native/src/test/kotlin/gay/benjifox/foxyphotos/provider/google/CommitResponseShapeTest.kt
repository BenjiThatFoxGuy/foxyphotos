package gay.benjifox.foxyphotos.provider.google

import org.junit.Assert.*
import org.junit.Test

class CommitResponseShapeTest {
    @Test fun `shows expected envelope and opaque key length without string contents`() {
        val secret = "signed-token-secret.jpg".toByteArray()
        val response = bytes(1, bytes(3, bytes(1, secret)))
        val shape = CommitResponseShape.summarize(response)
        assertEquals("1:2:${secret.size + 4}[3:2:${secret.size + 2}[1:2:${secret.size};]]", shape.encoded)
        assertFalse(shape.encoded.contains("secret"))
        assertNotEquals(shape.encoded, CommitResponseShape.summarize(bytes(2, secret)).encoded)
    }

    @Test fun `limits malformed hostile and wide responses`() {
        val malformed = listOf(byteArrayOf(0), byteArrayOf(10, 127, 1), ByteArray(20) { 0xff.toByte() }, byteArrayOf(15))
        malformed.forEach { assertTrue(CommitResponseShape.summarize(it).encoded.contains('!')) }
        val wide = ByteArray(100_000) { if (it % 2 == 0) 8 else 1 }
        val bounded = CommitResponseShape.summarize(wide).encoded
        assertTrue(bounded.length <= 4096)
        assertTrue(bounded.endsWith('?'))
        val scalarSecret = bytes(1, bytes(3, bytes(1, byteArrayOf(8, 123))))
        assertFalse(CommitResponseShape.summarize(scalarSecret).encoded.contains("123"))
    }

    private fun bytes(field: Int, data: ByteArray) = byteArrayOf(((field shl 3) or 2).toByte(), data.size.toByte()) + data
}
