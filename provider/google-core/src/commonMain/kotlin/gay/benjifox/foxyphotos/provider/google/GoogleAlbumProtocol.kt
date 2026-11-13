package gay.benjifox.foxyphotos.provider.google

import gay.benjifox.foxyphotos.provider.google.GoogleNativeProtocol.bytesField
import gay.benjifox.foxyphotos.provider.google.GoogleNativeProtocol.varintField

/** Native keys and optional web IDs are distinct. Never derive one from the other. */
data class GoogleAlbum(val id: String, val title: String, val count: Long?, val webId: String?)
data class GoogleAlbumPage(val albums: List<GoogleAlbum>, val nextCursor: String?, val syncToken: String? = null)

interface GoogleAlbumAccess {
    suspend fun albumPage(cursor: String? = null): GoogleAlbumPage
    suspend fun createAlbum(title: String, initialMediaKey: String? = null): String
    suspend fun requestMembership(albumId: String, mediaKey: String)
}

/** bclone f6ad6b63 + gpmc 94b1b267 collection metadata; no invented content endpoint. */
object GoogleAlbumProtocol {
    fun decode(bytes: ByteArray): GoogleAlbumPage {
        val root = GoogleNativeProtocol.ProtoFields(bytes).message(1)
            ?: throw GoogleProtocolException("Album listing unavailable")
        val entries = root.messages(3)
        require(entries.size <= 5000)
        val albums = entries.map {
            val id = it.string(1)?.takeIf { key -> key.isNotBlank() && key.length <= 4096 }
                ?: throw GoogleProtocolException("Album identity unavailable")
            val meta = it.message(2) ?: throw GoogleProtocolException("Album metadata unavailable")
            val title = meta.string(5) ?: throw GoogleProtocolException("Album title unavailable")
            require(title.length <= 4096)
            GoogleAlbum(id, title, meta.varint(7)?.takeIf { n -> n >= 0 },
                it.message(4)?.message(2)?.string(3)?.takeIf { key -> key.isNotBlank() && key.length <= 4096 })
        }
        val cursor = root.string(1)?.takeIf { it.isNotEmpty() }
        require(cursor == null || cursor.length <= 16384)
        // Field 1.6 is the library sync token: a delta refresh is far smaller
        // than re-pulling every album and media item on each backup.
        val syncToken = root.string(6)?.takeIf { it.isNotEmpty() }
        require(syncToken == null || syncToken.length <= 16384)
        return GoogleAlbumPage(albums, cursor, syncToken)
    }
    private fun text(field: Int, value: String) = bytesField(field, value.encodeToByteArray())
    private fun device() = text(3, "Pixel XL") + text(4, "Google") + varintField(5, 28)
    fun create(title: String, seconds: Long, initialMediaKey: String? = null): ByteArray =
        text(1, title) + varintField(2, seconds) + varintField(3, 1) +
            (initialMediaKey?.let { bytesField(4, bytesField(1, text(1, it))) } ?: byteArrayOf()) + bytesField(6, byteArrayOf()) +
            bytesField(7, varintField(1, 3)) + bytesField(8, device())
    fun add(albumId: String, key: String, seconds: Long): ByteArray {
        require(albumId.isNotBlank() && key.isNotBlank() && albumId.length <= 4096 && key.length <= 4096)
        return text(1, key) + text(2, albumId) + bytesField(5, varintField(1, 2)) +
            bytesField(6, device()) + varintField(7, seconds)
    }
    fun createdId(bytes: ByteArray): String = GoogleNativeProtocol.ProtoFields(bytes).message(1)?.string(1)
        ?.takeIf { it.isNotBlank() && it.length <= 4096 } ?: throw GoogleProtocolException("Creation unconfirmed")
}
