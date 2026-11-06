package gay.benjifox.foxyphotos

import android.content.Context
import gay.benjifox.foxyphotos.provider.google.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FolderAlbumsTest {
    private lateinit var context: Context
    private class Fake : GoogleAlbumAccess {
        var entries = listOf(GoogleAlbum("existing", "Camera", 0, null))
        var created = 0
        var added = 0
        var loseCreate = false
        var loseAdd = false
        var partial = false
        override suspend fun albumPage(cursor: String?) = GoogleAlbumPage(entries, if (partial) "same" else null)
        override suspend fun createAlbum(title: String, initialMediaKey: String?): String {
            created++
            entries = listOf(GoogleAlbum("created", title, 0, null))
            if (loseCreate) error("lost")
            return "created"
        }
        override suspend fun requestMembership(albumId: String, mediaKey: String) { added++; if (loseAdd) error("lost") }
    }
    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("foxyphotos.folder-albums", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private val folder = "external_primary:DCIM/Camera/"
    private suspend fun map(fake: Fake, key: String = "media", account: String = "fixture") =
        FolderAlbums(context, account, fake).associate(folder, setOf(folder), key) { true }
    @Test fun defaultOffAndReuseExisting() = runBlocking {
        assertFalse(FolderAlbums.enabled(context))
        val fake = Fake()
        assertTrue(map(fake).contains("unconfirmed"))
        map(fake)
        assertEquals(0, fake.created); assertEquals(1, fake.added)
    }
    @Test fun lostCreationNeverRecreatesOrAdoptsByTitle() = runBlocking {
        val fake = Fake().apply { entries = emptyList(); loseCreate = true }
        try { map(fake); fail() } catch (_: IllegalStateException) {}
        assertTrue(map(fake).contains("uncertain"))
        assertEquals(1, fake.created); assertEquals(0, fake.added)
    }
    @Test fun lostMembershipNeverReplaysAcrossInstances() = runBlocking {
        val fake = Fake().apply { loseAdd = true }
        try { map(fake); fail() } catch (_: IllegalStateException) {}
        assertTrue(map(fake).contains("no replay")); assertEquals(1, fake.added)
    }
    @Test fun capacityUnknownFullAndReservationStopSafely() = runBlocking {
        for (count in listOf(null, 20000L)) {
            val fake = Fake().apply { entries = listOf(GoogleAlbum("existing", "Camera", count, null)) }
            assertTrue(map(fake, account = "count$count").contains("capacity"))
            assertEquals(0, fake.added); assertEquals(0, fake.created)
        }
        val fake = Fake().apply { entries = listOf(GoogleAlbum("existing", "Camera", 19999, null)) }
        map(fake, "one"); assertTrue(map(fake, "two").contains("capacity"))
        assertEquals(1, fake.added)
    }
    @Test fun collisionsPartialListingAndRevokedAdmissionNeverWrite() = runBlocking {
        val fake = Fake().apply { entries = entries + GoogleAlbum("duplicate", "Camera", 0, null) }
        assertTrue(map(fake).contains("Ambiguous")); assertEquals(0, fake.added)
        fake.entries = emptyList(); fake.partial = true
        try { map(fake); fail() } catch (_: IllegalStateException) {}
        assertEquals(0, fake.created)
        fake.partial = false
        val engine = FolderAlbums(context, "another", fake)
        assertTrue(engine.associate(folder, setOf(folder, "card:Pictures/Camera/"), "media") { true }.contains("collision"))
        assertEquals("Paused", engine.associate(folder, setOf(folder), "media") { false })
        assertEquals(0, fake.created)
    }
    @Test fun concurrentInstancesSerializeCreationAndMembership() = runBlocking {
        val fake = Fake().apply { entries = emptyList() }
        coroutineScope { (1..4).map { async { map(fake) } }.awaitAll() }
        assertEquals(1, fake.created); assertEquals(0, fake.added)
    }
    @Test fun renamedMappedAlbumAndAnotherFolderCannotSilentlyRemap() = runBlocking {
        val fake = Fake(); map(fake)
        fake.entries = listOf(GoogleAlbum("replacement", "Camera", 0, null))
        assertTrue(map(fake, "new").contains("Mapped album"))
        val other = "card:Pictures/Camera/"
        assertTrue(FolderAlbums(context, "fixture", fake).associate(other, setOf(other), "new") { true }.contains("claimed"))
        assertEquals(1, fake.added); assertEquals(0, fake.created)
    }
}
