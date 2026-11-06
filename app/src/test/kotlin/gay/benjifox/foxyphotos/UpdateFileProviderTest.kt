package gay.benjifox.foxyphotos

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateFileProviderTest {
    @Test fun updaterProviderOnlySharesDedicatedApkDirectory() {
        val context: Context = RuntimeEnvironment.getApplication()
        val authority = "${context.packageName}.updates.fileprovider"
        val apk = File(context.cacheDir, "updates/apks/update.apk").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val uri = FileProvider.getUriForFile(context, authority, apk)
        assertEquals("content", uri.scheme)
        assertEquals(authority, uri.authority)
        for (path in listOf("updates/release.json", "updates/update.part", "diagnostics/private.txt")) {
            val outside = File(context.cacheDir, path)
            assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, authority, outside) }
        }
        apk.delete()
    }
}
