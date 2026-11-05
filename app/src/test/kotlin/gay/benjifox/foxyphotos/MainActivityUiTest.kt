package gay.benjifox.foxyphotos

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        compose.onNodeWithText("Done").performScrollTo().performClick()
        org.junit.Assert.assertTrue(prefs.getBoolean("onboarding-completed", false))
        compose.onNodeWithContentDescription("More options").performScrollTo().performClick()
        compose.onNodeWithText("Setup").performClick()
        compose.onNodeWithText("Welcome!").assertIsDisplayed()
        assertFalse(BackupRuntime.state.value.active)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
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
