package gay.benjifox.foxyphotos

import gay.benjifox.foxyphotos.core.model.*
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
        val diagnostics = AppDiagnostics.get(context)
        diagnostics.safelyRecord(DiagnosticEvent(DiagnosticComponent.BACKUP, DiagnosticStep.SCAN))
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
            var unavailable = 0
            val media = if (requested != null) scanner.scanSelectedItems(requested, policy.allowedFolders()) {
                unavailable++; policy.completeItem(it, failed = true)
            } else {
                val snapshot = scanner.scanSelectedFolders(selected)
                policy.storeRequest(snapshot.map { it.uri.toString() }.toSet())
                snapshot
            }
            diagnostics.safelyRecord(DiagnosticEvent(DiagnosticComponent.BACKUP, DiagnosticStep.PLAN, count = media.size, failed = unavailable))
            // Removed folders never remain eligible on a later resume.
            policy.storeRequest(media.map { it.uri.toString() }.toSet(), explicit = policy.allowedFolders() == null)
            var currentItem: String? = null
            var currentFolder: String? = null
            var counts = BackupCounts(failed = unavailable)
            fun report(phase: BackupPhase) = progress(BackupSnapshot(phase = phase, counts = counts, total = media.size))
            val result = runBackup(
                items = media,
                diagnostics = diagnostics,
                hash = { item -> currentItem = item.uri.toString(); currentFolder = item.relativePath; report(BackupPhase.HASHING); scanner.hash(item) },
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
                        beforeBytes = { !shouldPause() && policy.allowsFolder(item.relativePath) && policy.reasons(item.uri.toString()).isEmpty() },
                        openSource = { context.contentResolver.openInputStream(item.uri) ?: throw IllegalStateException("Media unavailable") },
                    ))) {
                        is GoogleUploadOutcome.Committed -> BackupUploadResult(outcome.mediaKey)
                        is GoogleUploadOutcome.Present -> BackupUploadResult(outcome.mediaKey, alreadyPresent = true)
                    }
                },
                recordCommitted = { hashed, key -> ledger.recordCommitted(hashed.sha256, key) },
                shouldPause = { shouldPause() || (currentFolder?.let { !policy.allowsFolder(it) } == true) || policy.reasons(currentItem).isNotEmpty() },
                onProgress = {
                    val failed = it.failed + unavailable > counts.failed || it.uncertain > counts.uncertain
                    currentItem?.let { id -> policy.completeItem(id, failed) }
                    counts = it.copy(failed = it.failed + unavailable); report(BackupPhase.HASHING)
                },
            ).let { it.copy(counts = it.counts.copy(failed = it.counts.failed + unavailable)) }
            if (policy.prefs.getBoolean("override-batch", false) && (result.counts.failed > 0 || result.counts.uncertain > 0)) policy.endOverride()
            if (!result.paused) policy.finish()
            diagnostics.safelyRecord(DiagnosticEvent(DiagnosticComponent.BACKUP,
                if (!result.paused) DiagnosticStep.COMPLETE else if (policy.paused()) DiagnosticStep.USER_PAUSE else DiagnosticStep.CONDITION_WAIT,
                count = result.counts.uploaded + result.counts.alreadyPresent, failed = result.counts.failed, uncertain = result.counts.uncertain))
            return result.copy(waiting = result.paused && !policy.paused())
        } finally { secret.fill(0) }
    }

}
