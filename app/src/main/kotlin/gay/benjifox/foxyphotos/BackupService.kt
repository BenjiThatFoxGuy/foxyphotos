package gay.benjifox.foxyphotos

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local observable status, not a promise of scheduled work after process death. */
internal object BackupRuntime {
    val mutableState = MutableStateFlow(BackupSnapshot())
    val state = mutableState.asStateFlow()
    fun command(context: Context, action: String) {
        val policy = AndroidBackupPolicy(context)
        if (action == BackupService.PAUSE) policy.pause(null)
        if (action == BackupService.RESUME) policy.resume()
        if (action == BackupService.CANCEL) {
            policy.finish()
            if (BackupExecution.cancelJob()) return
        }
        val intent = Intent(context, BackupService::class.java).setAction(action)
            .putExtra(BackupService.RUN_ID, state.value.runId)
        try {
            if (action == BackupService.START || action == BackupService.RESUME) ContextCompat.startForegroundService(context, intent)
            else context.startService(intent)
        } catch (_: Exception) {
            if (!state.value.active) mutableState.value = state.value.copy(phase = BackupPhase.ERRORS)
        }
    }
}

internal open class BackupService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: BackupSession
    private lateinit var notifications: BackupNotifications
    private var foreground = false
    private var destroying = false

    override fun onCreate() {
        super.onCreate()
        notifications = BackupNotifications(this)
        session = BackupSession(serviceScope, BackupRuntime.mutableState, ::performBackup, ::publish)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = BackupRuntime.state.value
        val runId = intent?.getStringExtra(RUN_ID)
        when (intent?.action) {
            START -> if (!state.active && BackupExecution.acquire(this)) session.start(UUID.randomUUID().toString())
            RESUME -> if (!state.active && state.phase == BackupPhase.PAUSED && runId == state.runId && BackupExecution.acquire(this)) {
                AndroidBackupPolicy(this).resume()
                session.start(UUID.randomUUID().toString())
            }
            PAUSE -> if (runId != null && runId == state.runId) { AndroidBackupPolicy(this).pause(null); session.pause(runId) }
            CANCEL -> if (runId != null && runId == state.runId) { AndroidBackupPolicy(this).finish(); session.cancel(runId) }
        }
        if (!BackupRuntime.state.value.active) stopSelf(startId)
        return START_NOT_STICKY
    }

    internal open fun buildNotification(state: BackupSnapshot): android.app.Notification = notifications.build(state)

    private fun publish(state: BackupSnapshot) {
        if (!state.active) {
            BackupExecution.release(this)
            if (state.phase == BackupPhase.ERRORS || state.phase == BackupPhase.CANCELLED || state.phase == BackupPhase.TIMED_OUT) AndroidBackupPolicy(this).finish()
            BackupScheduling.refresh(this, false)
        }
        if (destroying) return
        if (state.active) {
            if (!foreground) {
                // A foreground service cannot run honestly without its mandatory notification.
                // Optional promotion/update failures are isolated; FGS admission failure stops work.
                val notification = try { buildNotification(state) } catch (_: Exception) {
                    android.app.Notification.Builder(this, BackupNotifications.CHANNEL)
                        .setSmallIcon(R.drawable.ic_backup_notification)
                        .setContentTitle(state.phase.title).setOngoing(true)
                        .setContentIntent(android.app.PendingIntent.getActivity(this, 0,
                            Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE))
                        .build()
                }
                try {
                    startForeground(BackupNotifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    foreground = true
                } catch (_: Exception) {
                    BackupRuntime.mutableState.value = state.copy(phase = BackupPhase.ERRORS)
                    serviceScope.cancel()
                    notifications.remove()
                    stopSelf()
                }
            } else notifications.update(state)
        } else {
            // Remove the foreground record before reusing its ID for a normal result.
            // Detaching can retain system-owned FGS/Live Update dismissal restrictions.
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            if (state.phase == BackupPhase.CANCELLED || state.phase == BackupPhase.TIMED_OUT) notifications.remove()
            else notifications.update(state)
            stopSelf()
        }
    }

    internal open suspend fun performBackup(
        progress: (BackupSnapshot) -> Unit,
        shouldPause: () -> Boolean,
    ): BackupRunResult = AndroidBackupWork(this).run(progress, shouldPause)

    override fun onTimeout(startId: Int, fgsType: Int) {
        session.cancel(BackupRuntime.state.value.runId, timedOut = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        destroying = true
        val wasActive = BackupRuntime.state.value.active
        session.close()
        BackupExecution.release(this)
        serviceScope.cancel()
        if (wasActive || foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notifications.remove()
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val SETTINGS = "foxyphotos.backup-settings"
        const val START = "backup.START"
        const val PAUSE = "backup.PAUSE"
        const val RESUME = "backup.RESUME"
        const val CANCEL = "backup.CANCEL"
        const val RUN_ID = "backup.runId"
    }
}
