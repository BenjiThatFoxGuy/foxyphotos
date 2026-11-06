package gay.benjifox.foxyphotos.core.model

import org.junit.Assert.*
import org.junit.Test

class AlbumMatchingTest {
    @Test fun uniqueIdentityIsReusedWithoutConflatingDeviceFolders() {
        val albums = listOf(AlbumIdentity("remote", "Camera"))
        assertEquals(AlbumNameMatch.Existing("primary/DCIM/Camera/", "remote"), matchFolderAlbum("primary/DCIM/Camera/", "Camera", albums, true))
        assertEquals(AlbumNameMatch.Existing("secondary/DCIM/Camera/", "remote"), matchFolderAlbum("secondary/DCIM/Camera/", "Camera", albums, true))
    }
    @Test fun incompleteAndAmbiguousListingsNeverSuggestCreationOrReuse() {
        val a = AlbumIdentity("one", "Camera")
        assertEquals(AlbumNameMatch.Incomplete, matchFolderAlbum("folder", "Camera", emptyList(), false))
        assertEquals(AlbumNameMatch.Incomplete, matchFolderAlbum("folder", "Camera", listOf(a), false))
        assertEquals(AlbumNameMatch.Ambiguous, matchFolderAlbum("folder", "Camera", listOf(a, AlbumIdentity("two", "Camera")), true))
        assertEquals(AlbumNameMatch.Missing, matchFolderAlbum("folder", "camera", listOf(a), true))
        assertEquals(AlbumNameMatch.Existing("folder", "one"), matchFolderAlbum("folder", "Camera", listOf(a, a), true))
    }
}
