package gay.benjifox.foxyphotos.provider.google

class GoogleCredential constructor(
    val accountEmail: String,
    val androidId: String,
    private val formFields: List<Pair<String, String>>,
) {
    val hasRequiredFields: Boolean
        get() = GoogleCredentialParser.requiredFields.all { fieldValue(it).isNullOrBlank().not() }

    /** Sensitive form fields; pass directly to the auth transport and never log. */
    fun authRequestFields(): List<Pair<String, String>> = formFields
        .filterNot { (name, _) -> name in ignoredAuthFields || name == "app" || name == "callerPkg" }
        .plus("app" to googlePhotosPackage)
        .plus("callerPkg" to googlePhotosPackage)

    fun requiresTokenBinding(): Boolean = !fieldValue("token_binding_alias").isNullOrBlank()

    override fun toString(): String = "GoogleCredential(accountEmail=$accountEmail)"

    private fun fieldValue(name: String): String? = formFields.firstOrNull { it.first == name }?.second

    private companion object {
        const val googlePhotosPackage = "com.google.android.apps.photos"
        val ignoredAuthFields = setOf("it_caveat_types", "assertion_jwt", "token_binding_alias")
    }
}

object GoogleCredentialParser {
    internal val requiredFields = setOf(
        "androidId",
        "app",
        "client_sig",
        "Email",
        "Token",
        "lang",
        "service",
    )

    fun parse(importedCredential: String): GoogleCredential {
        val fields = importedCredential
            .trim()
            .split('&')
            .filter(String::isNotBlank)
            .map { pair ->
                val separator = pair.indexOf('=')
                require(separator > 0) { "Google credential contains an invalid field." }
                decode(pair.substring(0, separator)) to decode(pair.substring(separator + 1))
            }

        fun fieldValue(name: String): String? = fields.firstOrNull { it.first == name }?.second

        val missing = requiredFields.filter { fieldValue(it).isNullOrBlank() }
        require(missing.isEmpty()) {
            "Google credential is missing required field(s): ${missing.joinToString(", ")}."
        }

        return GoogleCredential(
            accountEmail = fieldValue("Email")!!,
            androidId = fieldValue("androidId")!!,
            formFields = fields,
        )
    }

    private fun decode(value: String): String {
        val normalized = value.replace('+', ' ')
        val result = StringBuilder()
        var index = 0
        while (index < normalized.length) {
            if (normalized[index] != '%') {
                result.append(normalized[index++])
                continue
            }
            val encoded = mutableListOf<Byte>()
            while (index < normalized.length && normalized[index] == '%') {
                require(index + 2 < normalized.length) { "Malformed credential form encoding." }
                val high = normalized[index + 1].digitToIntOrNull(16)
                val low = normalized[index + 2].digitToIntOrNull(16)
                require(high != null && low != null) { "Malformed credential form encoding." }
                encoded.add(((high shl 4) or low).toByte())
                index += 3
            }
            result.append(encoded.toByteArray().decodeToString(throwOnInvalidSequence = true))
        }
        return result.toString()
    }
}
