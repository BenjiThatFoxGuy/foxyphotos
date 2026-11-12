package gay.benjifox.foxyphotos.core.backup

import gay.benjifox.foxyphotos.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

enum class BackupPhase(val title: String, val active: Boolean = false) {
    IDLE("Backup ready"), SCANNING("Scanning for backup", true),
    HASHING("Hashing for backup", true), UPLOADING("Backing up", true),
    PAUSING("Pausing after current item", true), CANCELLING("Stopping backup", true), PAUSED("Backup paused"),
    WAITING("Waiting for backup conditions"), COMPLETED("Backup complete"), ERRORS("Backup stopped with errors"),
    CANCELLED("Backup cancelled"), TIMED_OUT("Backup stopped: time limit"),
}

data class BackupSnapshot(
    val runId: String = "",
    val phase: BackupPhase = BackupPhase.IDLE,
    val counts: BackupCounts = BackupCounts(),
    val total: Int = 0,
) {
    val active: Boolean get() = phase.active
}

/** Caller-serialized commands. The platform execution host owns the scope, not a UI observer. */
class BackupSession(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<BackupSnapshot>,
    private val work: suspend ((BackupSnapshot) -> Unit, () -> Boolean) -> BackupRunResult,
    private val changed: (BackupSnapshot) -> Unit = {},
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) {
    private var job: Job? = null
    private var pauseRequested = false
    private var ownedRunId: String? = null
    private var cancellationPhase = BackupPhase.CANCELLED
    private fun publish(value: BackupSnapshot) {
        if (state.value.phase != value.phase) {
            val step = when (value.phase) {
                BackupPhase.SCANNING -> DiagnosticStep.SCAN
                BackupPhase.PAUSING, BackupPhase.PAUSED -> DiagnosticStep.USER_PAUSE
                BackupPhase.WAITING -> DiagnosticStep.CONDITION_WAIT
                BackupPhase.CANCELLED, BackupPhase.CANCELLING -> DiagnosticStep.CANCELLED
                BackupPhase.ERRORS, BackupPhase.TIMED_OUT -> DiagnosticStep.FAILED
                BackupPhase.COMPLETED -> DiagnosticStep.COMPLETE
                else -> null
            }
            if (step != null) diagnostics.safelyRecord(DiagnosticEvent(DiagnosticComponent.BACKUP, step,
                count = value.counts.uploaded + value.counts.alreadyPresent, failed = value.counts.failed, uncertain = value.counts.uncertain))
        }
        state.value = value
        // A notification or observer failure must never change upload outcomes.
        try { changed(value) } catch (_: Exception) { }
    }
    fun start(runId: String) {
        if (job?.isCompleted == false || state.value.active) return
        pauseRequested = false
        ownedRunId = runId
        cancellationPhase = BackupPhase.CANCELLED
        publish(BackupSnapshot(runId, BackupPhase.SCANNING))
        job = scope.launch {
            try {
                val result = work({ progress ->
                    if (state.value.runId == runId && state.value.active && state.value.phase != BackupPhase.CANCELLING) {
                        publish(progress.copy(runId = runId,
                            phase = if (pauseRequested) BackupPhase.PAUSING else progress.phase))
                    }
                }, { pauseRequested })
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (state.value.runId == runId) publish(state.value.copy(counts = result.counts, phase = when {
                    result.waiting -> BackupPhase.WAITING
                    result.paused -> BackupPhase.PAUSED
                    result.counts.failed > 0 || result.counts.uncertain > 0 -> BackupPhase.ERRORS
                    else -> BackupPhase.COMPLETED
                }))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (state.value.runId == runId && state.value.phase != BackupPhase.CANCELLING)
                    publish(state.value.copy(phase = BackupPhase.ERRORS))
            }
        }.also { running ->
            running.invokeOnCompletion { cause ->
                if (cause != null && state.value.runId == runId && state.value.active) {
                    publish(state.value.copy(phase = cancellationPhase))
                }
            }
        }
    }
    fun pause(runId: String) {
        if (state.value.runId != runId || !state.value.active || state.value.phase == BackupPhase.CANCELLING) return
        pauseRequested = true
        publish(state.value.copy(phase = BackupPhase.PAUSING))
    }
    fun cancel(runId: String, timedOut: Boolean = false) {
        if (state.value.runId != runId) return
        if (state.value.phase == BackupPhase.PAUSED || state.value.phase == BackupPhase.WAITING) {
            publish(state.value.copy(phase = BackupPhase.CANCELLED))
            return
        }
        if (!state.value.active || ownedRunId != runId) return
        cancellationPhase = if (timedOut) BackupPhase.TIMED_OUT else BackupPhase.CANCELLED
        publish(state.value.copy(phase = BackupPhase.CANCELLING))
        job?.cancel()
    }
    fun close() { if (state.value.active) ownedRunId?.let { cancel(it) } }
    suspend fun await() { job?.join() }
}
