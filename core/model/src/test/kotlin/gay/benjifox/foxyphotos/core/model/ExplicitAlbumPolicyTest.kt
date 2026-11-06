package gay.benjifox.foxyphotos.core.model

import org.junit.Assert.*
import org.junit.Test

class ExplicitAlbumPolicyTest {
    @Test fun explicitIdentityNotTitleDeterminesDestination() {
        assertEquals(ExplicitAlbumResolution.Existing("picked"), resolveExplicitAlbum("picked", listOf(
            AlbumIdentity("picked", "Renamed holiday"), AlbumIdentity("other", "Camera"))))
        assertEquals(ExplicitAlbumResolution.Missing, resolveExplicitAlbum("picked", listOf(AlbumIdentity("replacement", "Camera"))))
        assertEquals(ExplicitAlbumResolution.Missing, resolveExplicitAlbum("picked", listOf(
            AlbumIdentity("picked", "one"), AlbumIdentity("picked", "conflicting"))))
    }
}
