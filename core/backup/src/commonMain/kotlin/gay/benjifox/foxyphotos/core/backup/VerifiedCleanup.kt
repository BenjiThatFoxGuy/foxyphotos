package gay.benjifox.foxyphotos.core.backup

import gay.benjifox.foxyphotos.core.platform.Sha256Digest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reads up to buffer.size bytes into [buffer]; returns the count read, or -1 at end of stream. */
fun interface ByteReader {
    fun read(buffer: ByteArray): Int
}

/** Private durable upload evidence; never put these values in diagnostics. */
data class CleanupUploadEvidence(
    val localIdentity: String,
    val folder: String,
    val sha256: String,
    val size: Long,
    val remoteKey: String,
    val uploadRun: String,
    val uploadedAt: Long,
)

data class CleanupVerification(val upload: CleanupUploadEvidence, val run: String, val verifiedAt: Long)

object VerifiedCleanup {
    const val MAX_BYTES = 256L * 1024 * 1024
    const val UPLOAD_MAX_AGE = 30L * 24 * 60 * 60 * 1000
    const val VERIFICATION_MAX_AGE = 10L * 60 * 1000

    fun candidate(e: CleanupUploadEvidence, run: String, now: Long): Boolean =
        e.uploadRun.isNotBlank() && run.isNotBlank() && e.uploadRun != run &&
            e.remoteKey.isNotBlank() && e.sha256.matches(Regex("[0-9a-f]{64}")) &&
            e.size in 1..MAX_BYTES && now >= e.uploadedAt && now - e.uploadedAt <= UPLOAD_MAX_AGE

    fun admit(v: CleanupVerification, enabled: Boolean, folders: Set<String>,
              identity: String, hash: String, size: Long, now: Long): Boolean =
        enabled && v.upload.folder in folders && candidate(v.upload, v.run, now) &&
            now >= v.verifiedAt && now - v.verifiedAt <= VERIFICATION_MAX_AGE &&
            identity == v.upload.localIdentity && hash == v.upload.sha256 && size == v.upload.size

    /** Constant memory, exact byte count, cancellable between bounded reads. Caller owns the stream. */
    suspend fun hash(reader: ByteReader, expectedSize: Long): String {
        require(expectedSize in 1..MAX_BYTES)
        val digest = Sha256Digest()
        try {
            val buffer = ByteArray(32 * 1024)
            var count = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = reader.read(buffer)
                if (n < 0) break
                count += n
                check(count <= expectedSize) { "Content size changed" }
                digest.update(buffer, 0, n)
            }
            currentCoroutineContext().ensureActive()
            check(count == expectedSize) { "Content size changed" }
            return digest.digestHex()
        } finally {
            digest.close()
        }
    }
}

/** Execution boundary: provider failure/cancellation or a stale local check cannot call trash. */
suspend fun executeVerifiedCleanup(
    verification: CleanupVerification,
    remoteHash: suspend () -> String,
    recheck: suspend () -> Boolean,
    trash: suspend () -> Unit,
) {
    check(remoteHash() == verification.upload.sha256) { "Remote content differs" }
    check(recheck()) { "Cleanup no longer eligible" }
    currentCoroutineContext().ensureActive()
    trash()
}
