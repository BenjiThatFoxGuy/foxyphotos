package gay.benjifox.foxyphotos.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

class PlatformPrimitivesTest {
    @Test
    fun isoRenderingMatchesJvmInstant() {
        assertEquals(Instant.ofEpochMilli(0).toString(), iso8601FromEpochMillis(0))
        assertEquals(Instant.ofEpochMilli(1).toString(), iso8601FromEpochMillis(1))
        assertEquals(Instant.ofEpochMilli(1_700_000_000_123).toString(), iso8601FromEpochMillis(1_700_000_000_123))
        assertEquals(Instant.ofEpochMilli(-1).toString(), iso8601FromEpochMillis(-1))
    }

    @Test
    fun isoRenderingIsStable() {
        assertEquals("1970-01-01T00:00:00Z", iso8601FromEpochMillis(0))
        assertEquals("1970-01-01T00:00:00.001Z", iso8601FromEpochMillis(1))
        assertEquals("1969-12-31T23:59:59.999Z", iso8601FromEpochMillis(-1))
        assertEquals("2024-02-29T12:34:56.789Z", iso8601FromEpochMillis(1_709_210_096_789))
        assertEquals("2000-01-01T00:00:00Z", iso8601FromEpochMillis(946_684_800_000))
    }

    @Test
    fun currentTimeIsRenderedAsIsoUtc() {
        val rendered = formatCurrentTimeIso()
        val parsed = Instant.parse(rendered)
        assertTrue("unexpected render: $rendered", abs(parsed.toEpochMilli() - System.currentTimeMillis()) < 60_000)
    }

    @Test
    fun digestMatchesMessageDigest() {
        val data = ByteArray(1_000_003) { (it % 251).toByte() }
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }

        val digest = Sha256Digest()
        try {
            var offset = 0
            while (offset < data.size) {
                val chunk = minOf(997, data.size - offset)
                digest.update(data, offset, chunk)
                offset += chunk
            }
            assertEquals(expected, digest.digestHex())
        } finally {
            digest.close()
        }
    }

    @Test
    fun digestIsNotAffectedByChunking() {
        val data = "hello world".toByteArray()
        fun digestOf(vararg chunks: Int): String {
            val digest = Sha256Digest()
            try {
                var offset = 0
                chunks.forEach { chunk ->
                    digest.update(data, offset, chunk)
                    offset += chunk
                }
                return digest.digestHex()
            } finally {
                digest.close()
            }
        }
        val whole = digestOf(data.size)
        assertEquals(whole, digestOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1))
        assertEquals(digestOf(5, 6), digestOf(6, 5))
        assertNotEquals(whole, digestOf(5, 5))
    }
}
