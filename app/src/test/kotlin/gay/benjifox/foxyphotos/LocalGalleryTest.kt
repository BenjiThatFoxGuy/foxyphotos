package gay.benjifox.foxyphotos

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalGalleryTest {
    @Test fun selectionUsesStableUrisAndNeverAddsNeighbors() {
        val first = "content://media/images/1"
        val second = "content://media/images/2"
        val selected = toggleGallerySelection(listOf(first), second)
        assertEquals(listOf(first, second), selected)
        assertEquals(listOf(second), toggleGallerySelection(selected, first))
        assertEquals(emptyList<String>(), toggleGallerySelection(listOf(second), second))
    }

    @Test fun zoomPanIsBoundedAndFitClearsTranslation() {
        val size = androidx.compose.ui.unit.IntSize(200, 400)
        val pan = androidx.compose.ui.geometry.Offset(999f, -999f)
        assertEquals(androidx.compose.ui.geometry.Offset(100f, -200f), boundedGalleryPan(pan, 2f, size))
        assertEquals(androidx.compose.ui.geometry.Offset.Zero, boundedGalleryPan(pan, 1f, size))
    }

    @Test fun cancellingBlockedQueryCancelsProviderBeforeItReturnsAndClosesCursor() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        ShadowContentResolver.registerProviderInternal("media", object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) =
                query(uri, projection, selection, args, sort, null)
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?, signal: android.os.CancellationSignal?): android.database.Cursor {
                signal?.setOnCancelListener { cancelled.countDown() }
                entered.countDown()
                release.await(3, java.util.concurrent.TimeUnit.SECONDS)
                return object : MatrixCursor(projection!!) {
                    override fun close() { super.close(); closed.countDown() }
                }
            }
            override fun getType(uri: Uri) = null
            override fun insert(uri: Uri, values: ContentValues?) = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        })
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            queryLocalGallery(RuntimeEnvironment.getApplication().contentResolver, videos = false)
        }
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            job.cancel()
            assertTrue("provider must see cancellation while query is still blocked", cancelled.await(1, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown()
            job.join()
        }
        assertTrue(closed.await(1, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(job.isCancelled)
    }
    @Test fun queriesRealMediaMetadataAndSortsNewestFirstWithoutReadingFiles() = runBlocking {
        ShadowContentResolver.registerProviderInternal("media", object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) =
                MatrixCursor(projection!!).apply {
                    if (uri.toString().contains("images")) {
                        addRow(arrayOf(1L, 1000L, 1L))
                        addRow(arrayOf(2L, 3000L, 3L))
                    } else addRow(arrayOf(3L, 2000L, 2L))
                }
            override fun getType(uri: Uri) = null
            override fun insert(uri: Uri, values: ContentValues?) = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        })
        val media = queryLocalGallery(RuntimeEnvironment.getApplication().contentResolver)
        assertEquals(listOf(3000L, 2000L, 1000L), media.map { it.timestampMillis })
        assertEquals(listOf(false, true, false), media.map { it.video })
        assertTrue(media.all { it.uri.scheme == "content" })
    }
}
