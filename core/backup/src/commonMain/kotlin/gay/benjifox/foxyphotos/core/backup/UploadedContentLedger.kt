package gay.benjifox.foxyphotos.core.backup

/** Records content confirmed by a destination so retries do not blindly re-upload it. */
interface UploadedContentLedger {
    fun contains(sha256: ByteArray): Boolean

    fun providerMediaKey(sha256: ByteArray): String?

    /** Call only after the destination confirms a committed media key. */
    fun recordCommitted(sha256: ByteArray, providerMediaKey: String)
}
