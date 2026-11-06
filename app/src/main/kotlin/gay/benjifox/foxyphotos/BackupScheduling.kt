package gay.benjifox.foxyphotos

import android.app.job.*
import android.content.*
import android.provider.MediaStore
import kotlinx.coroutines.*

/** Admission is shared by both hosts, independently of observable UI state. */
internal object BackupExecution {
    private var owner: Any? = null
    var jobCancellation: (() -> Unit)? = null
    var runningJobId: Int? = null
    fun cancelJob(): Boolean { val cancel = jobCancellation ?: return false; cancel(); return true }
    @Synchronized fun acquire(token: Any): Boolean {
        if (owner != null) return false
        owner = token
        return true
    }
    @Synchronized fun release(token: Any) { if (owner === token) owner = null }
}
internal object BackupScheduling {
    const val PERIODIC = 8101
    const val CONTENT = 8102
    const val TIMER = 8103
    fun eager(context: Context) {
        context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(8104,
            ComponentName(context, BackupJobService::class.java)).setMinimumLatency(1000)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
    }
    fun refresh(context: Context, rearmContent: Boolean = true) {
        val p = AndroidBackupPolicy(context)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val component = ComponentName(context, BackupJobService::class.java)
        val modern = p.prefs.getString("app-mode", "Simple") == "Modern"
        if (modern) {
            if (scheduler.getPendingJob(PERIODIC) == null) scheduler.schedule(JobInfo.Builder(PERIODIC, component)
                .setPeriodic(15 * 60_000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build())
            if (p.timerActive("immediate")) {
                if (BackupExecution.runningJobId != CONTENT && (rearmContent || scheduler.getPendingJob(CONTENT) == null)) scheduler.schedule(JobInfo.Builder(CONTENT, component)
                    .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                    .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                    .setTriggerContentUpdateDelay(1000).setTriggerContentMaxDelay(5000)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
            } else scheduler.cancel(CONTENT)
        } else {
            listOf(PERIODIC, CONTENT, 8104).filter { it != BackupExecution.runningJobId }.forEach { scheduler.cancel(it) }
        }
        // Independent clocks also wake a paused user-started Simple request, never a fresh run.
        val now = System.currentTimeMillis()
        val deadlines = listOf("pause", "override", "immediate").filter { p.timerActive(it) }.map {
            p.prefs.getLong("$it-created", now) + p.prefs.getLong("$it-duration", 0) - now
        }
        if (BackupExecution.runningJobId == TIMER) return
        if (deadlines.isNotEmpty() || p.prefs.getBoolean("request-pending", false)) {
            val delay = deadlines.minOrNull()?.coerceAtLeast(1000) ?: 60_000L
            scheduler.schedule(JobInfo.Builder(TIMER, component).setMinimumLatency(minOf(delay, 60_000L)).build())
        } else scheduler.cancel(TIMER)
    }
}

internal class BackupJobService : JobService() {
    private var scope: CoroutineScope? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val policy = AndroidBackupPolicy(this)
        if (BackupRuntime.state.value.active || !BackupExecution.acquire(this)) {
            jobFinished(params, false)
            if (params.jobId == BackupScheduling.CONTENT) android.os.Handler(mainLooper).post { BackupScheduling.refresh(this, true) }
            return false
        }
        val modern = policy.prefs.getString("app-mode", "Simple") == "Modern"
        if (((!modern || params.jobId == BackupScheduling.TIMER) && !policy.prefs.getBoolean("request-pending", false)) || policy.reasons().isNotEmpty()) {
            BackupExecution.release(this)
            android.os.Handler(mainLooper).post { BackupScheduling.refresh(this, params.jobId == BackupScheduling.CONTENT) }
            return false
        }
        val host = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = host
        BackupExecution.runningJobId = params.jobId
        BackupExecution.jobCancellation = { host.cancel() }
        host.launch {
            val id = java.util.UUID.randomUUID().toString()
            BackupRuntime.mutableState.value = BackupSnapshot(id, BackupPhase.SCANNING)
            try {
                val result = AndroidBackupWork(this@BackupJobService).run({
                    BackupRuntime.mutableState.value = it.copy(runId = id)
                }, { false })
                BackupRuntime.mutableState.value = BackupRuntime.state.value.copy(phase = when {
                    result.waiting -> BackupPhase.WAITING
                    result.paused -> BackupPhase.PAUSED
                    result.counts.failed > 0 || result.counts.uncertain > 0 -> BackupPhase.ERRORS
                    else -> BackupPhase.COMPLETED
                }, counts = result.counts)
            } catch (cancelled: CancellationException) {
                BackupRuntime.mutableState.value = BackupRuntime.state.value.copy(phase = BackupPhase.CANCELLED)
                throw cancelled
            } catch (_: Exception) {
                policy.finish()
                BackupRuntime.mutableState.value = BackupRuntime.state.value.copy(phase = BackupPhase.ERRORS)
            } finally {
                BackupExecution.release(this@BackupJobService)
                scope = null
                BackupExecution.jobCancellation = null
                BackupExecution.runningJobId = null
                jobFinished(params, false)
                android.os.Handler(mainLooper).post { BackupScheduling.refresh(this@BackupJobService, params.jobId == BackupScheduling.CONTENT) }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { if (params.jobId == BackupExecution.runningJobId) { val p = AndroidBackupPolicy(this); if (p.prefs.getBoolean("override-batch", false)) p.endOverride(); scope?.cancel() }; return false }
    override fun onDestroy() { scope?.cancel(); super.onDestroy() }
}
class BackupBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { BackupScheduling.refresh(context) }
}
