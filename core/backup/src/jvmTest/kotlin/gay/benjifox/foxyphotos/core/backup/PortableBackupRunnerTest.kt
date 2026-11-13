package gay.benjifox.foxyphotos.core.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PortableBackupRunnerTest {
    @Test fun `gate changing during hash stops before upload without failure`() = runBlocking {
        var blocked = false
        var uploaded = false
        val result = runBackup(items = listOf(1), hash = { blocked = true; it },
            isCommitted = { false }, upload = { _, _ -> uploaded = true; BackupUploadResult("key") },
            recordCommitted = { _, _ -> }, shouldPause = { blocked })
        assertFalse(uploaded)
        assertTrue(result.paused)
        assertEquals(BackupCounts(), result.counts)
    }
    @Test fun `typed provider outcomes retain only confirmed items in ledger`() = runBlocking {
        val ledger = mutableMapOf<Int, String>()
        val result = runBackup(
            items = listOf(1, 2, 3, 4), hash = { it }, isCommitted = { false },
            upload = { item, _ -> when(item) {
                1 -> BackupItemOutcome.Uncertain
                2 -> BackupItemOutcome.Failed
                3 -> BackupUploadResult("confirmed")
                else -> BackupUploadResult("present", alreadyPresent = true)
            } },
            recordCommitted = { hash, key -> ledger[hash] = key },
        )
        assertEquals(BackupCounts(1, 1, 1, 1), result.counts)
        assertEquals(mapOf(3 to "confirmed", 4 to "present"), ledger)
    }
    @Test fun `organization receives deduplicated and new confirmations without affecting upload counts`() = runBlocking {
        val ledger = mutableMapOf(1 to "old")
        val organized = mutableListOf<Int>()
        var uploads = 0
        val result = runBackup(items = listOf(1, 2), hash = { it }, isCommitted = { it in ledger },
            upload = { _, _ -> uploads++; BackupUploadResult("new") }, recordCommitted = { hash, key -> ledger[hash] = key },
            onConfirmed = { item, hash -> assertTrue(hash in ledger); organized += item; error("album unavailable") })
        assertEquals(1, uploads)
        assertEquals(listOf(1, 2), organized)
        assertEquals(BackupCounts(uploaded = 1, alreadyPresent = 1), result.counts)
    }
    @Test fun `confirmation callback runs in same pass after durable receipt and never for uncertain uploads`() = runBlocking {
        val events = mutableListOf<String>()
        val ledger = mutableMapOf<Int, String>()
        val result = runBackup(
            items = listOf(1, 2), hash = { it }, isCommitted = { false },
            upload = { item, _ ->
                events += "upload:$item"
                if (item == 1) BackupUploadResult("provider-key") else BackupItemOutcome.Uncertain
            },
            recordCommitted = { hash, key ->
                ledger[hash] = key
                events += "record:$hash"
            },
            onConfirmed = { item, hash ->
                assertEquals("provider-key", ledger[hash])
                events += "offer:$item"
            },
        )
        assertEquals(listOf("upload:1", "record:1", "offer:1", "upload:2"), events)
        assertEquals(BackupCounts(uploaded = 1, uncertain = 1), result.counts)
    }
}
