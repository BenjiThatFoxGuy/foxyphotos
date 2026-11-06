package gay.benjifox.foxyphotos

import gay.benjifox.foxyphotos.core.backup.BackupItemOutcome
import gay.benjifox.foxyphotos.provider.google.GoogleUploadUncertainException

internal typealias BackupCounts = gay.benjifox.foxyphotos.core.backup.BackupCounts
internal typealias BackupRunResult = gay.benjifox.foxyphotos.core.backup.BackupRunResult
internal typealias BackupUploadResult = gay.benjifox.foxyphotos.core.backup.BackupUploadResult
internal typealias BackupSnapshot = gay.benjifox.foxyphotos.core.backup.BackupSnapshot
internal typealias BackupPhase = gay.benjifox.foxyphotos.core.backup.BackupPhase
internal typealias BackupSession = gay.benjifox.foxyphotos.core.backup.BackupSession

/** Provider-specific uncertainty is mapped at the Android/provider boundary. */
internal suspend fun <Item, Hash> runBackup(
    items: List<Item>,
    hash: suspend (Item) -> Hash,
    isCommitted: (Hash) -> Boolean,
    upload: suspend (Item, Hash) -> BackupUploadResult,
    recordCommitted: (Hash, String) -> Unit,
    shouldPause: () -> Boolean = { false },
    onProgress: (BackupCounts) -> Unit = {},
    diagnostics: gay.benjifox.foxyphotos.core.model.DiagnosticSink = gay.benjifox.foxyphotos.core.model.DiagnosticSink.NONE,
    onConfirmed: suspend (Item, Hash) -> Unit = { _, _ -> },
): BackupRunResult = gay.benjifox.foxyphotos.core.backup.runBackup(
    items, hash, isCommitted,
    upload = { item, hashed ->
        try { upload(item, hashed) }
        catch (_: gay.benjifox.foxyphotos.provider.google.GoogleUploadDeferredException) { BackupItemOutcome.Deferred }
        catch (_: GoogleUploadUncertainException) { BackupItemOutcome.Uncertain }
    },
    recordCommitted, shouldPause, onProgress, diagnostics, onConfirmed,
)
