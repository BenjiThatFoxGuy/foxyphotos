package gay.benjifox.foxyphotos.core.updater

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ReleaseVerificationTest {
    private val bytes = "actual fixture bytes".toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val release = ReleaseUpdate("gay.benjifox.foxyphotos", "1", 67, 29, "v1", "app.apk",
        "https://github.com/BenjiThatFoxGuy/foxyphotos/releases/download/v1/app.apk", bytes.size.toLong(), digest, "b".repeat(64))
    @Test fun rejectsTruncationOversizeAndHashMismatch() {
        ReleaseVerification.verifyBytes(release, ByteArrayInputStream(bytes))
        for (bad in listOf(bytes.dropLast(1).toByteArray(), bytes + byteArrayOf(1), ByteArray(bytes.size))) {
            assertThrows(IllegalArgumentException::class.java) { ReleaseVerification.verifyBytes(release, ByteArrayInputStream(bad)) }
        }
    }
    @Test fun honoursCancellation() {
        assertThrows(InterruptedException::class.java) {
            ReleaseVerification.verifyBytes(release, ByteArrayInputStream(bytes)) { throw InterruptedException() }
        }
    }
    @Test fun requiresInstalledIdentityInCandidateLineage() {
        ReleaseVerification.verifyIdentity(release, release.packageName, 67, "1", 29, "b".repeat(64), setOf("a", "b".repeat(64)), "a")
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseVerification.verifyIdentity(release, release.packageName, 67, "1", 29, "b".repeat(64), setOf("b".repeat(64)), "a")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseVerification.verifyIdentity(release, "wrong.package", 67, "1", 29, "b".repeat(64), setOf("a"), "a")
        }
    }
}
