package gay.benjifox.foxyphotos

import android.content.Context
import gay.benjifox.foxyphotos.core.database.SharedPreferencesUploadedContentLedger
import gay.benjifox.foxyphotos.core.media.MediaStoreFolderScanner
import gay.benjifox.foxyphotos.core.security.AndroidKeystoreCredentialStore
import gay.benjifox.foxyphotos.provider.contract.CredentialReference
import gay.benjifox.foxyphotos.provider.google.*
import kotlinx.coroutines.*

/** Android storage, credentials and Google adapter for the portable backup engine. */
internal class AndroidBackupWork(private val context: Context) {
    suspend fun run(
        progress: (BackupSnapshot) -> Unit,
        shouldPause: () -> Boolean,
    ): BackupRunResult {
        val policy = AndroidBackupPolicy(context)
        val credentialStore = AndroidKeystoreCredentialStore(context.applicationContext)
        val ledger = SharedPreferencesUploadedContentLedger(context.applicationContext)
        val scanner = MediaStoreFolderScanner(context.contentResolver)
        val client = GoogleNativeUploadClient(AppDiagnostics.get(context))
        val selected = context.getSharedPreferences(BackupService.SETTINGS, Context.MODE_PRIVATE).getStringSet("enabled-folders", emptySet())?.toSet() ?: emptySet()
        val secret = credentialStore.loadCredential(CredentialReference("google-native"))
            ?: throw IllegalStateException("Missing credential")
        try {
            val credential = GoogleCredentialParser.parse(secret.decodeToString())
            val requested = policy.request()
            val media = if (requested != null) scanner.scanSelectedItems(requested) else {
                val snapshot = scanner.scanSelectedFolders(selected)
                policy.prefs.edit().putStringSet("request-items", snapshot.map { it.uri.toString() }.toSet())
                    .putBoolean("request-pending", true).commit()
                snapshot
            }
            var currentItem: String? = null
            var counts = BackupCounts()
            fun report(phase: BackupPhase) = progress(BackupSnapshot(phase = phase, counts = counts, total = media.size))
            val result = runBackup(
                items = media,
                hash = { item -> currentItem = item.uri.toString(); report(BackupPhase.HASHING); scanner.hash(item) },
                isCommitted = { ledger.contains(it.sha256) },
                upload = { item, hashed ->
                    report(BackupPhase.UPLOADING)
                    val fileType = withContext(Dispatchers.IO) {
                        try { UploadFileType.fromMimeType(context.contentResolver.getType(item.uri)) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { UploadFileType.OTHER }
                    }
                    when (val outcome = client.upload(GoogleUploadRequest(
                        credential = credential,
                        fileName = item.displayName,
                        sizeBytes = item.sizeBytes,
                        modifiedEpochSeconds = item.modifiedEpochSeconds,
                        sha1 = hashed.sha1,
                        fileType = fileType,
                        beforeBytes = { !shouldPause() && policy.reasons(item.uri.toString()).isEmpty() },
                        openSource = { context.contentResolver.openInputStream(item.uri) ?: throw IllegalStateException("Media unavailable") },
                    ))) {
                        is GoogleUploadOutcome.Committed -> BackupUploadResult(outcome.mediaKey)
                        is GoogleUploadOutcome.Present -> BackupUploadResult(outcome.mediaKey, alreadyPresent = true)
                    }
                },
                recordCommitted = { hashed, key -> ledger.recordCommitted(hashed.sha256, key) },
                shouldPause = { shouldPause() || policy.reasons(currentItem).isNotEmpty() },
                onProgress = { counts = it; report(BackupPhase.HASHING) },
            )
            if (policy.prefs.getBoolean("override-batch", false) && (result.counts.failed > 0 || result.counts.uncertain > 0)) policy.endOverride()
            if (!result.paused) policy.finish()
            return result.copy(waiting = result.paused && !policy.paused())
        } finally { secret.fill(0) }
    }

}
