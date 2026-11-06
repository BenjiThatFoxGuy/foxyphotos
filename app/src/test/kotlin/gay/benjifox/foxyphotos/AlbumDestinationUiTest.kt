package gay.benjifox.foxyphotos

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import gay.benjifox.foxyphotos.provider.google.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w393dp-h850dp-mdpi")
class AlbumDestinationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val app get() = RuntimeEnvironment.getApplication()
    private val folder = AlbumLocalFolder("external_primary", "DCIM/Camera/", 3)
    private class Fake : GoogleAlbumAccess {
        var reads = 0
        var writes = 0
        var failNext = false
        override suspend fun albumPage(cursor: String?): GoogleAlbumPage {
            reads++
            if (failNext) { failNext = false; error("fixture") }
            return if (cursor == null) GoogleAlbumPage(listOf(GoogleAlbum("one", "Holiday", 1, null)), "next")
                else GoogleAlbumPage(listOf(GoogleAlbum("two", "Family", 1, null)), null)
        }
        override suspend fun createAlbum(title: String, initialMediaKey: String?): String { writes++; error("No UI mutation") }
        override suspend fun requestMembership(albumId: String, mediaKey: String) { writes++; error("No UI mutation") }
    }
    @Before fun reset() {
        app.getSharedPreferences("foxyphotos.folder-albums", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(BackupService.SETTINGS, Context.MODE_PRIVATE).edit().clear().commit()
        BackupRuntime.mutableState.value = BackupSnapshot()
    }
    @After fun close() { activity.pause().stop().destroy() }
    private fun launch(fake: Fake) {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        activity.get().setContent { MaterialTheme { AlbumBrowser(
            connectionLoader = { AlbumMappingConnection("fixture", fake) },
            folderLoader = { listOf(folder) },
        ) } }
        await("Choose album")
    }
    private fun await(text: String) {
        compose.waitUntil(5000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
    @Test fun chooseSaveChangeAndClearAreNativeLocalOnlyControls() {
        val fake = Fake()
        launch(fake)
        compose.onNodeWithText("Not selected for backup").assertIsDisplayed()
        assertEquals(0, fake.reads)
        compose.onNodeWithText("Choose album").performClick()
        await("Holiday")
        compose.onNodeWithText("Partial listing. More albums available.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Select Holiday").performClick()
        compose.onNodeWithText("Save destination").performClick()
        await("Destination: Holiday")
        assertEquals("one", FolderAlbums(app, "fixture", fake).destination(folder.identity)?.id)
        compose.onNodeWithText("Change album").performClick()
        await("Holiday")
        compose.onNodeWithText("Load more / retry").performClick()
        await("Family")
        compose.onNodeWithContentDescription("Select Family").performClick()
        compose.onNodeWithText("Save destination").performClick()
        await("Destination: Family")
        compose.onNodeWithText("Clear destination").performClick()
        compose.onNodeWithText("Clear").performClick()
        await("Destination: None")
        assertNull(FolderAlbums(app, "fixture", fake).destination(folder.identity))
        assertFalse(FolderAlbums.enabled(app))
        assertTrue(app.getSharedPreferences(BackupService.SETTINGS, Context.MODE_PRIVATE).getStringSet("enabled-folders", emptySet())!!.isEmpty())
        assertEquals(0, fake.writes)
        assertFalse(BackupRuntime.state.value.active)
        assertNull(Shadows.shadowOf(app).nextStartedService)
    }
    @Test fun failedPageRetainsLoadedChoicesAndCanRetry() {
        val fake = Fake()
        launch(fake)
        compose.onNodeWithText("Choose album").performClick()
        await("Holiday")
        fake.failNext = true
        compose.onNodeWithText("Load more / retry").performClick()
        await("Could not load Google albums. Loaded choices are kept; retry.")
        compose.onNodeWithText("Holiday").assertIsDisplayed()
        compose.onNodeWithText("Load more / retry").performClick()
        await("Family")
        compose.onNodeWithText("All available albums loaded.").assertIsDisplayed()
        assertEquals(0, fake.writes)
    }
}
