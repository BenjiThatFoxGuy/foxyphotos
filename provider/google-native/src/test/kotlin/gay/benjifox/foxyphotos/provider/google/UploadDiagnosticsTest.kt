package gay.benjifox.foxyphotos.provider.google

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class UploadDiagnosticsTest {
    @Test fun `byte bound survives restart and corrupt storage is discarded`() {
        val file = Files.createTempDirectory("diagnostics-bound").resolve("events").toFile()
        val log = UploadDiagnosticLog(file, maxBytes = 1024)
        repeat(30) { log.record(UploadDiagnosticEvent(UploadCorrelationId.create(), UploadStage.COMMIT, UploadAction.START)) }
        assertTrue(file.length() <= 1024)
        assertEquals(log.snapshot().size, UploadDiagnosticLog(file, maxBytes = 1024).snapshot().size)
        file.writeText("foxyphotos-diagnostics-v1\nhttps://private.example/?token=secret /DCIM/photo.jpg\n")
        val recovered = UploadDiagnosticLog(file)
        assertTrue(recovered.snapshot().isEmpty())
        assertFalse(file.readText().contains("secret"))
        file.writeBytes(ByteArray(300_000) { 65 })
        assertTrue(UploadDiagnosticLog(file).snapshot().isEmpty())
        assertTrue(file.length() <= 256 * 1024)
    }

    @Test fun `unwritable storage keeps bounded memory and never throws`() {
        val parent = Files.createTempFile("diagnostics-parent", ".file").toFile()
        val log = UploadDiagnosticLog(java.io.File(parent, "events"), maxEvents = 2)
        repeat(3) { log.record(UploadDiagnosticEvent(UploadCorrelationId.create(), UploadStage.AUTH, UploadAction.START)) }
        assertEquals(2, log.snapshot().size)
        assertFalse(log.storageHealthy)
        log.clear()
        assertTrue(log.snapshot().isEmpty())
        assertFalse(log.storageHealthy)
    }

    @Test fun `corrupt shape cannot inject raw data into viewer`() {
        val file = Files.createTempDirectory("diagnostics-corrupt-shape").resolve("events").toFile()
        val log = UploadDiagnosticLog(file)
        log.record(UploadDiagnosticEvent(UploadCorrelationId.create(), UploadStage.COMMIT, UploadAction.HTTP_RESPONSE,
            responseShape = CommitResponseShape.summarize(byteArrayOf(10, 0))))
        file.writeText(file.readText().replace("1:2:0[]", "private-token.jpg"))
        assertTrue(UploadDiagnosticLog(file).snapshot().isEmpty())
        assertFalse(file.readText().contains("private-token"))
    }

    @Test fun `retains newest bounded events across restart`() {
        val file = Files.createTempDirectory("diagnostics-test").resolve("events").toFile()
        val log = UploadDiagnosticLog(file, maxEvents = 2, maxBytes = 8192)
        val id = UploadCorrelationId.create()
        for (attempt in 1..3) log.record(UploadDiagnosticEvent(id, UploadStage.COMMIT, UploadAction.START, attempt = attempt))
        val restored = UploadDiagnosticLog(file, maxEvents = 2, maxBytes = 8192)
        assertEquals(listOf(2, 3), restored.snapshot().map { it.attempt })
        assertTrue(file.length() <= 8192)
        restored.clear()
        assertTrue(UploadDiagnosticLog(file).snapshot().isEmpty())
    }
}
