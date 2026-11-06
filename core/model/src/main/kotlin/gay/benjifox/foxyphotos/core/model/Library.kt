package gay.benjifox.foxyphotos.core.model

/** Identities are scoped to this provider and account session; never match by filename. */
data class LibraryItem(val id: String, val timestampMillis: Long, val video: Boolean)
data class LibraryPage(val items: List<LibraryItem>, val nextCursor: String?)

/** A call performs one bounded page operation, never an implicit full-library crawl. */
interface ReadOnlyLibrary {
    suspend fun page(cursor: String? = null): LibraryPage
    suspend fun preview(id: String, large: Boolean = false): ByteArray
}
