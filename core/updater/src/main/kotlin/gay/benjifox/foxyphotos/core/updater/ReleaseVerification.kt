package gay.benjifox.foxyphotos.core.updater

import java.io.InputStream
import java.security.MessageDigest

/** Bounded, streaming integrity gate shared by every native installer adapter. */
object ReleaseVerification {
    fun verifyBytes(release: ReleaseUpdate, input: InputStream, checkCancelled: () -> Unit = {}) {
        release.validate()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count == -1) break
            total += count
            require(total <= release.size)
            digest.update(buffer, 0, count)
        }
        require(total == release.size)
        require(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256)
    }
    fun verifyIdentity(release: ReleaseUpdate, packageName: String, code: Long, name: String?, minSdk: Int,
        currentSigner: String, candidateLineage: Set<String>, installedSigner: String) {
        require(packageName == release.packageName && code == release.versionCode && name == release.versionName && minSdk == release.minSdk)
        require(currentSigner == release.signingCertificateSha256 && installedSigner in candidateLineage)
    }
}
