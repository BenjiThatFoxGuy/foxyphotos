package gay.benjifox.foxyphotos.provider.google

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UploadDiagnosticTraceTest {
    @Test fun `failure excludes exception secrets and preserves original failure`() = runBlocking {
        val file = Files.createTempDirectory("diagnostic-trace").resolve("events").toFile()
        val log = UploadDiagnosticLog(file)
        val trace = UploadDiagnosticTrace(log, UploadFileType.JPEG, 12345)
        val secret = "https://signed.example/upload?token=secret person@example.com /DCIM/private.jpg abc-sha256 media-key credential"
        val failure = IOException(secret)
        try {
            trace.step(UploadStage.MEDIA_BYTES, 2) { throw failure }
            fail("expected failure")
        } catch (caught: IOException) { assertSame(failure, caught) }
        trace.event(UploadStage.COMMIT, UploadAction.HTTP_RESPONSE, 3, 503,
            CommitResponseShape.summarize(byteArrayOf(10, 0)))
        val restored = UploadDiagnosticLog(file).snapshot()
        assertEquals(UploadFailure.NETWORK, restored[1].failure)
        assertEquals(2, restored[1].attempt)
        assertEquals(503, restored.last().httpStatus)
        assertEquals(1, restored.map { it.correlationId.toString() }.distinct().size)
        assertTrue(restored.all { it.fileType == UploadFileType.JPEG && it.sizeBytes == 12345L })
        assertNotNull(restored.last().responseShape)
        val text = restored.joinToString { it.render() } + file.readText()
        for (value in listOf("signed.example", "token=", "secret", "person@", "DCIM", "private.jpg", "abc-sha256", "media-key", "credential")) {
            assertFalse(value, text.contains(value))
        }
    }

    @Test fun `file type mapping only emits allowlisted labels`() {
        assertEquals(UploadFileType.JPEG, UploadFileType.fromMimeType("image/jpeg"))
        assertEquals(UploadFileType.HEIC, UploadFileType.fromMimeType("image/heic"))
        assertEquals(UploadFileType.MOV, UploadFileType.fromMimeType("video/quicktime"))
        assertEquals(UploadFileType.OTHER_IMAGE, UploadFileType.fromMimeType("image/private-secret.jpg"))
        assertEquals(UploadFileType.OTHER, UploadFileType.fromMimeType("https://signed/?token=secret"))
        assertEquals(UploadFileType.OTHER, UploadFileType.fromMimeType(null))
    }

    @Test fun `sink failure cannot change step result`() = runBlocking {
        val trace = UploadDiagnosticTrace(UploadDiagnosticSink { throw IOException("signed url") }, UploadFileType.OTHER, 1)
        assertEquals(42, trace.step(UploadStage.COMMIT) { 42 })
    }
}
