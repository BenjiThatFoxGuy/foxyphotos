package gay.benjifox.foxyphotos.core.platform

import org.junit.Assert.assertThrows
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
    fun digestMatchesKnownAnswerVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256Digest().digestHex(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256Digest().apply { update("abc".toByteArray(), 0, 3) }.digestHex(),
        )
        assertEquals(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            Sha256Digest().apply { update("hello world".toByteArray(), 0, 11) }.digestHex(),
        )
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
    fun digestMatchesMessageDigestAroundBlockBoundaries() {
        // Every length where the padding could land inside or just past a block.
        for (length in intArrayOf(0, 1, 55, 56, 57, 63, 64, 65, 119, 120, 127, 128, 129, 192)) {
            val data = ByteArray(length) { (it * 31 + 7).toByte() }
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(data)
                .joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }
            val digest = Sha256Digest()
            try {
                digest.update(data, 0, length)
                assertEquals("length $length", expected, digest.digestHex())
            } finally {
                digest.close()
            }
        }
    }

    @Test
    fun digestHexIsRepeatable() {
        val digest = Sha256Digest()
        try {
            digest.update("hello world".toByteArray(), 0, 11)
            val first = digest.digestHex()
            assertEquals(first, digest.digestHex())
        } finally {
            digest.close()
        }
    }

    @Test
    fun updateRejectsOutOfRangeReads() {
        val digest = Sha256Digest()
        try {
            assertThrows(IllegalArgumentException::class.java) { digest.update(ByteArray(4), 0, 5) }
            assertThrows(IllegalArgumentException::class.java) { digest.update(ByteArray(4), -1, 2) }
            assertThrows(IllegalArgumentException::class.java) { digest.update(ByteArray(4), 0, -1) }
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
