package gay.benjifox.foxyphotos.provider.google

import gay.benjifox.foxyphotos.core.model.LibraryItem
import gay.benjifox.foxyphotos.core.model.LibraryPage

/** Wire field mask observed in bclone f6ad6b63, api/protobuf.go; not an account response. */
internal object GoogleLibraryProtocol {
    fun request(cursor: String?): ByteArray = GoogleAlbumListRequest.request(cursor)
    fun decode(bytes: ByteArray): LibraryPage {
        val container = GoogleNativeProtocol.ProtoFields(bytes).message(1)
            ?: throw GoogleProtocolException("Google library response has no container.")
        val entries = container.messages(2)
        require(entries.size <= 5000) { "Google library page exceeds safety limit." }
        val items = entries.map { item ->
            val key = item.string(1)?.takeIf { it.isNotBlank() && it.length <= 4096 }
                ?: throw GoogleProtocolException("Google library item has no identity.")
            val metadata = item.message(2)
            val seconds = metadata?.varint(7) ?: 0L
            LibraryItem(key, seconds.takeIf { it in 0..253402300799L }?.times(1000) ?: 0L,
                item.message(5)?.varint(1) == 1L)
        }
        val cursor = container.string(1)?.takeIf { it.isNotEmpty() }
        require(cursor == null || cursor.length <= 16384)
        return LibraryPage(items.distinctBy { it.id }, cursor)
    }
}
