package gay.benjifox.foxyphotos

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ManualCopyControlsTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun defaultOffAndMissingSourceCannotStartCopy() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("foxyphotos.manual-copy", Context.MODE_PRIVATE).edit().clear().commit()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent { MaterialTheme { ManualCopyControls(null, null, emptyList()) } }
            compose.onNodeWithText("Copy / Move from this iCloud album").assertDoesNotExist()
            compose.onNode(isToggleable()).assertIsOff().performClick()
            compose.onNodeWithText("Copy / Move from this iCloud album").assertIsNotEnabled()
            compose.onNodeWithText("Choose an iCloud album first. Only explicitly selected, loaded items are copied.").assertExists()
        } finally { controller.pause().stop().destroy() }
    }
}
