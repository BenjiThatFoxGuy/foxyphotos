package gay.benjifox.foxyphotos.core.platform

/** Wall-clock time in epoch milliseconds. */
expect fun currentTimeMillis(): Long

/**
 * ISO-8601 UTC rendering of an epoch-millisecond timestamp, e.g. 2026-11-12T21:38:22.123Z.
 *
 * Pure Kotlin rather than a platform date API, so every target renders identical
 * diagnostics and there is no per-platform formatting seam to keep in sync.
 */
fun formatEpochMillisIso(millis: Long): String = iso8601FromEpochMillis(millis)

/** ISO-8601 UTC rendering of the current wall-clock time. */
fun formatCurrentTimeIso(): String = formatEpochMillisIso(currentTimeMillis())

/**
 * Incremental SHA-256 over bounded reads.
 *
 * Implemented in pure Kotlin instead of delegating to a platform digest so the
 * same code runs on every target with no cinterop seam, and so the JVM can prove
 * it against java.security.MessageDigest. Standard FIPS 180-4 construction:
 * 64-byte blocks, eight 32-bit working registers, 64 rounds.
 *
 * Not thread-safe. [digestHex] is repeatable and does not consume the digest, so
 * callers may hash, read the digest, and keep feeding data.
 */
class Sha256Digest {
    private var state = intArrayOf(
        0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
    )
    private val buffer = ByteArray(BLOCK_BYTES)
    private val words = IntArray(ROUND_COUNT)
    private var buffered = 0
    private var totalBytes = 0L

    fun update(data: ByteArray, offset: Int, count: Int) {
        require(offset >= 0) { "offset must not be negative: $offset" }
        require(count >= 0) { "count must not be negative: $count" }
        require(offset + count <= data.size) {
            "range $offset..${offset + count} is outside the ${data.size} byte buffer"
        }
        if (count == 0) return
        totalBytes += count.toLong()
        var index = offset
        var remaining = count
        if (buffered > 0) {
            val take = minOf(BLOCK_BYTES - buffered, remaining)
            System.arraycopy(data, index, buffer, buffered, take)
            buffered += take
            index += take
            remaining -= take
            if (buffered == BLOCK_BYTES) {
                compress(buffer, 0)
                buffered = 0
            }
        }
        while (remaining >= BLOCK_BYTES) {
            compress(data, index)
            index += BLOCK_BYTES
            remaining -= BLOCK_BYTES
        }
        if (remaining > 0) {
            System.arraycopy(data, index, buffer, 0, remaining)
            buffered = remaining
        }
    }

    fun digestHex(): String {
        val finalized = state.copyOf()
        val bitLength = totalBytes * 8
        // Pad: 0x80, then the minimum number of zero bytes, then the 64-bit
        // big-endian bit length, so the padded message is a whole number of blocks.
        val padding = (BLOCK_BYTES - ((buffered + 1 + LENGTH_BYTES) % BLOCK_BYTES)) % BLOCK_BYTES
        val tail = ByteArray(1 + padding + LENGTH_BYTES)
        tail[0] = 0x80.toByte()
        for (i in 0 until LENGTH_BYTES) {
            tail[tail.size - LENGTH_BYTES + i] = (bitLength ushr (56 - 8 * i)).toByte()
        }
        // Merge into a scratch buffer: the digest keeps its buffered bytes so the
        // digest can be read more than once and followed by more data.
        val padded = ByteArray(buffered + tail.size)
        if (buffered > 0) System.arraycopy(buffer, 0, padded, 0, buffered)
        System.arraycopy(tail, 0, padded, buffered, tail.size)
        for (offset in padded.indices step BLOCK_BYTES) {
            compressInto(finalized, padded, offset)
        }
        val out = ByteArray(DIGEST_BYTES)
        for (i in 0 until 8) {
            out[i * 4] = (finalized[i] ushr 24).toByte()
            out[i * 4 + 1] = (finalized[i] ushr 16).toByte()
            out[i * 4 + 2] = (finalized[i] ushr 8).toByte()
            out[i * 4 + 3] = finalized[i].toByte()
        }
        return out.joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }
    }

    fun close() {
        // Nothing to release: the digest is pure Kotlin state.
    }

    private fun compress(data: ByteArray, offset: Int) {
        compressInto(state, data, offset)
    }

    private fun compressInto(target: IntArray, data: ByteArray, offset: Int) {
        for (i in 0 until 16) {
            val j = offset + i * 4
            words[i] = ((data[j].toInt() and 0xFF) shl 24) or
                ((data[j + 1].toInt() and 0xFF) shl 16) or
                ((data[j + 2].toInt() and 0xFF) shl 8) or
                (data[j + 3].toInt() and 0xFF)
        }
        for (i in 16 until ROUND_COUNT) {
            val s0 = rotateRight(words[i - 15], 7) xor rotateRight(words[i - 15], 18) xor (words[i - 15] ushr 3)
            val s1 = rotateRight(words[i - 2], 17) xor rotateRight(words[i - 2], 19) xor (words[i - 2] ushr 10)
            words[i] = words[i - 16] + s0 + words[i - 7] + s1
        }
        var a = target[0]
        var b = target[1]
        var c = target[2]
        var d = target[3]
        var e = target[4]
        var f = target[5]
        var g = target[6]
        var h = target[7]
        for (i in 0 until ROUND_COUNT) {
            val s1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = h + s1 + ch + ROUND_CONSTANTS[i] + words[i]
            val s0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        target[0] += a
        target[1] += b
        target[2] += c
        target[3] += d
        target[4] += e
        target[5] += f
        target[6] += g
        target[7] += h
    }

    private companion object {
        const val BLOCK_BYTES = 64
        const val DIGEST_BYTES = 32
        const val LENGTH_BYTES = 8
        const val ROUND_COUNT = 64

        /**
         * First 32 bits of the fractional parts of the cube roots of the first 64 primes.
         * Literals above 0x7fffffff carry an explicit `.toInt()`: Kotlin types oversized
         * hexadecimal literals as Long, and these are the bit patterns we want.
         */
        val ROUND_CONSTANTS = intArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
            0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
            0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
            0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
        )
    }
}

private fun rotateRight(value: Int, count: Int): Int = (value ushr count) or (value shl (32 - count))

/**
 * Pure-Kotlin ISO-8601 rendering. Matches java.time.Instant.toString() for
 * millisecond inputs, so both platforms render identical diagnostics.
 */
internal fun iso8601FromEpochMillis(millis: Long): String = buildString {
    val totalSeconds = millis.floorDiv(1000L)
    val fraction = millis.mod(1000L)
    val days = totalSeconds.floorDiv(86_400L)
    val secondsOfDay = totalSeconds.mod(86_400L)
    val (year, month, day) = civilFromDays(days)
    append(year.toString().padStart(4, '0'))
    append('-')
    append(month.toString().padStart(2, '0'))
    append('-')
    append(day.toString().padStart(2, '0'))
    append('T')
    append((secondsOfDay / 3600L).toString().padStart(2, '0'))
    append(':')
    append(((secondsOfDay % 3600L) / 60L).toString().padStart(2, '0'))
    append(':')
    append((secondsOfDay % 60L).toString().padStart(2, '0'))
    if (fraction != 0L) {
        append('.')
        append(fraction.toString().padStart(3, '0'))
    }
    append('Z')
}

/** Howard Hinnant's civil-from-days algorithm; [days] counts from 1970-01-01. */
private fun civilFromDays(days: Long): Triple<Long, Int, Int> {
    val z = days + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val year = yearOfEra + era * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthPrime = (5 * dayOfYear + 2) / 153
    val day = (dayOfYear - (153 * monthPrime + 2) / 5 + 1).toInt()
    val month = (if (monthPrime < 10) monthPrime + 3 else monthPrime - 9).toInt()
    return Triple(if (month <= 2) year + 1 else year, month, day)
}
