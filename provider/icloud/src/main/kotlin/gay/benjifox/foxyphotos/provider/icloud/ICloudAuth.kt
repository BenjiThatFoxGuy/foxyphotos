package gay.benjifox.foxyphotos.provider.icloud

/** Hidden scaffold only. No credentials, transport, persistence, or successful session. */
class ICloudAuth {
    fun signIn(): ICloudAuthResult = ICloudAuthResult.Unavailable
}

sealed interface ICloudAuthResult {
    val message: String

    data object Unavailable : ICloudAuthResult {
        override val message: String = "iCloud sign-in is not implemented."
    }
}
