package gay.benjifox.foxyphotos

import gay.benjifox.foxyphotos.provider.google.GoogleUploadException
import gay.benjifox.foxyphotos.provider.google.GoogleUploadUncertainException
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupRunnerTest {
    @Test fun `late provider refusal waits preserving confirmed outcomes`() = runBlocking {
        val ledger = mutableListOf<Int>()
        val result = runBackup(listOf(1, 2, 3), { it }, { false },
            { item, _ -> if (item == 2) throw gay.benjifox.foxyphotos.provider.google.GoogleUploadDeferredException() else BackupUploadResult("key") },
            { item, _ -> ledger += item })
        assertTrue(result.paused)
        assertTrue(result.waiting)
        assertEquals(BackupCounts(uploaded = 1), result.counts)
        assertEquals(listOf(1), ledger)
    }

    @Test fun `cancelled job stops between items even when callbacks do not suspend`() = runBlocking {
        val visited = mutableListOf<Int>()
        val recorded = mutableListOf<Int>()
        val job = launch {
            runBackup(
                items = listOf(1, 2), hash = { visited += it; it },
                isCommitted = { false }, upload = { _, _ -> BackupUploadResult("key") },
                recordCommitted = { hash, _ -> recorded += hash },
                onProgress = { coroutineContext.cancel() },
            )
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf(1), visited)
        assertEquals(listOf(1), recorded)
    }

    @Test fun `hashing and source read errors do not block healthy items`() = runBlocking {
        val ledger = mutableMapOf<Int, String>()
        val hashes = mutableListOf<Int>()
        val uploads = mutableListOf<Int>()
        val result = runBackup(
            items = listOf(1, 2, 3),
            hash = { item ->
                hashes += item
                if (item == 1) throw IOException("private filename in hashing error")
                item
            },
            isCommitted = { it in ledger },
            upload = { item, _ ->
                uploads += item
                if (item == 2) throw IOException("source cannot be opened")
                BackupUploadResult("key")
            },
            recordCommitted = { hash, key -> ledger[hash] = key },
        )
        assertEquals(BackupCounts(uploaded = 1, failed = 2), result.counts)
        assertEquals(listOf(1, 2, 3), hashes)
        assertEquals(listOf(2, 3), uploads)
        assertEquals(mapOf(3 to "key"), ledger)
        assertFalse(result.summary().contains("private"))
    }

    @Test fun `cancellation propagates from hashing and upload without processing next item`() = runBlocking {
        for (duringHash in listOf(true, false)) {
            val cancellation = CancellationException("stop")
            assertStops(cancellation, duringHash)
        }
    }

    @Test fun `fatal errors propagate from hashing and upload without processing next item`() = runBlocking {
        for (duringHash in listOf(true, false)) {
            assertStops(AssertionError("fatal"), duringHash)
        }
    }

    private suspend fun assertStops(failure: Throwable, duringHash: Boolean) {
        val visited = mutableListOf<Int>()
        val ledger = mutableMapOf<Int, String>()
        try {
            runBackup(
                items = listOf(1, 2),
                hash = { item ->
                    visited += item
                    if (duringHash) throw failure
                    item
                },
                isCommitted = { false },
                upload = { _, _ -> throw failure },
                recordCommitted = { hash, key -> ledger[hash] = key },
            )
            fail("Expected propagation")
        } catch (actual: Throwable) {
            assertSame(failure, actual)
        }
        assertEquals(listOf(1), visited)
        assertTrue(ledger.isEmpty())
    }

    @Test fun `pause preserves every count and stops before next item`() = runBlocking {
        var pause = false
        val visited = mutableListOf<Int>()
        val result = runBackup(
            items = listOf(1, 2, 3, 4, 5),
            hash = { visited += it; it }, isCommitted = { it == 2 },
            upload = { item, _ ->
                when (item) {
                    3 -> throw IOException("failed")
                    4 -> throw GoogleUploadUncertainException("uncertain", IOException())
                    else -> BackupUploadResult("key")
                }
            },
            recordCommitted = { _, _ -> },
            shouldPause = { pause },
            onProgress = { if (it.uncertain == 1) pause = true },
        )
        assertTrue(result.paused)
        assertEquals(listOf(1, 2, 3, 4), visited)
        assertEquals(BackupCounts(1, 1, 1, 1), result.counts)
        assertEquals("Backup paused · 1 uploaded · 1 already present · 1 failed · 1 uncertain", result.summary())
    }

    @Test fun `uncertain upload is counted separately and remains retryable`() = runBlocking {
        val ledger = mutableMapOf<Int, String>()
        val attempted = mutableListOf<Int>()
        suspend fun run() = runBackup(
            items = listOf(1, 2), hash = { it }, isCommitted = { it in ledger },
            upload = { item, _ ->
                attempted += item
                if (item == 1) throw gay.benjifox.foxyphotos.provider.google.GoogleUploadUncertainException(
                    "Unconfirmed", java.io.IOException("private provider detail"),
                )
                BackupUploadResult("confirmed")
            },
            recordCommitted = { hash, key -> ledger[hash] = key },
        )
        val result = run()
        assertEquals(BackupCounts(uploaded = 1, uncertain = 1), result.counts)
        assertEquals(listOf(1, 2), attempted)
        assertEquals(mapOf(2 to "confirmed"), ledger)
        assertTrue(result.summary().startsWith("Backup completed with errors"))
        assertEquals(BackupCounts(alreadyPresent = 1, uncertain = 1), run().counts)
        assertEquals(listOf(1, 2, 1), attempted)
        assertFalse(ledger.containsKey(1))
    }

    @Test fun `missing commit key fails only that item and retries it next run`() = runBlocking {
        val ledger = mutableMapOf<Int, String>()
        val attempts = mutableListOf<Int>()
        suspend fun run() = runBackup(
            items = listOf(1, 2, 3),
            hash = { it },
            isCommitted = { it in ledger },
            upload = { item, _ ->
                attempts += item
                if (item == 1) throw GoogleUploadException("Missing commit key")
                BackupUploadResult("key-$item", alreadyPresent = item == 3)
            },
            recordCommitted = { hash, key -> ledger[hash] = key },
        )

        val first = run()
        assertEquals(BackupCounts(uploaded = 1, alreadyPresent = 1, failed = 1), first.counts)
        assertEquals(listOf(1, 2, 3), attempts)
        assertEquals(mapOf(2 to "key-2", 3 to "key-3"), ledger)
        assertEquals("Backup completed with errors · 1 uploaded · 1 already present · 1 failed · 0 uncertain", first.summary())

        val second = run()
        assertEquals(listOf(1, 2, 3, 1), attempts)
        assertEquals(BackupCounts(alreadyPresent = 2, failed = 1), second.counts)
        assertFalse(ledger.containsKey(1))
    }
}
