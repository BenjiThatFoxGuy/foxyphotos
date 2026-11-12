package gay.benjifox.foxyphotos.provider.contract

/**
 * Identifies a stored credential.
 *
 * A plain data class rather than a value class: `@JvmInline` is absent from the
 * common standard library, so value classes cannot be declared in shared code
 * and still compile for every target.
 */
data class CredentialReference(val value: String) {
    init {
        require(value.isNotBlank()) { "Credential reference cannot be blank." }
    }
}

interface CredentialStore {
    suspend fun hasCredential(reference: CredentialReference): Boolean

    suspend fun saveCredential(reference: CredentialReference, secret: ByteArray)

    suspend fun loadCredential(reference: CredentialReference): ByteArray?

    suspend fun deleteCredential(reference: CredentialReference)
}
