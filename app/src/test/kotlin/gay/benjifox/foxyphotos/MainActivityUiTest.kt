package gay.benjifox.foxyphotos

import android.Manifest
import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
                android.database.MatrixCursor(projection!!).apply { queryCount.incrementAndGet(); addRow(arrayOf("DCIM/Test/")) }
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
