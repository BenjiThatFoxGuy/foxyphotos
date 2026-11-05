package gay.benjifox.foxyphotos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

internal class BackupNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Backup progress", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress and controls for backups you start"
            setShowBadge(false)
        })
    }

    fun build(state: BackupSnapshot): Notification {
        val counts = state.counts
        val done = counts.uploaded + counts.alreadyPresent + counts.failed + counts.uncertain
        val content = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), FLAGS)
        val delete = PendingIntent.getBroadcast(context, 0,
            Intent(context, BackupDismissReceiver::class.java).setData(Uri.parse("foxyphotos:notification/${state.runId}"))
                .putExtra(BackupService.RUN_ID, state.runId), FLAGS)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_backup_notification)
            .setContentTitle(state.phase.title)
            .setContentText("${counts.uploaded} uploaded · ${counts.alreadyPresent} already present · ${counts.failed} failed · ${counts.uncertain} uncertain")
            .setContentIntent(content).setDeleteIntent(delete)
            .setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(state.active).setAutoCancel(!state.active)
            .setRequestPromotedOngoing(Build.VERSION.SDK_INT >= 36 && state.active)
        if (state.active) {
            builder.setProgress(state.total, done.coerceAtMost(state.total), state.total == 0)
            if (state.phase != BackupPhase.PAUSING && state.phase != BackupPhase.CANCELLING) builder.addAction(0, "Pause", command(BackupService.PAUSE, state))
            builder.addAction(0, "Cancel", command(BackupService.CANCEL, state))
        } else if (state.phase == BackupPhase.PAUSED) {
            builder.addAction(0, "Resume", command(BackupService.RESUME, state))
        }
        val notification = builder.build()
        return if (Build.VERSION.SDK_INT >= 36 && state.active) {
            val style = Notification.ProgressStyle()
                .setProgressIndeterminate(state.total == 0)
                .addProgressSegment(Notification.ProgressStyle.Segment(state.total.coerceAtLeast(1)))
                .setProgress(done.coerceIn(0, state.total.coerceAtLeast(0)))
            Notification.Builder.recoverBuilder(context, notification)
                .setStyle(style)
                .setShortCriticalText(if (state.total > 0) "${(done.toLong() * 100 / state.total).coerceIn(0, 100)}%" else "Scanning")
                .build()
        } else notification
    }

    private fun command(action: String, state: BackupSnapshot): PendingIntent {
        val intent = Intent(context, BackupService::class.java).setAction(action)
            .setData(Uri.parse("foxyphotos:backup/${state.runId}/$action"))
            .putExtra(BackupService.RUN_ID, state.runId)
        return if (action == BackupService.RESUME) PendingIntent.getForegroundService(context, 0, intent, FLAGS)
        else PendingIntent.getService(context, 0, intent, FLAGS)
    }

    /** The delete receiver marks only this run; progress and completion never resurrect it. */
    fun update(state: BackupSnapshot) {
        if (dismissedRun == state.runId) return
        try { manager.notify(ID, build(state)) } catch (_: Exception) { }
    }
    fun remove() { try { manager.cancel(ID) } catch (_: Exception) { } }

    companion object {
        const val ID = 1401
        const val CHANNEL = "backup-progress"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        internal var dismissedRun: String? = null
    }
}

class BackupDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BackupNotifications.dismissedRun = intent.getStringExtra(BackupService.RUN_ID)
    }
}
