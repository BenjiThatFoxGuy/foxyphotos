package gay.benjifox.foxyphotos.core.backup

import gay.benjifox.foxyphotos.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class BackupCounts(
    val uploaded: Int = 0,
    val alreadyPresent: Int = 0,
    val failed: Int = 0,
    val uncertain: Int = 0,
)

data class BackupRunResult(val counts: BackupCounts, val paused: Boolean = false, val waiting: Boolean = false) {
    fun summary(): String {
        val status = when {
            paused -> "Backup paused"
            counts.failed > 0 || counts.uncertain > 0 -> "Backup completed with errors"
            else -> "Backup complete"
        }
        return "$status · ${counts.uploaded} uploaded · ${counts.alreadyPresent} already present · ${counts.failed} failed · ${counts.uncertain} uncertain"
    }
}

sealed interface BackupItemOutcome {
    data object Deferred : BackupItemOutcome
    data object Uncertain : BackupItemOutcome
    data object Failed : BackupItemOutcome
}

data class BackupUploadResult(val mediaKey: String, val alreadyPresent: Boolean = false) : BackupItemOutcome

/** One pass only: only confirmed uploads enter the ledger; failed items retry on a later run. */
suspend fun <Item, Hash> runBackup(
    items: List<Item>,
    hash: suspend (Item) -> Hash,
    isCommitted: (Hash) -> Boolean,
    upload: suspend (Item, Hash) -> BackupItemOutcome,
    recordCommitted: (Hash, String) -> Unit,
    shouldPause: () -> Boolean = { false },
    onProgress: (BackupCounts) -> Unit = {},
    diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    onConfirmed: suspend (Item, Hash) -> Unit = { _, _ -> },
): BackupRunResult {
    var counts = BackupCounts()
    fun emit(step: DiagnosticStep) = diagnostics.safelyRecord(DiagnosticEvent(DiagnosticComponent.BACKUP, step,
        count = counts.uploaded + counts.alreadyPresent, failed = counts.failed, uncertain = counts.uncertain))
    suspend fun notifyConfirmed(item: Item, hashed: Hash) {
        // Optional organization must never turn a confirmed upload into a failed upload.
        try { if (!shouldPause()) onConfirmed(item, hashed) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Separate album status belongs to the adapter. */ }
    }
    var hashedOnce = false
    var admittedOnce = false
    emit(DiagnosticStep.PLAN)
    for (item in items) {
        currentCoroutineContext().ensureActive()
        if (shouldPause()) return BackupRunResult(counts, paused = true)
        try {
            if (!hashedOnce) { hashedOnce = true; emit(DiagnosticStep.HASH) }
            val hashed = hash(item)
            if (isCommitted(hashed)) {
                counts = counts.copy(alreadyPresent = counts.alreadyPresent + 1)
                notifyConfirmed(item, hashed)
            } else {
                // Hashing can outlast a network transition or a temporary exception.
                if (shouldPause()) return BackupRunResult(counts, paused = true)
                if (!admittedOnce) { admittedOnce = true; emit(DiagnosticStep.ADMISSION) }
                when (val result = upload(item, hashed)) {
                    is BackupUploadResult -> {
                        currentCoroutineContext().ensureActive()
                        recordCommitted(hashed, result.mediaKey)
                        counts = if (result.alreadyPresent) counts.copy(alreadyPresent = counts.alreadyPresent + 1)
                        else counts.copy(uploaded = counts.uploaded + 1)
                        notifyConfirmed(item, hashed)
                    }
                    BackupItemOutcome.Deferred -> { emit(DiagnosticStep.CONDITION_WAIT); return BackupRunResult(counts, paused = true, waiting = true) }
                    BackupItemOutcome.Uncertain -> counts = counts.copy(uncertain = counts.uncertain + 1)
                    BackupItemOutcome.Failed -> counts = counts.copy(failed = counts.failed + 1)
                }
            }
        } catch (cancelled: CancellationException) {
            emit(DiagnosticStep.CANCELLED)
            throw cancelled
        } catch (_: Exception) {
            counts = counts.copy(failed = counts.failed + 1)
        }
        onProgress(counts)
    }
    val paused = shouldPause()
    if (!paused) emit(DiagnosticStep.COMPLETE)
    return BackupRunResult(counts, paused = paused)
}
