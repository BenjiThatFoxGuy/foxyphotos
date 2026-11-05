package gay.benjifox.foxyphotos.provider.google

import java.io.ByteArrayOutputStream

/** Minimal protobuf wire encoder for the bclone-observed Google Photos upload flow. */
internal object GoogleNativeProtocol {
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

    private fun varintField(field: Int, value: Long): ByteArray =
        encodeVarint((field shl 3).toLong()) + encodeVarint(value)

    private fun bytesField(field: Int, value: ByteArray): ByteArray =
        encodeVarint(((field shl 3) or 2).toLong()) + encodeVarint(value.size.toLong()) + value

    private fun encodeVarint(value: Long): ByteArray {
        require(value >= 0) { "Protocol varints must be non-negative." }
        var remaining = value
        return ByteArrayOutputStream().use { output ->
            while (remaining and 0x7f.inv().toLong() != 0L) {
                output.write((remaining and 0x7f).toInt() or 0x80)
                remaining = remaining ushr 7
            }
            output.write(remaining.toInt())
            output.toByteArray()
        }
    }
}
