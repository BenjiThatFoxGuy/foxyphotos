package gay.benjifox.foxyphotos

import gay.benjifox.foxyphotos.provider.google.GoogleUploadUncertainException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class BackupCounts(
    val uploaded: Int = 0,
    val alreadyPresent: Int = 0,
    val failed: Int = 0,
    val uncertain: Int = 0,
)

internal data class BackupRunResult(val counts: BackupCounts, val paused: Boolean = false) {
    fun summary(): String {
        val status = when {
            paused -> "Backup paused"
            counts.failed > 0 || counts.uncertain > 0 -> "Backup completed with errors"
            else -> "Backup complete"
        }
        return "$status · ${counts.uploaded} uploaded · ${counts.alreadyPresent} already present · ${counts.failed} failed · ${counts.uncertain} uncertain"
    }
}

internal data class BackupUploadResult(val mediaKey: String, val alreadyPresent: Boolean = false)

/** One pass only: only confirmed uploads enter the ledger; failed items retry on a later run. */
internal suspend fun <Item, Hash> runBackup(
    items: List<Item>,
    hash: suspend (Item) -> Hash,
    isCommitted: (Hash) -> Boolean,
    upload: suspend (Item, Hash) -> BackupUploadResult,
    recordCommitted: (Hash, String) -> Unit,
    shouldPause: () -> Boolean = { false },
    onProgress: (BackupCounts) -> Unit = {},
): BackupRunResult {
    var counts = BackupCounts()
    for (item in items) {
        currentCoroutineContext().ensureActive()
        if (shouldPause()) return BackupRunResult(counts, paused = true)
        try {
            val hashed = hash(item)
            if (isCommitted(hashed)) {
                counts = counts.copy(alreadyPresent = counts.alreadyPresent + 1)
            } else {
                val result = upload(item, hashed)
                recordCommitted(hashed, result.mediaKey)
                counts = if (result.alreadyPresent) counts.copy(alreadyPresent = counts.alreadyPresent + 1)
                else counts.copy(uploaded = counts.uploaded + 1)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GoogleUploadUncertainException) {
            counts = counts.copy(uncertain = counts.uncertain + 1)
        } catch (_: Exception) {
            counts = counts.copy(failed = counts.failed + 1)
        }
        onProgress(counts)
    }
    return BackupRunResult(counts, paused = shouldPause())
}
