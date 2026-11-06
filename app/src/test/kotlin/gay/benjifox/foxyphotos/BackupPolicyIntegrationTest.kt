package gay.benjifox.foxyphotos

import android.content.Context
import kotlinx.coroutines.launch
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupPolicyIntegrationTest {
    @Test fun explicitItemScanNeverQueriesNeighborsOrChangesFolderInclusion() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queried = mutableListOf<String>()
        val ids = setOf("content://media/external/images/media/7", "content://media/external/video/media/9")
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): android.database.Cursor {
                queried += uri.toString()
                return android.database.MatrixCursor(projection!!).apply { addRow(arrayOf<Any>("item.jpg", 10L, 100L, "Excluded/")) }
            }
            override fun getType(uri: android.net.Uri) = null
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = null
            override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 0
        })
        val policy = AndroidBackupPolicy(context)
        policy.prefs.edit().clear().putStringSet("enabled-folders", setOf("Camera/")).commit()
        val media = gay.benjifox.foxyphotos.core.media.MediaStoreFolderScanner(context.contentResolver).scanSelectedItems(ids)
        assertEquals(ids, queried.toSet())
        assertEquals(ids, media.map { it.uri.toString() }.toSet())
        assertTrue(media.all { it.relativePath == "Excluded/" })
        assertEquals(setOf("Camera/"), policy.prefs.getStringSet("enabled-folders", emptySet()))
    }
    @Test fun folderOriginIsPersistedAndLegacyRequestsStayFolderScoped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val policy = AndroidBackupPolicy(context)
        policy.prefs.edit().clear().putStringSet("enabled-folders", setOf("Camera/")).commit()
        BackupRuntime.mutableState.value = BackupSnapshot()
        assertTrue(policy.requestItems(setOf("one"), explicit = false))
        assertFalse(AndroidBackupPolicy(context).allowsFolder("Excluded/"))
        assertTrue(policy.allowsFolder("Camera/"))
        policy.prefs.edit().remove("request-origin").commit()
        assertFalse(policy.allowsFolder("Excluded/"))
        policy.finish()
        assertTrue(policy.requestItems(setOf("one")))
        assertTrue(AndroidBackupPolicy(context).allowsFolder("Excluded/"))
    }

    @Test fun pendingBatchExcludesCommittedContentAndDeduplicatesHashes() = kotlinx.coroutines.runBlocking {
        val result = pendingItemIds(listOf("committed", "first", "duplicate", "unreadable"), { it },
            { if (it == "unreadable") throw java.io.IOException() else if (it == "duplicate") "first" else it },
            { it == "committed" })
        assertEquals(setOf("first", "unreadable"), result)
        val policy = AndroidBackupPolicy(ApplicationProvider.getApplicationContext<Context>())
        policy.prefs.edit().clear().commit()
        policy.storeRequest(result)
        policy.override(setOf(gay.benjifox.foxyphotos.core.backup.BackupCondition.CELLULAR), null, result)
        policy.completeItem("first")
        assertTrue(policy.prefs.getBoolean("override-batch", false))
        assertEquals(setOf("unreadable"), AndroidBackupPolicy(ApplicationProvider.getApplicationContext<Context>()).request())
        policy.completeItem("unreadable", failed = true)
        assertFalse(policy.prefs.getBoolean("override-batch", false))
        assertEquals(emptySet<String>(), policy.request())
    }

    @Test fun selectedQueryCancellationInterruptsBlockingProviderBeforeReturn() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entered = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, sort: String?, signal: android.os.CancellationSignal?): android.database.Cursor? {
                signal!!.setOnCancelListener { cancelled.countDown() }
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                signal.throwIfCanceled()
                return null
            }
            override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, sort: String?): android.database.Cursor? = error("must use cancellation signal")
            override fun getType(uri: android.net.Uri) = null
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = null
            override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 0
        })
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            gay.benjifox.foxyphotos.core.media.MediaStoreFolderScanner(context.contentResolver)
                .scanSelectedItems(setOf("content://media/external/images/media/1"))
        }
        try {
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            job.cancel()
            assertTrue(cancelled.await(3, java.util.concurrent.TimeUnit.SECONDS))
        } finally { release.countDown(); job.join() }
        assertTrue(job.isCancelled)
    }

    @Test fun deletedSelectionContinuesReadableItemsWithoutWideningScope() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queried = mutableListOf<String>()
        val failed = mutableListOf<String>()
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): android.database.Cursor {
                queried += uri.lastPathSegment!!
                if (uri.lastPathSegment == "1") throw SecurityException("unavailable")
                return android.database.MatrixCursor(projection!!).apply { addRow(arrayOf<Any>("item.jpg", 10L, 100L, if (uri.lastPathSegment == "2") "Camera/" else "Excluded/")) }
            }
            override fun getType(uri: android.net.Uri) = null
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = null
            override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 0
        })
        val ids = (1..3).map { "content://media/external/images/media/$it" }.toSet()
        val media = gay.benjifox.foxyphotos.core.media.MediaStoreFolderScanner(context.contentResolver)
            .scanSelectedItems(ids, setOf("Camera/"), failed::add)
        assertEquals(listOf("1", "2", "3"), queried)
        assertEquals(listOf("content://media/external/images/media/1"), failed)
        assertEquals(listOf("2"), media.map { it.uri.lastPathSegment })
    }

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
