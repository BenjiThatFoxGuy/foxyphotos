package gay.benjifox.foxyphotos.core.model

/** Read-only groundwork for explicit folder mapping; never authorizes an album write. */
data class AlbumIdentity(val id: String, val title: String)
sealed interface AlbumNameMatch {
    data class Existing(val folderIdentity: String, val albumId: String) : AlbumNameMatch
    data object Missing : AlbumNameMatch
    data object Ambiguous : AlbumNameMatch
    data object Incomplete : AlbumNameMatch
}

/** Caller retains the full device folder identity. No case folding or invented album identities.
 * Missing is meaningful only after a complete bounded album enumeration, never on listing failure.
 * Even Existing needs capacity/membership checks before future writes.
 */
fun matchFolderAlbum(folderIdentity: String, requestedTitle: String, albums: List<AlbumIdentity>, complete: Boolean): AlbumNameMatch {
    require(folderIdentity.isNotBlank() && requestedTitle.isNotBlank())
    if (!complete) return AlbumNameMatch.Incomplete
    val matches = albums.filter { it.title == requestedTitle }.map { it.id }.distinct()
    if (matches.any { it.isBlank() }) return AlbumNameMatch.Incomplete
    return when (matches.size) {
        0 -> AlbumNameMatch.Missing
        1 -> AlbumNameMatch.Existing(folderIdentity, matches.single())
        else -> AlbumNameMatch.Ambiguous
    }
}
