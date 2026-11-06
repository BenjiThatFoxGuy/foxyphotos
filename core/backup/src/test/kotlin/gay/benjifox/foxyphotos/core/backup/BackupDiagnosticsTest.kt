package gay.benjifox.foxyphotos.core.backup

import gay.benjifox.foxyphotos.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupDiagnosticsTest {
    @Test fun failuresAndDeferredAdmissionAreTypedAndSinkFailureDoesNotChangeOutcome() = runBlocking {
        val events = mutableListOf<DiagnosticEvent>()
        val result = runBackup(listOf(1, 2), hash = { it }, isCommitted = { false },
            upload = { _, _ -> BackupItemOutcome.Failed }, recordCommitted = { _, _ -> }, diagnostics = DiagnosticSink { events.add(it) })
        assertEquals(2, result.counts.failed)
        assertEquals(DiagnosticStep.COMPLETE, events.last().step)
        assertEquals(2, events.last().failed)
        val waiting = runBackup(listOf(1), hash = { it }, isCommitted = { false },
            upload = { _, _ -> BackupItemOutcome.Deferred }, recordCommitted = { _, _ -> }, diagnostics = DiagnosticSink { throw IllegalStateException("secret") })
        assertTrue(waiting.waiting)
    }
}
