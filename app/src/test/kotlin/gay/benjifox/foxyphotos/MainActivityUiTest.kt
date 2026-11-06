package gay.benjifox.foxyphotos

import android.Manifest
import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35], qualifiers = "w393dp-h700dp-mdpi")
class MainActivityUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var controller: ActivityController<MainActivity>? = null

    @Before fun freshInstall() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("foxyphotos.cleanup", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("foxyphotos.credentials", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("foxyphotos.backup-settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        shadowOf(app).denyPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        BackupRuntime.mutableState.value = BackupSnapshot()
    }

    @After fun closeActivity() {
        controller?.pause()?.stop()?.destroy()
    }

    private fun launch(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        compose.waitForIdle()
        return controller!!.get()
    }

    private fun clickSettingsEntry(label: String) {
        // LazyColumn may not compose lower rows until scrolled (for example below App updates).
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).assertIsDisplayed().assertHasClickAction().performClick()
    }

    private fun assertSelectedPhotoAccess() {
        // Prove we reached/stayed on folders before checking the absent permission action.
        compose.onNodeWithText("Backup folders").assertIsDisplayed()
        compose.onNodeWithText("Clear selection").assertIsDisplayed()
        compose.onNodeWithText("Allow photos access").assertDoesNotExist()
    }

    @Test fun folderAlbumsDefaultOffDoNotAdmitBackupOrStartRemoteReadsOnToggle() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("foxyphotos.backup-settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("onboarding-completed", true).commit()
        launch()
        assertFalse(FolderAlbums.enabled(app))
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Experimental folder → Google albums"))
        compose.onNodeWithContentDescription("Enable experimental folder albums").performClick()
        org.junit.Assert.assertTrue(FolderAlbums.enabled(app))
        assertFalse(BackupRuntime.state.value.active)
        assertNull(shadowOf(app).nextStartedService)
        clickSettingsEntry("Folder album destinations")
        compose.onNodeWithText("Folder album destinations").assertIsDisplayed()
        compose.onNodeWithText("Saving a destination does not select a backup folder or start backup. Manage inclusion in Backup folders.").assertIsDisplayed()
        assertFalse(BackupRuntime.state.value.active)
        assertNull(shadowOf(app).nextStartedService)
        org.junit.Assert.assertTrue(prefs.getStringSet("enabled-folders", emptySet())!!.isEmpty())
    }

    @Test fun cleanupStartsOffWithIndependentEmptyWhitelistAndCannotRun() {
        val app = RuntimeEnvironment.getApplication()
        val backup = app.getSharedPreferences("foxyphotos.backup-settings", Context.MODE_PRIVATE)
        backup.edit().putBoolean("onboarding-completed", true).putStringSet("enabled-folders", setOf("DCIM/")).commit()
        launch()
        compose.onNodeWithContentDescription("Settings").performClick()
        clickSettingsEntry("Experimental verified local cleanup")
        compose.onNodeWithText("Verified local cleanup").assertIsDisplayed()
        compose.onNodeWithText("Verify earlier uploads").performScrollTo().assertIsNotEnabled()
        val cleanup = CleanupStorage(app)
        assertFalse(cleanup.enabled())
        org.junit.Assert.assertTrue(cleanup.folders().isEmpty())
        compose.onNodeWithContentDescription("Enable experimental cleanup").performScrollTo().performClick()
        org.junit.Assert.assertTrue(cleanup.enabled())
        compose.onNodeWithText("Verify earlier uploads").performScrollTo().assertIsNotEnabled()
        org.junit.Assert.assertEquals(setOf("DCIM/"), backup.getStringSet("enabled-folders", emptySet()))
        assertFalse(BackupRuntime.state.value.active)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun prototypeFlagsDoNotOptIntoCloudBrowsingOnUpgrade() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("foxyphotos.backup-settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("onboarding-completed", true)
            .putBoolean("experimental-google-library", true)
            .putBoolean("experimental-icloud-library", true).commit()
        launch()
        compose.onNodeWithText("Photo access is off").assertIsDisplayed()
        compose.onNodeWithText("Combined").assertDoesNotExist()
        compose.onNodeWithText("Refresh Google").assertDoesNotExist()
        compose.onNodeWithText("Refresh iCloud").assertDoesNotExist()
        compose.onNodeWithContentDescription("Library mode").performClick()
        compose.onNodeWithText("✓ Local only").assertIsDisplayed()
        assertFalse(BackupRuntime.state.value.active)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun icloudOnlyRoutesMainWithoutGoogleOrLocalAndPersistsAcrossRecreation() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("foxyphotos.backup-settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("onboarding-completed", true).commit()
        val activity = launch()
        compose.onNodeWithContentDescription("Library mode").performClick()
        compose.onNodeWithText("iCloud only").performClick()
        org.junit.Assert.assertEquals("icloud", prefs.getString("main-library-mode", null))
        compose.onNodeWithText("Refresh iCloud").assertIsDisplayed()
        compose.onNodeWithText("Photo access is off").assertDoesNotExist()
        compose.onNodeWithText("Photo access").assertDoesNotExist()
        compose.onNodeWithText("Refresh Google").assertDoesNotExist()
        compose.onNodeWithText("Retry Google page").assertDoesNotExist()
        assertNull(shadowOf(activity).lastRequestedPermission)
        controller!!.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Refresh iCloud").assertIsDisplayed()
        compose.onNodeWithText("Refresh Google").assertDoesNotExist()
        compose.onNodeWithContentDescription("Library mode").performClick()
        compose.onNodeWithText("Local only").performClick()
        compose.onNodeWithText("Photo access is off").assertIsDisplayed()
        compose.onNodeWithText("Refresh iCloud").assertDoesNotExist()
        assertFalse(BackupRuntime.state.value.active)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun googleOnlyMainRoutingHasHonestAuthErrorAndNoIcloudOrLocalOverlay() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("foxyphotos.backup-settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("onboarding-completed", true).commit()
        launch()
        compose.onNodeWithContentDescription("Library mode").performClick()
        compose.onNodeWithText("Google Photos").performClick()
        org.junit.Assert.assertEquals("google", prefs.getString("main-library-mode", null))
        compose.waitUntil(5000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Google access unavailable. Connect or reconnect Google Photos in Settings, then retry.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Retry Google page").assertIsDisplayed()
        compose.onNodeWithText("Google listing ended for this session.").assertDoesNotExist()
        compose.onNodeWithText("Refresh iCloud").assertDoesNotExist()
        compose.onNodeWithText("Photo access is off").assertDoesNotExist()
        compose.onNodeWithContentDescription("Library mode").performClick()
        compose.onNodeWithText("Google Photos + iCloud").performClick()
        compose.onNodeWithText("Refresh Google").assertIsDisplayed()
        compose.onNodeWithText("Refresh iCloud").assertIsDisplayed()
        compose.onNodeWithContentDescription("Library mode").performClick()
        compose.onNodeWithText("Local only").performClick()
        compose.onNodeWithText("Retry Google page").assertDoesNotExist()
        compose.onNodeWithText("Refresh iCloud").assertDoesNotExist()
        compose.onNodeWithText("Photo access is off").assertIsDisplayed()
        assertFalse(BackupRuntime.state.value.active)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun persistedIcloudOnlyNeverQueriesGrantedDeviceMedia() {
        val app = RuntimeEnvironment.getApplication()
        val queries = java.util.concurrent.atomic.AtomicInteger()
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): android.database.Cursor {
                queries.incrementAndGet()
                return android.database.MatrixCursor(projection ?: emptyArray())
            }
            override fun getType(uri: android.net.Uri) = null
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = null
            override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 0
        })
        app.getSharedPreferences("foxyphotos.backup-settings", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding-completed", true).putString("main-library-mode", "icloud")
            .putStringSet("enabled-folders", setOf("DCIM/")).commit()
        // Deliberately undecryptable fixture: presence may enable existing backup controls,
        // but iCloud-only startup must never decrypt Google credentials or use their pages.
        app.getSharedPreferences("foxyphotos.credentials", Context.MODE_PRIVATE).edit()
            .putString("credential.google-native.ciphertext", "not-a-real-credential")
            .putString("credential.google-native.iv", "not-an-iv").commit()
        val activity = launch()
        compose.onNodeWithText("Back up").assertIsEnabled()
        compose.onNodeWithText("Refresh iCloud").assertIsDisplayed()
        controller!!.pause().stop().start().resume()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(0, queries.get())
        assertNull(shadowOf(activity).lastRequestedPermission)
        compose.onNodeWithText("Refresh Google").assertDoesNotExist()
        compose.onNodeWithText("Photo access").assertDoesNotExist()
    }

    @Test @Config(sdk = [35]) fun selectedOnlyAccessSurvivesResumeAndRecreation() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        app.getSharedPreferences("foxyphotos.backup-settings", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding-completed", true).commit()
        launch()
        compose.onNodeWithContentDescription("Settings").performClick()
        clickSettingsEntry("Backup folders")
        assertSelectedPhotoAccess()
        controller!!.pause().stop().start().resume()
        compose.waitForIdle()
        assertSelectedPhotoAccess()
        controller!!.recreate()
        compose.waitForIdle()
        assertSelectedPhotoAccess()
    }

    @Test fun notificationsAreRequestedOnlyFromTheirSetupStep() {
        val activity = launch()
        compose.onNodeWithText("Welcome!").assertIsDisplayed()
        assertNull(shadowOf(activity).lastRequestedPermission)
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.onNodeWithText("Not now").performScrollTo().performClick()
        compose.onNodeWithText("Not now").performScrollTo().performClick()
        assertNull(shadowOf(activity).lastRequestedPermission)
        compose.onNodeWithText("Allow notifications").performScrollTo().performClick()
        assertArrayEquals(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            shadowOf(activity).lastRequestedPermission?.requestedPermissions,
        )
        assertFalse(BackupRuntime.state.value.active)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test fun onlyDoneCompletesSetupAndSetupMenuCanReopenIt() {
        launch()
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("foxyphotos.backup-settings", Context.MODE_PRIVATE)
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        repeat(3) { compose.onNodeWithText("Not now").performScrollTo().performClick() }
        assertFalse(prefs.getBoolean("onboarding-completed", false))
        compose.onNodeWithText("Simple").performScrollTo().performClick()
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.onNodeWithText("Done").performScrollTo().performClick()
        org.junit.Assert.assertTrue(prefs.getBoolean("onboarding-completed", false))
        org.junit.Assert.assertEquals("Simple", prefs.getString("app-mode", null))
        compose.onNodeWithContentDescription("Settings").performClick()
        clickSettingsEntry("Setup")
        compose.onNodeWithText("Welcome!").assertIsDisplayed()
        assertFalse(BackupRuntime.state.value.active)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test fun settingsSwitchPersistsModeWithoutStartingBackup() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("foxyphotos.backup-settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("onboarding-completed", true).commit()
        launch()
        compose.onNodeWithText("Simple").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Modern").performClick()
        org.junit.Assert.assertEquals("Modern", prefs.getString("app-mode", null))
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Photo access is off").assertIsDisplayed()
        controller!!.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Photo access is off").assertIsDisplayed()
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertFalse(BackupRuntime.state.value.active)
    }

    @Test fun accountDetailsAreHiddenUntilProfileTapped() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("foxyphotos.backup-settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("onboarding-completed", true).commit()
        val activity = launch()
        activity.setContent { ProfileControl("private@example.invalid", onSettings = {}, backupControls = { androidx.compose.material3.Text("Backup controls") }) }
        compose.onNodeWithText("private@example.invalid").assertDoesNotExist()
        compose.onNodeWithContentDescription("Account").performClick()
        compose.onNodeWithText("private@example.invalid").assertIsDisplayed()
    }

    @Test fun modernMovesControlsAndSettingsIntoProfileWithoutStartingBackup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("foxyphotos.backup-settings", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding-completed", true).putString("app-mode", "Modern").commit()
        launch()
        compose.onNodeWithText("Photo access is off").assertIsDisplayed()
        compose.onNodeWithText("Pause for…").assertDoesNotExist()
        compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
        compose.onNodeWithText("Select").assertDoesNotExist()
        compose.onNodeWithText("FP").assertIsDisplayed()
        compose.onNodeWithContentDescription("Account").performClick()
        compose.onNodeWithText("Pause for…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Upload immediately…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performScrollTo().performClick()
        compose.onNodeWithText("App mode").assertIsDisplayed()
        assertFalse(BackupRuntime.state.value.active)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun folderScreenQueriesAgainOnEveryEntryWithoutStartingBackup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("foxyphotos.backup-settings", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding-completed", true).commit()
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        val queryCount = java.util.concurrent.atomic.AtomicInteger()
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) =
                android.database.MatrixCursor(projection!!).apply {
                    queryCount.incrementAndGet()
                    addRow(projection.map { column ->
                        when (column) {
                            android.provider.MediaStore.MediaColumns.RELATIVE_PATH -> "DCIM/Test/"
                            android.provider.MediaStore.MediaColumns.VOLUME_NAME -> "external_primary"
                            android.provider.MediaStore.MediaColumns._ID -> 1L
                            android.provider.MediaStore.MediaColumns.DATE_TAKEN,
                            android.provider.MediaStore.MediaColumns.DATE_ADDED -> 0L
                            else -> null
                        }
                    }.toTypedArray())
                }
            override fun getType(uri: android.net.Uri) = null
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = null
            override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 0
        })
        launch()
        compose.onNodeWithContentDescription("Settings").performClick()
        clickSettingsEntry("Backup folders")
        compose.waitUntil(5000) {
            compose.waitForIdle()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            queryCount.get() >= 2 &&
                compose.onAllNodesWithText("DCIM/Test/").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("DCIM/Test/").assertIsDisplayed()
        val first = queryCount.get()
        compose.onNodeWithText("Back").performClick()
        clickSettingsEntry("Backup folders")
        compose.waitUntil(5000) {
            compose.waitForIdle()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            queryCount.get() >= first + 2 &&
                compose.onAllNodesWithText("DCIM/Test/").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("DCIM/Test/").assertIsDisplayed()
        compose.onNodeWithText("Refresh folders").assertDoesNotExist()
        assertFalse(BackupRuntime.state.value.active)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun longCredentialLeavesSaveReachableAndClickable() {
        launch()
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        val invalidCredential = "invalid dummy credential\n".repeat(500)
        compose.onNode(hasSetTextAction() and hasText("Google Photos credential"))
            .performScrollTo()
            .performTextReplacement(invalidCredential)
        compose.onNodeWithText("Save")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
            .performClick()
        // Parsing fails locally, proving the click ran without any Google authentication.
        compose.waitForIdle()
        compose.onNodeWithText("Google credential contains an invalid field.")
            .performScrollTo()
            .assertIsDisplayed()
        assertFalse(BackupRuntime.state.value.active)
    }
}
