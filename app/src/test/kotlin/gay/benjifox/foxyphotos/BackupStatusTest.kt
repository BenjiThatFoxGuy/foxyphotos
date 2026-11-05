package gay.benjifox.foxyphotos

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupStatusTest {
    @Test
    fun `status distinguishes active runs from idle and pending pause`() {
        assertEquals("Backup running", backupStatusLabel(isRunning = true, paused = false))
        assertEquals("Pausing backup · finishing current item", backupStatusLabel(isRunning = true, paused = true))
        assertEquals("Backup paused", backupStatusLabel(isRunning = false, paused = true))
        assertEquals("Backup idle", backupStatusLabel(isRunning = false, paused = false))
    }
}
