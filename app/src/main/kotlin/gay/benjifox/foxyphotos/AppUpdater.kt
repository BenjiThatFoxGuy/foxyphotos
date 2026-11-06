package gay.benjifox.foxyphotos

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import gay.benjifox.foxyphotos.core.model.*
import gay.benjifox.foxyphotos.core.model.DiagnosticStep.*
import gay.benjifox.foxyphotos.core.model.ReleaseUpdate
import gay.benjifox.foxyphotos.core.model.ReleaseVerification
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal data class UpdateState(val text: String = "Not checked", val release: ReleaseUpdate? = null,
    val busy: Boolean = false, val progress: Float? = null, val ready: Boolean = false)

/** App-scoped work: navigation never cancels a download, explicit Cancel does. No account HTTP client. */
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
internal class AppUpdater internal constructor(private val context: Context, httpClient: OkHttpClient? = null) {
    private val prefs = context.getSharedPreferences("foxyphotos.updates", Context.MODE_PRIVATE)
    private val directory = File(context.cacheDir, "updates").apply { mkdirs() }
    private val apkDirectory = File(directory, "apks").apply { mkdirs() }
    private val apk = File(apkDirectory, "update.apk")
    private val metadata = File(directory, "release.json")
    private val partial = File(directory, "update.part")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    val automatic get() = prefs.getBoolean("automatic", true)
    private val client = httpClient ?: OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES).build()

    private var failureReason = DiagnosticReason.OTHER
    private var lastAutomaticDecision: DiagnosticStep? = null
    private fun event(step: DiagnosticStep, r: ReleaseUpdate? = null, status: Int = 0, bytes: Long = 0, reason: DiagnosticReason = DiagnosticReason.NONE) {
        val current = runCatching { installed() }.getOrNull()
        AppDiagnostics.get(context).safelyRecord(DiagnosticEvent(DiagnosticComponent.UPDATER, step, reason,
            status = status, bytes = bytes, installedCode = current?.longVersionCode ?: 0,
            availableCode = r?.versionCode ?: 0, installedName = DiagnosticVersion.from(current?.versionName),
            availableName = DiagnosticVersion.from(r?.versionName)))
    }
    private fun gate(reason: DiagnosticReason, step: DiagnosticStep, r: ReleaseUpdate, test: () -> Unit) {
        failureReason = reason
        test()
        event(step, r)
    }
    init {
        partial.delete()
        if (apk.exists() != metadata.exists()) clearReady()
        if (apk.exists() && System.currentTimeMillis() - apk.lastModified() > TimeUnit.DAYS.toMillis(2)) clearReady()
        if (apk.exists() && metadata.exists()) {
            job = scope.launch {
                runCatching { val r = parse(metadata.readText()); verify(r); event(RESTORED, r); mutable.value = UpdateState("Ready to install", r, ready = true) }
                    .onFailure { if (it is CancellationException) throw it; event(FAILED, reason = failureReason); clearReady() }
                if (prefs.getBoolean("pending-install", false)) { job = null; onResume() }
            }
        }
    }
    internal suspend fun awaitIdle() { job?.join(); AppDiagnostics.get(context).flush() }
    fun setAutomatic(value: Boolean) { prefs.edit().putBoolean("automatic", value).apply() }
    fun launchCheck() {
        val elapsed = System.currentTimeMillis() - prefs.getLong("last-check", 0)
        val decision = when {
            apk.exists() -> READY_ARTIFACT
            !automatic -> DISABLED
            elapsed >= 0 && elapsed <= TimeUnit.HOURS.toMillis(24) -> THROTTLED
            else -> AUTO_CHECK
        }
        if (decision != lastAutomaticDecision) { event(AUTO_CHECK); if (decision != AUTO_CHECK) event(decision) }
        lastAutomaticDecision = decision
        if (decision == AUTO_CHECK) checkInternal()
    }
    private fun installed() = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    private fun eligible(r: ReleaseUpdate) = r.available(context.packageName, installed().longVersionCode, Build.VERSION.SDK_INT)
    private suspend fun <T> response(url: String, block: (okhttp3.Response) -> T): T {
        var next = url
        repeat(6) {
            require(ReleaseUpdate.allowedTransport(next))
            currentCoroutineContext().ensureActive()
            val call = client.newCall(Request.Builder().url(next).header("Accept", "application/vnd.github+json").build())
            val cancellation = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) call.cancel() }
            try {
                failureReason = DiagnosticReason.NETWORK
                call.execute().use { res ->
                    event(HTTP_RESPONSE, status = res.code)
                    if (res.code in listOf(301, 302, 303, 307, 308)) {
                        next = res.request.url.resolve(res.header("Location") ?: throw IOException())?.toString() ?: throw IOException()
                    } else {
                        if (!res.isSuccessful) throw UpdateHttpException(res.code)
                        return block(res)
                    }
                }
            } finally { cancellation.dispose() }
        }
        throw IOException()
    }
    private suspend fun text(url: String): String = response(url) { res ->
        val body = res.body ?: throw IOException()
        body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count == -1) break
                require(output.size() + count <= 256 * 1024)
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
    }
    fun check() { event(MANUAL_CHECK); checkInternal() }
    private fun checkInternal() = start("Checking…") {
        event(CONTACT_GITHUB)
        prefs.edit().putLong("last-check", System.currentTimeMillis()).apply()
        val feed = text(ReleaseUpdate.FEED_URL)
        failureReason = DiagnosticReason.METADATA
        val release = JSONObject(feed)
        require(!release.getBoolean("draft") && !release.getBoolean("prerelease") && release.getBoolean("immutable"))
        event(RELEASE_FOUND)
        event(LOCATE_MANIFEST)
        val assets = release.getJSONArray("assets")
        val manifests = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name") == "release-manifest.json" }
        if (manifests.size != 1) {
            event(MANIFEST_MISSING, reason = DiagnosticReason.METADATA)
            mutable.value = UpdateState("No compatible update published")
            return@start
        }
        val raw = text(manifests.single().getString("browser_download_url"))
        failureReason = DiagnosticReason.METADATA
        val r = parse(raw)
        require(r.tag == release.getString("tag_name"))
        event(MANIFEST_PARSED, r)
        event(LOCATE_APK, r)
        val apkAssets = (0 until assets.length()).map { assets.getJSONObject(it) }.filter {
            it.optString("name") == r.filename && it.optString("browser_download_url") == r.url && it.optLong("size") == r.size
        }
        if (apkAssets.size != 1) {
            event(APK_MISSING, r, reason = DiagnosticReason.METADATA)
            mutable.value = UpdateState("No compatible update published")
            return@start
        }
        event(APK_FOUND, r, bytes = r.size)
        event(COMPARE_VERSION, r)
        event(when { eligible(r) -> UPDATE_AVAILABLE; r.packageName != context.packageName || r.minSdk > Build.VERSION.SDK_INT -> INCOMPATIBLE; else -> NO_UPDATE }, r)
        mutable.value = when {
            eligible(r) -> UpdateState("Update ${r.versionName} available", r)
            r.packageName != context.packageName || r.minSdk > Build.VERSION.SDK_INT -> UpdateState("No compatible update published")
            else -> UpdateState("Up to date")
        }
    }
    fun download() {
        val r = mutable.value.release ?: return
        start("Downloading…", r) {
            require(eligible(r)); clearReady()
            event(DOWNLOAD_START, r)
            var progressBucket = 0
            try {
                response(r.url) { res ->
                    val body = res.body ?: throw IOException()
                    failureReason = DiagnosticReason.SIZE
                    require(body.contentLength() == -1L || body.contentLength() == r.size)
                    body.byteStream().use { input -> partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024); var count = 0L
                        while (true) {
                            if (job?.isActive != true) throw CancellationException()
                            failureReason = DiagnosticReason.NETWORK
                            val n = input.read(buffer); if (n == -1) break
                            failureReason = DiagnosticReason.SIZE
                            count += n; require(count <= r.size)
                            output.write(buffer, 0, n)
                            val bucket = (count * 4 / r.size).toInt()
                            if (bucket > progressBucket) { progressBucket = bucket; event(DOWNLOAD_PROGRESS, r, bytes = count) }
                            mutable.value = UpdateState("Downloading…", r, true, count.toFloat() / r.size)
                        }
                        failureReason = DiagnosticReason.SIZE
                        require(count == r.size)
                    } }
                }
                event(DOWNLOAD_COMPLETE, r, bytes = partial.length())
                failureReason = DiagnosticReason.STORAGE
                require(partial.renameTo(apk))
                event(APK_SAVED, r, bytes = apk.length())
                AppDiagnostics.get(context).flush()
                verify(r)
                metadata.writeText(encode(r).toString())
                mutable.value = UpdateState("Ready to install", r, ready = true)
            } finally { partial.delete(); if (!metadata.exists()) apk.delete() }
        }
    }
    fun cancel() {
        event(CANCEL_REQUEST, mutable.value.release)
        prefs.edit().putBoolean("pending-install", false).apply()
        job?.cancel(); mutable.value = UpdateState("Cancelled", mutable.value.release)
    }
    private fun start(label: String, release: ReleaseUpdate? = null, action: suspend () -> Unit) {
        if (job?.isActive == true) { event(BUSY); return }
        mutable.value = UpdateState(label, release, busy = true)
        job = scope.launch(start = CoroutineStart.LAZY) {
            try { action() }
            catch (e: CancellationException) { event(CANCELLED, release); throw e }
            catch (e: UpdateHttpException) {
                currentCoroutineContext().ensureActive()
                event(FAILED, release, status = e.status, reason = DiagnosticReason.HTTP)
                mutable.value = UpdateState(if (e.status == 403 || e.status == 429) "Rate limited. Try later." else "Release check failed (${e.status})", release)
            }
            catch (_: Exception) {
                if (!currentCoroutineContext().isActive) event(CANCELLED, release)
                currentCoroutineContext().ensureActive()
                event(if (failureReason == DiagnosticReason.METADATA) MANIFEST_REJECTED else FAILED, release, reason = failureReason)
                mutable.value = UpdateState("Update failed. Check connection or retry.", release)
            }
        }
        job?.start()
    }
    private fun clearReady() { apk.delete(); metadata.delete() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun verify(r: ReleaseUpdate) {
        failureReason = DiagnosticReason.VERSION
        require(eligible(r))
        gate(DiagnosticReason.SIZE, SIZE_CHECK, r) { require(apk.length() == r.size) }
        failureReason = DiagnosticReason.HASH
        apk.inputStream().use { input -> ReleaseVerification.verifyBytes(r, input) { if (job?.isCancelled == true) throw CancellationException() } }
        event(HASH_CHECK, r)
        failureReason = DiagnosticReason.PACKAGE
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Invalid APK")
        gate(DiagnosticReason.PACKAGE, PACKAGE_CHECK, r) { require(archive.packageName == r.packageName) }
        gate(DiagnosticReason.VERSION, VERSION_CHECK, r) { require(archive.longVersionCode == r.versionCode && archive.versionName == r.versionName) }
        gate(DiagnosticReason.SDK, SDK_CHECK, r) { require(archive.applicationInfo?.minSdkVersion == r.minSdk && r.minSdk <= Build.VERSION.SDK_INT) }
        failureReason = DiagnosticReason.SIGNATURE
        val signing = archive.signingInfo ?: error("Missing signature")
        require(!signing.hasMultipleSigners())
        val installedSigning = installed().signingInfo ?: error("Missing installed signature")
        require(!installedSigning.hasMultipleSigners())
        val history = (signing.signingCertificateHistory ?: error("Missing signing history")).map { hash(it.toByteArray()) }.toSet()
        ReleaseVerification.verifyIdentity(r, archive.packageName, archive.longVersionCode, archive.versionName,
            archive.applicationInfo?.minSdkVersion ?: error("Missing minimum SDK"),
            hash(signing.apkContentsSigners.single().toByteArray()), history,
            hash(installedSigning.apkContentsSigners.single().toByteArray()))
        event(SIGNATURE_CHECK, r)
    }
    fun install() {
        val r = mutable.value.release ?: return
        start("Verifying…", r) {
            verify(r)
            withContext(Dispatchers.Main) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    failureReason = DiagnosticReason.AUTHORIZATION
                    event(AUTH_REQUIRED, r)
                    prefs.edit().putBoolean("pending-install", true).commit()
                    AppDiagnostics.get(context).flush()
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    mutable.value = UpdateState("Allow installation, then return", r, ready = true)
                } else {
                    event(AUTH_GRANTED, r)
                    prefs.edit().putBoolean("pending-install", false).commit()
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates.fileprovider", apk)
                    failureReason = DiagnosticReason.INSTALLER
                    event(INSTALLER_CALL, r)
                    AppDiagnostics.get(context).flush()
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    event(INSTALLER_LAUNCHED, r)
                    AppDiagnostics.get(context).flush()
                    mutable.value = UpdateState("Ready to install", r, ready = true)
                }
            }
        }
    }
    fun onResume() {
        if (prefs.getBoolean("pending-install", false) && mutable.value.ready) {
            if (context.packageManager.canRequestPackageInstalls()) install()
            else { event(AUTH_DENIED, mutable.value.release); prefs.edit().putBoolean("pending-install", false).apply() }
        }
    }
    private fun parse(raw: String): ReleaseUpdate {
        val j = JSONObject(raw); require(j.getInt("schemaVersion") == 1)
        val a = j.getJSONObject("apk")
        return ReleaseUpdate(j.getString("package"), j.getString("versionName"), j.getLong("versionCode"), j.getInt("minSdk"), j.getString("tag"),
            a.getString("filename"), a.getString("url"), a.getLong("size"), a.getString("sha256"), a.getString("signingCertificateSha256")).also { it.validate() }
    }
    private fun encode(r: ReleaseUpdate) = JSONObject().put("schemaVersion", 1).put("package", r.packageName).put("versionName", r.versionName)
        .put("versionCode", r.versionCode).put("minSdk", r.minSdk).put("tag", r.tag).put("apk", JSONObject().put("filename", r.filename)
            .put("url", r.url).put("size", r.size).put("sha256", r.sha256).put("signingCertificateSha256", r.signingCertificateSha256))
    private class UpdateHttpException(val status: Int) : IOException()
    companion object {
        @Volatile private var instance: AppUpdater? = null
        fun get(context: Context): AppUpdater = instance ?: synchronized(this) { instance ?: AppUpdater(context.applicationContext).also { instance = it } }
    }
}
