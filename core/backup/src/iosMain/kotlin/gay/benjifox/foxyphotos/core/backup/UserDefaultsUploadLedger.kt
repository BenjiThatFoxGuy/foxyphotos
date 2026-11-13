package gay.benjifox.foxyphotos.core.backup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create

/**
 * iOS UserDefaults-backed [UploadedContentLedger].
 *
 * Mirrors the Android SharedPreferencesUploadedContentLedger: a record is
 * written only after the destination confirms a committed media key. Keys
 * are base64-encoded SHA-256 hashes prefixed with "uploaded." in a dedicated
 * UserDefaults suite so they don't collide with other app preferences.
 */
class UserDefaultsUploadLedger : UploadedContentLedger {

    private val defaults = NSUserDefaults(suiteName = SUITE_NAME)

    override fun contains(sha256: ByteArray): Boolean {
        return defaults.stringForKey(key(sha256)) != null
    }

    override fun providerMediaKey(sha256: ByteArray): String? {
        return defaults.stringForKey(key(sha256))
    }

    override fun recordCommitted(sha256: ByteArray, providerMediaKey: String) {
        require(sha256.isNotEmpty()) { "Content hash cannot be empty." }
        require(providerMediaKey.isNotBlank()) { "Provider media key cannot be blank." }
        defaults.setObject(providerMediaKey, forKey = key(sha256))
        defaults.synchronize()
    }

    private fun key(sha256: ByteArray): String =
        "uploaded.${sha256.toBase64NoPadding()}"

    private companion object {
        const val SUITE_NAME = "foxyphotos.upload-ledger"
    }
}

/** Base64-encode a ByteArray using Foundation, no line wrapping. */
@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toBase64NoPadding(): String {
    if (isEmpty()) return ""
    val nsData = usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
    return nsData.base64EncodedStringWithOptions(0u)
}
