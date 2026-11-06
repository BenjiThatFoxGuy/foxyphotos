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
