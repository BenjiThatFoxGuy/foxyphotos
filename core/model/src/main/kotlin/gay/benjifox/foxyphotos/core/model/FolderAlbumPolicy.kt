package gay.benjifox.foxyphotos.core.model

/** Conservative bound includes unresolved requests, even when a newer remote count may include them. */
fun albumHasCapacity(remoteCount: Long?, reserved: Int): Boolean =
    remoteCount != null && remoteCount >= 0 && reserved >= 0 && remoteCount < 20_000L - reserved

sealed interface ExplicitAlbumResolution {
    data class Existing(val albumId: String) : ExplicitAlbumResolution
    data object Missing : ExplicitAlbumResolution
}

/** Titles are presentation only for explicit choices. Incomplete enumeration must fail at the adapter. */
fun resolveExplicitAlbum(id: String, albums: List<AlbumIdentity>): ExplicitAlbumResolution =
    if (id.isNotBlank() && albums.count { it.id == id } == 1) ExplicitAlbumResolution.Existing(id)
    else ExplicitAlbumResolution.Missing

fun folderAlbumTitle(folder: String): String = folder.trimEnd('/').substringAfterLast('/')

/** Equal leaf names never implicitly merge distinct actual-volume/folder identities. */
fun folderTitleCollides(folder: String, knownFolders: Set<String>): Boolean =
    knownFolders.any { it != folder && folderAlbumTitle(it) == folderAlbumTitle(folder) }
