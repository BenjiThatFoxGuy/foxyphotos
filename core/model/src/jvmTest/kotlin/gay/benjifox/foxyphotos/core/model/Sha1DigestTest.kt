package gay.benjifox.foxyphotos.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class Sha1DigestTest {
    @Test fun standardVectors() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", sha1(byteArrayOf()).hex())
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1("abc".encodeToByteArray()).hex())
        assertEquals("34aa973cd4c4daa4f61eeb2bdbad27316534016f", sha1("a".repeat(1_000_000).encodeToByteArray()).hex())
    }
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
