package gay.benjifox.foxyphotos

import android.content.Context
import gay.benjifox.foxyphotos.core.backup.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ManualCopyStorageTest {
    @Test fun uncertaintySurvivesCancelCloseAndDifferentAlbumButNeverCrossesAccounts() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("foxyphotos.manual-copy", Context.MODE_PRIVATE).edit().clear().commit()
        val store = ManualCopyStorage(app)
        assertFalse(store.enabled())
        val batch = store.create("apple-a", "google-a", "album-a", "Trip", listOf("source"))
        assertTrue(batch.matchesAccounts("apple-a", "google-a"))
        assertFalse(batch.matchesAccounts("apple-b", "google-a"))
        assertFalse(batch.matchesAccounts("apple-a", "google-b"))
        store.save(batch.update(listOf(CopyEntry("source", CopyPhase.UPLOAD_UNCERTAIN, "a".repeat(40))), cancelled = true))
        assertTrue(ManualCopyStorage(app).load()!!.cancelled)
        store.close()
        val next = store.create("apple-a", "google-a", "album-b", "Other", listOf("source"))
        assertEquals(CopyPhase.UPLOAD_UNCERTAIN, next.entries.single().phase)
        store.close()
        assertEquals(CopyPhase.QUEUED, store.create("apple-a", "google-b", "album-a", "Trip", listOf("source")).entries.single().phase)
        store.close()
        assertEquals(CopyPhase.QUEUED, store.create("apple-b", "google-a", "album-a", "Trip", listOf("source")).entries.single().phase)
    }
    @Test fun confirmedContentCanRequestAnotherAlbumWithoutReuploading() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("foxyphotos.manual-copy", Context.MODE_PRIVATE).edit().clear().commit()
        val store = ManualCopyStorage(app)
        val batch = store.create("a", "g", "one", "One", listOf("item"))
        store.save(batch.update(listOf(CopyEntry("item", CopyPhase.ORGANIZATION_UNCONFIRMED, "b".repeat(40), "key"))))
        store.close()
        assertEquals(CopyPhase.ORGANIZATION_UNCONFIRMED, store.create("a", "g", "one", "One", listOf("item")).entries.single().phase)
        store.close()
        assertEquals(CopyPhase.UPLOADED, store.create("a", "g", "two", "Two", listOf("item")).entries.single().phase)
    }
}
