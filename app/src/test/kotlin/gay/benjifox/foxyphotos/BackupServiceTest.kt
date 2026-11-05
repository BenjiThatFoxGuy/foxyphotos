package gay.benjifox.foxyphotos

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33, 36])
class BackupServiceTest {
    @Before fun reset() {
        BackupRuntime.mutableState.value = BackupSnapshot()
        BackupNotifications.dismissedRun = null
    }
    private fun intent(action: String, runId: String = BackupRuntime.state.value.runId) =
        Intent(RuntimeEnvironment.getApplication(), BackupService::class.java).setAction(action).putExtra(BackupService.RUN_ID, runId)

    @Test fun `service survives observers and duplicate starts then destroy cancels work and notification`() {
        val controller = Robolectric.buildService(HeldService::class.java).create()
        val service = controller.get()
        service.onStartCommand(intent(BackupService.START), 0, 1)
        val run = BackupRuntime.state.value.runId
        assertTrue(BackupRuntime.state.value.active)
        service.onStartCommand(intent(BackupService.START), 0, 2)
        service.onStartCommand(intent(BackupService.CANCEL, "stale"), 0, 3)
        assertEquals(run, BackupRuntime.state.value.runId)
        assertEquals(1, service.runs)
        controller.destroy()
        assertTrue(service.cleaned)
        assertEquals(BackupPhase.CANCELLED, BackupRuntime.state.value.phase)
        assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Test fun `notification denial and optional builder failure do not prevent foreground work`() {
        val controller = Robolectric.buildService(BrokenNotificationService::class.java).create()
        val service = controller.get()
        shadowOf(service.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        service.onStartCommand(intent(BackupService.START), 0, 1)
        assertEquals(1, service.runs)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertTrue(BackupRuntime.state.value.active)
        service.onStartCommand(intent(BackupService.CANCEL), 0, 2)
        assertTrue(service.cleaned)
        assertEquals(BackupPhase.CANCELLED, BackupRuntime.state.value.phase)
        controller.destroy()
    }

    @Test @Config(sdk = [36]) fun `timeout terminates foreground lifetime with truthful state`() {
        val controller = Robolectric.buildService(HeldService::class.java).create()
        val service = controller.get()
        service.onStartCommand(intent(BackupService.START), 0, 1)
        service.onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        assertEquals(BackupPhase.TIMED_OUT, BackupRuntime.state.value.phase)
        assertTrue(service.cleaned)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        controller.destroy()
    }

    @Test fun `notification pause drains current item then resume starts another foreground pass`() {
        val first = Robolectric.buildService(PausableService::class.java).create()
        val service = first.get()
        service.onStartCommand(intent(BackupService.START), 0, 1)
        val oldRun = BackupRuntime.state.value.runId
        val manager = service.getSystemService(NotificationManager::class.java)
        val pause = manager.activeNotifications.single().notification.actions.first { it.title == "Pause" }
        service.onStartCommand(shadowOf(pause.actionIntent).savedIntent, 0, 2)
        assertEquals(BackupPhase.PAUSING, BackupRuntime.state.value.phase)
        service.finishItem.complete(Unit)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(BackupPhase.PAUSED, BackupRuntime.state.value.phase)
        assertEquals(1, BackupRuntime.state.value.counts.uploaded)
        val pausedNotification = manager.activeNotifications.single().notification
        assertEquals(0, pausedNotification.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue(service.getSharedPreferences(BackupService.SETTINGS, 0).getBoolean("paused", false))
        val resume = shadowOf(pausedNotification.actions.single().actionIntent).savedIntent
        first.destroy()
        val second = Robolectric.buildService(HeldService::class.java).create()
        second.get().onStartCommand(resume, 0, 3)
        assertTrue(BackupRuntime.state.value.active)
        assertNotEquals(oldRun, BackupRuntime.state.value.runId)
        assertFalse(second.get().getSharedPreferences(BackupService.SETTINGS, 0).getBoolean("paused", true))
        val cancel = manager.activeNotifications.single().notification.actions.first { it.title == "Cancel" }
        second.get().onStartCommand(shadowOf(cancel.actionIntent).savedIntent, 0, 4)
        assertEquals(BackupPhase.CANCELLED, BackupRuntime.state.value.phase)
        assertTrue(second.get().cleaned)
        second.destroy()
    }

    @Test fun `dismissing active notification cancels only its run and does not repost`() {
        val controller = Robolectric.buildService(HeldService::class.java).create()
        val service = controller.get()
        service.onStartCommand(intent(BackupService.START), 0, 1)
        val runId = BackupRuntime.state.value.runId
        val manager = service.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.single().notification
        val app = shadowOf(RuntimeEnvironment.getApplication())
        BackupDismissReceiver().onReceive(service, Intent().putExtra(BackupService.RUN_ID, "stale"))
        assertNull(app.nextStartedService)
        manager.cancel(BackupNotifications.ID)
        notification.deleteIntent.send()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val cancel = app.nextStartedService
        assertNotNull("Dismissal must request cancellation, not just hide progress", cancel)
        assertEquals(BackupService.CANCEL, cancel.action)
        assertEquals(runId, cancel.getStringExtra(BackupService.RUN_ID))
        service.onStartCommand(cancel, 0, 2)
        assertEquals(BackupPhase.CANCELLED, BackupRuntime.state.value.phase)
        assertTrue(service.cleaned)
        assertTrue(manager.activeNotifications.isEmpty())
        controller.destroy()
    }

    @Test fun `failed run removes foreground record before posting a dismissible result`() {
        val controller = Robolectric.buildService(FailingService::class.java).create()
        val service = controller.get()
        service.onStartCommand(intent(BackupService.START), 0, 1)
        service.finish.completeExceptionally(java.io.IOException("offline"))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(BackupPhase.ERRORS, BackupRuntime.state.value.phase)
        assertTrue("Remove the foreground record, do not detach its live-update flags", shadowOf(service).getNotificationShouldRemoved())
        val notification = service.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals(0, notification.flags and (Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR))
        assertFalse(notification.extras.getBoolean("android.requestPromotedOngoing"))
        assertNull(notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }

    internal class FailingService : BackupService() {
        val finish = CompletableDeferred<Unit>()
        override suspend fun performBackup(progress: (BackupSnapshot) -> Unit, shouldPause: () -> Boolean): BackupRunResult {
            finish.await()
            return BackupRunResult(BackupCounts(), false)
        }
    }

    @Test fun `null restart and stale resume do not start work`() {
        val controller = Robolectric.buildService(HeldService::class.java).create()
        val service = controller.get()
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        service.onStartCommand(intent(BackupService.RESUME, "old"), 0, 2)
        assertEquals(0, service.runs)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }

    internal class PausableService : BackupService() {
        val finishItem = CompletableDeferred<Unit>()
        override suspend fun performBackup(progress: (BackupSnapshot) -> Unit, shouldPause: () -> Boolean): BackupRunResult {
            finishItem.await()
            return BackupRunResult(BackupCounts(uploaded = 1), shouldPause())
        }
    }

    internal open class HeldService : BackupService() {
        var runs = 0
        var cleaned = false
        override suspend fun performBackup(progress: (BackupSnapshot) -> Unit, shouldPause: () -> Boolean): BackupRunResult {
            runs++
            try { awaitCancellation() } finally { cleaned = true }
        }
    }
    internal class BrokenNotificationService : HeldService() {
        override fun buildNotification(state: BackupSnapshot): Notification = throw IllegalStateException("builder unavailable")
    }
}
