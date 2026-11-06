package gay.benjifox.foxyphotos.core.backup

import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VerifiedCleanupTest {
    private val now = 100000000L
    private val e = CleanupUploadEvidence("identity", "DCIM/", "a".repeat(64), 5, "remote", "upload-run", now - 1000)
    private val v = CleanupVerification(e, "later-run", now)
    private fun admit(v: CleanupVerification = this.v, enabled: Boolean = true, folders: Set<String> = setOf("DCIM/"),
                      identity: String = "identity", hash: String = e.sha256, size: Long = 5, time: Long = now) =
        VerifiedCleanup.admit(v, enabled, folders, identity, hash, size, time)

    @Test fun requiresSeparateRunAndDurableCompleteEvidence() {
        assertTrue(admit())
        assertFalse(admit(v.copy(run = "upload-run")))
        assertFalse(admit(v.copy(upload = e.copy(remoteKey = ""))))
        assertFalse(admit(v.copy(upload = e.copy(sha256 = "receipt"))))
        assertFalse(admit(v.copy(upload = e.copy(uploadRun = ""))))
    }
    @Test fun changedAuthorizationIdentityContentAndSizeFailClosed() {
        assertFalse(admit(enabled = false))
        assertFalse(admit(folders = emptySet()))
        assertFalse(admit(folders = setOf("DCIM")))
        assertFalse(admit(identity = "reused-uri-new-generation"))
        assertFalse(admit(hash = "b".repeat(64)))
        assertFalse(admit(size = 6))
    }
    @Test fun expiryFutureEvidenceAndBoundsFailClosed() {
        assertFalse(admit(time = now + VerifiedCleanup.VERIFICATION_MAX_AGE + 1))
        assertFalse(admit(time = now - 1))
        assertFalse(admit(v.copy(upload = e.copy(uploadedAt = now + 1))))
        assertFalse(admit(v.copy(upload = e.copy(uploadedAt = now - VerifiedCleanup.UPLOAD_MAX_AGE - 1))))
        assertFalse(admit(v.copy(upload = e.copy(size = 0))))
        assertFalse(admit(v.copy(upload = e.copy(size = VerifiedCleanup.MAX_BYTES + 1))))
    }
    @Test fun boundedStreamRejectsTruncatedAndTransformedBytes() = runBlocking {
        val digest = VerifiedCleanup.hash(ByteArrayInputStream("hello".toByteArray()), 5)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", digest)
        assertNotEquals(digest, VerifiedCleanup.hash(ByteArrayInputStream("HELLO".toByteArray()), 5))
        for (bytes in listOf("hell", "hello!")) {
            try { VerifiedCleanup.hash(ByteArrayInputStream(bytes.toByteArray()), 5); fail("Size mismatch admitted") }
            catch (_: IllegalStateException) { }
        }
    }
    @Test fun executionNeverTrashesOnRemoteFailureMismatchOrRevokedEligibility() = runBlocking {
        var trashes = 0
        val remoteCases: List<suspend () -> String> = listOf(
            { error("Expired auth") }, { error("Network or size failure") },
            { throw CancellationException("Cancelled download") }, { "transformed" })
        for (remote in remoteCases) {
            try { executeVerifiedCleanup(v, remote, { true }, { trashes++ }); fail("Unsafe execution") }
            catch (_: Exception) { }
        }
        try { executeVerifiedCleanup(v, { e.sha256 }, { false }, { trashes++ }); fail("Changed eligibility admitted") }
        catch (_: IllegalStateException) { }
        assertEquals(0, trashes)
        executeVerifiedCleanup(v, { e.sha256 }, { true }, { trashes++ })
        assertEquals(1, trashes)
    }

    @Test fun cancellationStopsStreaming() = runBlocking {
        var reads = 0
        val task = launch(start = CoroutineStart.LAZY) {
            VerifiedCleanup.hash(object : ByteArrayInputStream(ByteArray(100000)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    reads++
                    if (reads == 1) cancel()
                    return super.read(b, off, len)
                }
            }, 100000)
        }
        task.start(); task.join()
        assertTrue(task.isCancelled)
        assertEquals(1, reads)
    }
}
