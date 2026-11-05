package gay.benjifox.foxyphotos.core.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PortableBackupRunnerTest {
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
}
