package gay.benjifox.foxyphotos

import gay.benjifox.foxyphotos.core.model.DiagnosticReason
import org.junit.Assert.*
import org.junit.Test

class SchedulerDiagnosticsTest {
    @Test fun unchangedSkipReasonsAreSuppressedUntilAnOwnerStartsOrReasonChanges() {
        val id = BackupScheduling.PERIODIC
        BackupScheduling.started(id)
        assertTrue(BackupScheduling.changedSkip(id, DiagnosticReason.SERVICE_OWNER_BUSY))
        repeat(10) { assertFalse(BackupScheduling.changedSkip(id, DiagnosticReason.SERVICE_OWNER_BUSY)) }
        assertTrue(BackupScheduling.changedSkip(id, DiagnosticReason.JOB_OWNER_BUSY))
        BackupScheduling.started(id)
        assertTrue(BackupScheduling.changedSkip(id, DiagnosticReason.JOB_OWNER_BUSY))
    }
}
