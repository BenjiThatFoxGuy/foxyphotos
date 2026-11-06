package gay.benjifox.foxyphotos.provider.google

import gay.benjifox.foxyphotos.core.model.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class OperationalDiagnosticsTest {
    @Test fun mixedLogIsBoundedDurableAndRejectsUntrustedVersions() {
        val file = Files.createTempDirectory("diagnostics").resolve("events").toFile()
        try {
            assertNull(DiagnosticVersion.from("account-token@example.invalid"))
            val log = UploadDiagnosticLog(file, maxEvents = 3)
            repeat(6) { log.record(DiagnosticEvent(DiagnosticComponent.SCHEDULER, DiagnosticStep.SCHEDULE_SKIP, DiagnosticReason.OWNER_BUSY)) }
            assertEquals(3, UploadDiagnosticLog(file).renderedSnapshot().size)
            log.record(UploadDiagnosticEvent(UploadCorrelationId.create(), UploadStage.UPLOAD, UploadAction.START))
            assertEquals(1, UploadDiagnosticLog(file).snapshot().size)
            assertEquals(3, UploadDiagnosticLog(file).renderedSnapshot().size)
        } finally { file.parentFile.deleteRecursively() }
    }
}
