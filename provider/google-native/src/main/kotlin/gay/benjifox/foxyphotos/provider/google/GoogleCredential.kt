package gay.benjifox.foxyphotos.provider.google

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class GoogleCredential internal constructor(
    val accountEmail: String,
    val androidId: String,
    private val app: String,
    private val clientSignature: String,
    private val token: String,
    private val language: String,
    private val service: String,
) {
    val hasRequiredFields: Boolean
        get() = listOf(androidId, app, clientSignature, accountEmail, token, language, service)
            .all(String::isNotBlank)

    internal fun authenticationFields(): Map<String, String> = mapOf(
        "androidId" to androidId,
        "app" to app,
        "client_sig" to clientSignature,
        "Email" to accountEmail,
        "Token" to token,
        "lang" to language,
        "service" to service,
    )

    override fun toString(): String = "GoogleCredential(accountEmail=$accountEmail)"
}

object GoogleCredentialParser {
    private val requiredFields = setOf(
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
            .associate { pair ->
                val separator = pair.indexOf('=')
                require(separator > 0) { "Google credential contains an invalid field." }
                decode(pair.substring(0, separator)) to decode(pair.substring(separator + 1))
            }

        val missing = requiredFields.filter { fields[it].isNullOrBlank() }
        require(missing.isEmpty()) {
            "Google credential is missing required field(s): ${missing.joinToString(", ")}."
        }

        return GoogleCredential(
            accountEmail = fields.getValue("Email"),
            androidId = fields.getValue("androidId"),
            app = fields.getValue("app"),
            clientSignature = fields.getValue("client_sig"),
            token = fields.getValue("Token"),
            language = fields.getValue("lang"),
            service = fields.getValue("service"),
        )
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)
}
