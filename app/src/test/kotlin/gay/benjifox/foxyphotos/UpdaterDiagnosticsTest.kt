package gay.benjifox.foxyphotos

import android.content.Context
import gay.benjifox.foxyphotos.core.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdaterDiagnosticsTest {
    @Test fun fakeChecksExplainNoUpdateThrottleMissingAssetAndNetworkFailureWithoutSecrets() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val diagnostics = AppDiagnostics.get(context)
        diagnostics.clear()
        val secret = "private-account-token-secret"
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = info.longVersionCode.coerceAtLeast(1)
        val url = "https://github.com/BenjiThatFoxGuy/foxyphotos/releases/download/v1.2/app-release.apk"
        val manifest = JSONObject().put("schemaVersion", 1).put("package", context.packageName)
            .put("versionCode", code).put("versionName", "1.2").put("tag", "v1.2").put("minSdk", 29)
            .put("apk", JSONObject().put("filename", "app-release.apk").put("url", url).put("size", 123)
                .put("sha256", "a".repeat(64)).put("signingCertificateSha256", "b".repeat(64)))
        val manifestUrl = "https://github.com/BenjiThatFoxGuy/foxyphotos/releases/download/v1.2/release-manifest.json"
        val feed = """{"draft":false,"prerelease":false,"immutable":true,"tag_name":"v1.2","body":"$secret","assets":[{"name":"release-manifest.json","browser_download_url":"$manifestUrl"},{"name":"app-release.apk","browser_download_url":"$url","size":123}]}"""
        fun client(reply: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply(chain.request()).toResponseBody("application/json".toMediaType())).build()
        }.build()
        val updater = AppUpdater(context, client { if (it.url.toString() == ReleaseUpdate.FEED_URL) feed else manifest.toString() })
        updater.check(); updater.awaitIdle()
        updater.launchCheck(); diagnostics.flush()
        var exported = diagnostics.exportText()
        for (expected in listOf("Contacting GitHub", "HTTP 200", "Advertised APK asset found", "Comparing installed", "No newer update", "within 24 hours")) assertTrue(expected, exported.contains(expected))
        val missing = AppUpdater(context, client { """{"draft":false,"prerelease":false,"immutable":true,"assets":[],"body":"$secret"}""" })
        missing.check(); missing.awaitIdle()
        val failing = AppUpdater(context, client { throw java.io.IOException("https://secret.invalid/$secret") })
        failing.check(); failing.awaitIdle()
        exported = diagnostics.exportText()
        assertTrue(exported.contains("manifest missing"))
        assertTrue(exported.contains("reason=network"))
        assertTrue("Diagnostics must persist successfully", diagnostics.snapshot().storageHealthy)
        assertFalse(java.io.File(context.noBackupFilesDir, "diagnostics/events-v1").readText().contains(secret))
        assertFalse(exported.contains(secret))
        assertFalse(exported.contains("https://"))
        assertFalse(exported.contains("a".repeat(64)))
        assertFalse(exported.contains("Downloaded digest matches"))
    }
}
