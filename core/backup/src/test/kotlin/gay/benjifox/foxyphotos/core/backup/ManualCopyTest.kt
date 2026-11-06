package gay.benjifox.foxyphotos.core.backup

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ManualCopyTest {
    private val digest = "a".repeat(40)
    private open inner class Port : ManualCopyPort {
        var uploads = 0
        var reads = 0
        var lookups = 0
        var organized = 0
        var released = 0
        var existing: String? = null
        override suspend fun stage(sourceId: String): String { reads++; return digest }
        override suspend fun findExisting(sha1: String): String? { lookups++; return existing }
        override suspend fun upload(sourceId: String): String { uploads++; return "confirmed-key" }
        override suspend fun organize(sourceId: String, mediaKey: String): CopyOrganization {
            organized++; return CopyOrganization(true, "Requested; membership unconfirmed")
        }
        override fun releaseStage() { released++ }
    }
    @Test fun lostUploadIsDurableAndRetryOnlyChecksPresence() = runBlocking {
        var row = CopyEntry("source")
        val port = object : Port() {
            override suspend fun upload(sourceId: String): String {
                assertEquals(CopyPhase.UPLOAD_UNCERTAIN, row.phase)
                uploads++; error("Lost response")
            }
        }
        suspend fun pass() = runManualCopy(listOf(row), port, { _, e -> row = e })
        pass()
        assertEquals(CopyPhase.UPLOAD_UNCERTAIN, row.phase)
        pass()
        assertEquals(1, port.uploads)
        assertEquals(1, port.reads)
        assertEquals(0, port.organized)
        port.existing = "recovered-key"
        pass()
        assertEquals(CopyPhase.ORGANIZATION_UNCONFIRMED, row.phase)
        assertEquals("recovered-key", row.mediaKey)
        assertEquals(1, port.uploads)
        pass()
        assertEquals(1, port.organized)
    }
    @Test fun failedJournalCannotAuthorizeUpload() = runBlocking {
        val port = Port()
        try { runManualCopy(listOf(CopyEntry("source")), port, { _, _ -> error("Disk full") }); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(0, port.uploads)
        assertEquals(1, port.released)
    }
    @Test fun pauseAfterReadDoesNotUpload() = runBlocking {
        val port = Port()
        runManualCopy(listOf(CopyEntry("source")), port, { _, _ -> fail() }, paused = { port.reads > 0 })
        assertEquals(0, port.uploads)
        assertEquals(1, port.released)
    }
    @Test fun cancelDuringUploadKeepsUncertaintyAndReleasesStage() = runBlocking {
        var row = CopyEntry("source")
        val port = object : Port() {
            override suspend fun upload(sourceId: String): String { uploads++; throw CancellationException() }
        }
        try { runManualCopy(listOf(row), port, { _, e -> row = e }); fail() }
        catch (_: CancellationException) { }
        assertEquals(CopyPhase.UPLOAD_UNCERTAIN, row.phase)
        assertEquals(1, port.released)
    }
    @Test fun cancelledLookupCannotStartAlbumWrite() = runBlocking {
        val port = object : Port() {
            override suspend fun findExisting(sha1: String): String {
                currentCoroutineContext().cancel()
                return "present-key"
            }
        }
        val task = launch {
            runManualCopy(listOf(CopyEntry("source", CopyPhase.UPLOAD_UNCERTAIN, digest)), port, { _, _ -> })
        }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(0, port.organized)
    }
}
