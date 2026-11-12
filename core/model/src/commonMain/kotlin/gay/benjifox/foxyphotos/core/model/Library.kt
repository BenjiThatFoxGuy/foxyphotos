package gay.benjifox.foxyphotos.core.model

/** Identities are scoped to this provider and account session; never match by filename. */
data class LibraryItem(val id: String, val timestampMillis: Long, val video: Boolean)
data class LibraryPage(val items: List<LibraryItem>, val nextCursor: String?)

/** A call performs one bounded page operation, never an implicit full-library crawl. */
interface ReadOnlyLibrary {
    suspend fun page(cursor: String? = null): LibraryPage
    suspend fun preview(id: String, large: Boolean = false): ByteArray
}

/** Album identity includes its library/zone; names are display only. */
data class LibraryAlbum(val id: String, val title: String)
data class LibraryAlbumPage(val albums: List<LibraryAlbum>, val nextCursor: String?)
interface AlbumReadOnlyLibrary : ReadOnlyLibrary {
    suspend fun albums(cursor: String? = null): LibraryAlbumPage
    suspend fun albumPage(albumId: String, cursor: String? = null): LibraryPage
    /** Bounded original read for explicitly selected items; never an upload/delete operation. */
    suspend fun read(id: String, maxBytes: Int): ByteArray
}

/** Digest must come from an actual bounded remote byte stream, never metadata or a preview.
 * A returned digest makes no original-quality claim until compared to local original bytes.
 * Implementations throw on authentication, partial, unavailable, oversized or truncated reads.
 */
interface RemoteOriginalReader {
    suspend fun originalHash(id: String, expectedSize: Long): String
}
