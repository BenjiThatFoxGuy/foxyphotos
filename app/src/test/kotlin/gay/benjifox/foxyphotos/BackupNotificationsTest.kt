package gay.benjifox.foxyphotos

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33, 36])
class BackupNotificationsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun `one ongoing notification requests promotion only on API36 with safe counts and explicit actions`() {
        val notifications = BackupNotifications(context)
        val snapshot = BackupSnapshot("run", BackupPhase.UPLOADING, BackupCounts(1, 2, 3, 4), 12)
        val n = notifications.build(snapshot)
        assertEquals("Backing up", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("1 uploaded · 2 already present · 3 failed · 4 uncertain", n.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Build.VERSION.SDK_INT >= 36, n.extras.getBoolean("android.requestPromotedOngoing"))
        assertEquals(12, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(10, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertTrue(shadowOf(n.contentIntent).isImmutable)
        assertEquals(MainActivity::class.java.name, shadowOf(n.contentIntent).savedIntent.component!!.className)
        assertEquals(listOf("Pause", "Cancel"), n.actions.map { it.title.toString() })
        n.actions.forEach {
            assertTrue(shadowOf(it.actionIntent).isImmutable)
            assertEquals(BackupService::class.java.name, shadowOf(it.actionIntent).savedIntent.component!!.className)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.getNotificationChannel(BackupNotifications.CHANNEL).importance > NotificationManager.IMPORTANCE_MIN)
        notifications.update(snapshot)
        notifications.update(snapshot.copy(phase = BackupPhase.HASHING))
        assertEquals(listOf(BackupNotifications.ID), manager.activeNotifications.map { it.id })
    }

    @Test @Config(sdk = [36]) fun `API36 uses native progress style and short status on the same notification`() {
        val n = BackupNotifications(context).build(BackupSnapshot("run", BackupPhase.UPLOADING, BackupCounts(uploaded = 1), 4))
        assertEquals("android.app.Notification\$ProgressStyle", n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals("25%", n.shortCriticalText)
    }

    @Test fun `terminal states are not ongoing or promoted and paused offers resume`() {
        val notifications = BackupNotifications(context)
        for (phase in listOf(BackupPhase.PAUSED, BackupPhase.COMPLETED, BackupPhase.ERRORS, BackupPhase.CANCELLED, BackupPhase.TIMED_OUT)) {
            val n = notifications.build(BackupSnapshot("run", phase))
            assertEquals(0, n.flags and Notification.FLAG_ONGOING_EVENT)
            assertFalse(n.extras.getBoolean("android.requestPromotedOngoing"))
            assertEquals(if (phase == BackupPhase.PAUSED) listOf("Resume") else emptyList<String>(), n.actions?.map { it.title.toString() } ?: emptyList<String>())
        }
    }

    @Test fun `dismissed run stays dismissed until a different explicit run`() {
        val notifications = BackupNotifications(context)
        val state = BackupSnapshot("dismissed", BackupPhase.UPLOADING)
        notifications.update(state)
        val manager = context.getSystemService(NotificationManager::class.java)
        val n = manager.activeNotifications.single().notification
        n.deleteIntent.send()
        manager.cancel(BackupNotifications.ID)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        notifications.update(state.copy(phase = BackupPhase.HASHING))
        notifications.update(state.copy(phase = BackupPhase.COMPLETED))
        assertTrue(manager.activeNotifications.isEmpty())
        notifications.update(state.copy(runId = "new-run"))
        assertEquals(1, manager.activeNotifications.size)
    }
}
