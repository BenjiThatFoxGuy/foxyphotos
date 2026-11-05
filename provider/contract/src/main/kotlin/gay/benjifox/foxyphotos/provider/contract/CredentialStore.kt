package gay.benjifox.foxyphotos.provider.contract

@JvmInline
value class CredentialReference(val value: String) {
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
