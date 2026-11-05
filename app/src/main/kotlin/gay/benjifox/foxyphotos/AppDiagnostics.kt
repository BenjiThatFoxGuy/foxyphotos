package gay.benjifox.foxyphotos

import android.content.Context
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import gay.benjifox.foxyphotos.provider.google.UploadDiagnosticEvent
import gay.benjifox.foxyphotos.provider.google.UploadDiagnosticLog
import gay.benjifox.foxyphotos.provider.google.UploadDiagnosticSink
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val DIAGNOSTIC_PRIVACY_NOTICE =
    "logs stay in app-private storage until you export them. they contain timestamps, random upload ids, " +
    "file types and sizes, stages, http status, retry attempts and numeric response structure only. " +
    "no filenames, paths, accounts, urls, hashes, media keys, credentials, response contents or exception text. " +
    "review before sharing. clearing logs does not delete exported files."

/** shared across activity recreation; all storage access is dispatched off the main thread. */
internal class AppDiagnostics private constructor(private val context: Context) : UploadDiagnosticSink {
    private val log by lazy { UploadDiagnosticLog(File(context.noBackupFilesDir, "diagnostics/events-v1")) }

    // the provider invokes this sink on its io dispatcher.
    override fun record(event: UploadDiagnosticEvent) = log.record(event)

    suspend fun snapshot(): DiagnosticSnapshot = withContext(Dispatchers.IO) {
        DiagnosticSnapshot(log.snapshot().map { it.render() }, log.storageHealthy)
    }

    suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        log.clear()
        log.storageHealthy
    }

    suspend fun exportText(): String = withContext(Dispatchers.IO) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val version = info.versionName.orEmpty().replace(Regex("[^a-zA-Z0-9._+()-]"), "_").take(100)
        val buildType = if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) "debug" else "release"
        buildString {
            appendLine("foxyphotos diagnostics v1")
            appendLine("app_version=$version app_build=${info.longVersionCode} build_type=$buildType")
            appendLine(DIAGNOSTIC_PRIVACY_NOTICE)
            appendLine("retention: newest 400 events, at most 256 kib; oldest events may be missing")
            appendLine("wire format: field:wire:payload length; brackets follow known commit envelope 1 -> 3 only")
            appendLine("wire values and opaque bytes are never recorded; ! malformed/unsupported, ? limit reached")
            appendLine("auth http status is unavailable; attempt numbers restart for each stage")
            appendLine("storage_healthy=${log.storageHealthy}")
            appendLine()
            log.snapshot().forEach { appendLine(it.render()) }
        }
    }

    companion object {
        // only applicationContext reaches the private constructor, never an activity.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: AppDiagnostics? = null
        fun get(context: Context): AppDiagnostics = instance ?: synchronized(this) {
            instance ?: AppDiagnostics(context.applicationContext).also { instance = it }
        }
    }
}

internal data class DiagnosticSnapshot(val lines: List<String> = emptyList(), val storageHealthy: Boolean = true)
