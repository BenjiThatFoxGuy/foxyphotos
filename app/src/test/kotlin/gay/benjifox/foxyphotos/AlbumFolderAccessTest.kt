package gay.benjifox.foxyphotos

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlbumFolderAccessTest {
    @Test fun accessibleRowsKeepActualVolumeAndFullPathAndResolveAggregateRequest() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        ShadowContentResolver.registerProviderInternal("media", object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) = MatrixCursor(projection!!).apply {
                if (projection.size == 1) addRow(arrayOf("card"))
                else if (uri.pathSegments.contains("images")) {
                    addRow(arrayOf("external_primary", "DCIM/Camera/"))
                    addRow(arrayOf("card", "DCIM/Camera/"))
                    addRow(arrayOf("card", "Pictures/Camera/"))
                    addRow(arrayOf("external", "DCIM/Unknown/"))
                } else addRow(arrayOf("card", "DCIM/Camera/"))
            }
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        })
        val folders = accessibleAlbumFolders(context).associateBy { it.identity }
        assertEquals(setOf("external_primary:DCIM/Camera/", "card:DCIM/Camera/", "card:Pictures/Camera/"), folders.keys)
        assertEquals(2, folders["card:DCIM/Camera/"]?.count)
        assertEquals("card:DCIM/Camera/", actualAlbumFolder(context, Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "1"), "DCIM/Camera/"))
    }
}
