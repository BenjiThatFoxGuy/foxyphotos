package gay.benjifox.foxyphotos.core.database

import android.content.Context
import android.util.Base64
import gay.benjifox.foxyphotos.core.backup.UploadedContentLedger

/** Android-backed durable early-PoC content ledger.
 * A record is written only after Google confirms a committed media key.
 */
class SharedPreferencesUploadedContentLedger(context: Context) : UploadedContentLedger {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun contains(sha256: ByteArray): Boolean = preferences.contains(key(sha256))

    override fun providerMediaKey(sha256: ByteArray): String? = preferences.getString(key(sha256), null)

    override fun recordCommitted(sha256: ByteArray, providerMediaKey: String) {
        require(sha256.isNotEmpty()) { "Content hash cannot be empty." }
        require(providerMediaKey.isNotBlank()) { "Provider media key cannot be blank." }
        preferences.edit().putString(key(sha256), providerMediaKey).commit()
    }

    private fun key(sha256: ByteArray): String =
        "uploaded.${Base64.encodeToString(sha256, Base64.NO_WRAP)}"

    private companion object {
        const val PREFERENCES_NAME = "foxyphotos.upload-ledger"
    }
}
