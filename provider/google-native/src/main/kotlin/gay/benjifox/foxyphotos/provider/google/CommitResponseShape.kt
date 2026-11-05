package gay.benjifox.foxyphotos.provider.google

/** wire metadata only. length-delimited fields are opaque except the known 1 -> 3 envelope.
 * no payload bytes are converted to strings and scalar values are never retained.
 * format: field:wire:encoded-value-length; brackets contain known envelope children.
 * ! means malformed/unsupported, ? means a node/output limit was reached.
 */
class CommitResponseShape private constructor(val encoded: String) {
    companion object {
        private const val MAX_NODES = 128
        private const val MAX_CHARS = 4096

        internal fun restore(text: String): CommitResponseShape {
            require(text.length <= MAX_CHARS && text.matches(Regex("[0-9:;\\[\\]!?]*")))
            return CommitResponseShape(text)
        }

        fun summarize(data: ByteArray): CommitResponseShape {
            val output = StringBuilder()
            var nodes = 0
            fun scan(start: Int, end: Int, depth: Int) {
                var offset = start
                fun varint(): Long {
                    var result = 0L
                    for (shift in 0..63 step 7) {
                        require(offset < end)
                        val byte = data[offset++].toInt() and 255
                        require(shift != 63 || byte <= 1)
                        result = result or ((byte and 127).toLong() shl shift)
                        if (byte and 128 == 0) return result
                    }
                    error("invalid varint")
                }
                try {
                    while (offset < end) {
                        if (nodes >= MAX_NODES || output.length > MAX_CHARS - 100) {
                            output.append('?')
                            return
                        }
                        nodes++
                        val tag = varint()
                        val field = tag ushr 3
                        val wire = (tag and 7).toInt()
                        require(field in 1..536870911)
                        val valueStart = offset
                        var childStart = -1
                        val length = when (wire) {
                            0 -> { varint(); offset - valueStart }
                            1 -> { require(end - offset >= 8); offset += 8; 8 }
                            2 -> {
                                val size = varint()
                                require(size >= 0 && size <= end - offset)
                                childStart = offset
                                offset += size.toInt()
                                size.toInt()
                            }
                            5 -> { require(end - offset >= 4); offset += 4; 4 }
                            else -> error("unsupported wire type")
                        }
                        output.append(field).append(':').append(wire).append(':').append(length)
                        val knownEnvelope = (depth == 0 && field == 1L) || (depth == 1 && field == 3L)
                        if (childStart >= 0 && knownEnvelope) {
                            output.append('[')
                            scan(childStart, offset, depth + 1)
                            output.append(']')
                        } else output.append(';')
                    }
                } catch (_: Exception) {
                    output.append('!')
                }
            }
            scan(0, data.size, 0)
            return CommitResponseShape(output.toString())
        }
    }
}
