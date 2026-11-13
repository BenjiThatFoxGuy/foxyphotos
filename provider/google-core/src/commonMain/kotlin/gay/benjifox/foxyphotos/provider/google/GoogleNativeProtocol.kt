package gay.benjifox.foxyphotos.provider.google

/** Minimal protobuf wire codec for the bclone-observed Google Photos upload flow. */
object GoogleNativeProtocol {
    const val hashCheckEndpoint =
        "https://photosdata-pa.googleapis.com/6439526531001121323/5084965799730810217"
    const val uploadEndpoint = "https://photos.googleapis.com/data/upload/uploadmedia/interactive"
    const val commitEndpoint =
        "https://photosdata-pa.googleapis.com/6439526531001121323/16538846908252377752"

    fun encodeHashCheck(sha1: ByteArray): ByteArray {
        val hash = bytesField(1, sha1)
        val request = bytesField(1, hash) + bytesField(2, ByteArray(0))
        return bytesField(1, request)
    }

    fun encodeUploadTokenRequest(fileSize: Long): ByteArray {
        require(fileSize > 0) { "Google Photos cannot upload an empty file." }
        return varintField(1, 2) +
            varintField(2, 2) +
            varintField(3, 1) +
            varintField(4, 3) +
            varintField(7, fileSize)
    }

    fun encodeCommitRequest(
        token: GoogleCommitToken,
        fileName: String,
        sha1: ByteArray,
        modifiedEpochSeconds: Long,
    ): ByteArray {
        val tokenMessage = varintField(1, token.number) + bytesField(2, token.opaque)
        val timestamp = varintField(1, modifiedEpochSeconds) + varintField(2, 46_000_000)
        val commit = bytesField(1, tokenMessage) +
            bytesField(2, fileName.encodeToByteArray()) +
            bytesField(3, sha1) +
            bytesField(4, timestamp) +
            varintField(7, 3) +
            varintField(10, 1)
        val device = bytesField(3, "Pixel XL".encodeToByteArray()) +
            bytesField(4, "Google".encodeToByteArray()) +
            varintField(5, 28)
        return bytesField(1, commit) + bytesField(2, device) + bytesField(3, byteArrayOf(1, 3))
    }

    fun decodeExistingMediaKey(response: ByteArray): String? =
        ProtoFields(response).message(1)?.message(2)?.message(2)?.string(1)?.ifBlank { null }

    fun decodeCommitToken(response: ByteArray): GoogleCommitToken {
        val fields = ProtoFields(response)
        return GoogleCommitToken(
            number = fields.varint(1) ?: throw GoogleProtocolException("Upload response is missing a commit token."),
            opaque = fields.bytes(2) ?: throw GoogleProtocolException("Upload response is missing a commit token."),
        )
    }

    fun decodeCommittedMediaKey(response: ByteArray): String =
        ProtoFields(response).message(1)?.message(3)?.string(1)?.takeIf(String::isNotBlank)
            ?: throw GoogleProtocolException("Google commit response is missing a media key.")

    class ProtoFields(data: ByteArray) {
        private val fields = mutableMapOf<Int, MutableList<Any>>()

        init {
            var offset = 0
            while (offset < data.size) {
                val (tag, afterTag) = readVarint(data, offset)
                offset = afterTag
                val field = (tag shr 3).toInt()
                when ((tag and 7).toInt()) {
                    0 -> {
                        val (value, afterValue) = readVarint(data, offset)
                        fields.getOrPut(field, ::mutableListOf).add(value)
                        offset = afterValue
                    }
                    2 -> {
                        val (length, afterLength) = readVarint(data, offset)
                        require(length >= 0 && length <= (data.size - afterLength).toLong()) { "Malformed Google protobuf response." }
                        val end = afterLength + length.toInt()
                        require(end <= data.size) { "Malformed Google protobuf response." }
                        fields.getOrPut(field, ::mutableListOf).add(data.copyOfRange(afterLength, end))
                        offset = end
                    }
                    else -> throw GoogleProtocolException("Unsupported Google protobuf wire type.")
                }
            }
        }

        fun messages(field: Int): List<ProtoFields> = fields[field].orEmpty().mapNotNull { (it as? ByteArray)?.let(::ProtoFields) }
        fun varint(field: Int): Long? = fields[field]?.lastOrNull() as? Long
        fun bytes(field: Int): ByteArray? = fields[field]?.lastOrNull() as? ByteArray
        fun message(field: Int): ProtoFields? = bytes(field)?.let(::ProtoFields)
        fun string(field: Int): String? = bytes(field)?.decodeToString()
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var offset = start
        while (offset < data.size && shift < 64) {
            val byte = data[offset++].toInt() and 0xff
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return value to offset
            shift += 7
        }
        throw GoogleProtocolException("Malformed Google protobuf response.")
    }

    fun varintField(field: Int, value: Long): ByteArray =
        encodeVarint((field shl 3).toLong()) + encodeVarint(value)

    fun bytesField(field: Int, value: ByteArray): ByteArray =
        encodeVarint(((field shl 3) or 2).toLong()) + encodeVarint(value.size.toLong()) + value

    private fun encodeVarint(value: Long): ByteArray {
        require(value >= 0) { "Protocol varints must be non-negative." }
        var remaining = value
        val output = mutableListOf<Byte>()
        while (remaining and 0x7f.inv().toLong() != 0L) {
            output.add(((remaining and 0x7f).toInt() or 0x80).toByte())
            remaining = remaining ushr 7
        }
        output.add(remaining.toByte())
        return output.toByteArray()
    }
}

data class GoogleCommitToken(val number: Long, val opaque: ByteArray)

class GoogleProtocolException(message: String) : IllegalStateException(message)
