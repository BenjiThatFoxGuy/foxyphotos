package gay.benjifox.foxyphotos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupPolicyIntegrationTest {
    @Test fun explicitSelectionIsPersistedAndBusyAdmissionPreservesOriginal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(BackupService.SETTINGS, 0).edit().clear().commit()
        val policy = AndroidBackupPolicy(context)
        BackupRuntime.mutableState.value = BackupSnapshot()
        assertTrue(policy.requestItems(setOf("content://media/external/images/media/1")))
        BackupRuntime.mutableState.value = BackupSnapshot("busy", BackupPhase.UPLOADING)
        assertFalse(policy.requestItems(setOf("content://media/external/images/media/2")))
        assertEquals(setOf("content://media/external/images/media/1"), AndroidBackupPolicy(context).request())
        BackupRuntime.mutableState.value = BackupSnapshot()
    }
    @Test fun backwardClockPermanentlyExpiresGrantInsteadOfReactivatingLater() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val policy = AndroidBackupPolicy(context)
        policy.prefs.edit().clear().commit()
        policy.timer("override", 900000)
        policy.prefs.edit().putLong("override-created", System.currentTimeMillis() + 100000).commit()
        assertFalse(policy.timerActive("override"))
        assertFalse(policy.prefs.contains("override-created"))
    }
    @Test fun timedPauseAndExceptionClocksRemainIndependentAcrossBlockerChurn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val policy = AndroidBackupPolicy(context)
        policy.prefs.edit().clear().commit()
        policy.pause(900000)
        policy.timer("override", 3600000)
        policy.prefs.edit().putBoolean("allow-cellular", true).commit()
        assertTrue(AndroidBackupPolicy(context).paused())
        policy.clearTimer("override")
        assertTrue(policy.paused())
        policy.resume()
        assertFalse(policy.paused())
    }
    @Test fun serializedOwnerCannotBeStolenOrReleasedByAnotherHost() {
        val a = Any(); val b = Any()
        assertTrue(BackupExecution.acquire(a))
        assertFalse(BackupExecution.acquire(b))
        BackupExecution.release(b)
        assertFalse(BackupExecution.acquire(b))
        BackupExecution.release(a)
        assertTrue(BackupExecution.acquire(b))
        BackupExecution.release(b)
    }
    @Test fun scheduledJobsSeparateDurablePeriodicFromBoundedContentObservation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val policy = AndroidBackupPolicy(context)
        policy.prefs.edit().clear().putString("app-mode", "Modern").commit()
        policy.timer("immediate", 900000)
        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
        val periodic = scheduler.getPendingJob(BackupScheduling.PERIODIC)!!
        val content = scheduler.getPendingJob(BackupScheduling.CONTENT)!!
        assertTrue(periodic.isPersisted)
        assertTrue(periodic.isPeriodic)
        assertFalse(content.isPersisted)
        assertFalse(content.isPeriodic)
        assertEquals(2, content.triggerContentUris!!.size)
        policy.clearTimer("immediate")
        BackupScheduling.refresh(context)
        assertNull(scheduler.getPendingJob(BackupScheduling.CONTENT))
    }
    @Test fun switchingToSimpleDoesNotCancelAnAlreadyRunningAutomaticJob() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val policy = AndroidBackupPolicy(context)
        policy.prefs.edit().clear().putString("app-mode", "Modern").commit()
        BackupScheduling.refresh(context)
        BackupExecution.runningJobId = BackupScheduling.PERIODIC
        policy.prefs.edit().putString("app-mode", "Simple").commit()
        BackupScheduling.refresh(context)
        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
        assertNotNull(scheduler.getPendingJob(BackupScheduling.PERIODIC))
        BackupExecution.runningJobId = null
        BackupScheduling.refresh(context)
        assertNull(scheduler.getPendingJob(BackupScheduling.PERIODIC))
    }
    @Test fun simpleDoesNotScheduleFreshWorkButModernDoes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(BackupService.SETTINGS, 0)
        prefs.edit().clear().putString("app-mode", "Simple").commit()
        BackupScheduling.refresh(context)
        val jobs = context.getSystemService(android.app.job.JobScheduler::class.java)
        assertNull(jobs.getPendingJob(BackupScheduling.PERIODIC))
        prefs.edit().putString("app-mode", "Modern").commit()
        BackupScheduling.refresh(context)
        assertNotNull(jobs.getPendingJob(BackupScheduling.PERIODIC))
        prefs.edit().putString("app-mode", "Simple").commit()
        BackupScheduling.refresh(context)
        assertNull(jobs.getPendingJob(BackupScheduling.PERIODIC))
    }
}
