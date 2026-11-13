package gay.benjifox.foxyphotos.core.model

/** SHA-1 for the provider's legacy duplicate lookup, never for security decisions. */
internal fun sha1(data: ByteArray): ByteArray {
    val length = data.size.toLong() * 8
    val paddedSize = ((data.size.toLong() + 9 + 63) / 64 * 64).toInt()
    val padded = ByteArray(paddedSize)
    data.copyInto(padded)
    padded[data.size] = 0x80.toByte()
    for (i in 0 until 8) padded[paddedSize - 1 - i] = (length ushr (8 * i)).toByte()
    var h0 = 0x67452301
    var h1 = 0xefcdab89.toInt()
    var h2 = 0x98badcfe.toInt()
    var h3 = 0x10325476
    var h4 = 0xc3d2e1f0.toInt()
    val words = IntArray(80)
    for (offset in padded.indices step 64) {
        for (i in 0 until 16) {
            val j = offset + i * 4
            words[i] = ((padded[j].toInt() and 255) shl 24) or
                ((padded[j + 1].toInt() and 255) shl 16) or
                ((padded[j + 2].toInt() and 255) shl 8) or
                (padded[j + 3].toInt() and 255)
        }
        for (i in 16 until 80) {
            val value = words[i - 3] xor words[i - 8] xor words[i - 14] xor words[i - 16]
            words[i] = (value shl 1) or (value ushr 31)
        }
        var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
        for (i in 0 until 80) {
            val f: Int
            val k: Int
            when (i) {
                in 0..19 -> { f = (b and c) or (b.inv() and d); k = 0x5a827999 }
                in 20..39 -> { f = b xor c xor d; k = 0x6ed9eba1 }
                in 40..59 -> { f = (b and c) or (b and d) or (c and d); k = 0x8f1bbcdc.toInt() }
                else -> { f = b xor c xor d; k = 0xca62c1d6.toInt() }
            }
            val temp = ((a shl 5) or (a ushr 27)) + f + e + k + words[i]
            e = d; d = c; c = (b shl 30) or (b ushr 2); b = a; a = temp
        }
        h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
    }
    return ByteArray(20) { index ->
        val word = when (index / 4) { 0 -> h0; 1 -> h1; 2 -> h2; 3 -> h3; else -> h4 }
        (word ushr (24 - 8 * (index % 4))).toByte()
    }
}
