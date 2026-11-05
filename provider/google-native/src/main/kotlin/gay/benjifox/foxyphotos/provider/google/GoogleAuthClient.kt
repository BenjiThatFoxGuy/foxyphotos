package gay.benjifox.foxyphotos.provider.google

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class GoogleAuthClient {
    private val httpClient = OkHttpClient()
    private var cachedBearer: GoogleBearerToken? = null
    private var cachedCredentialFingerprint: ByteArray? = null

    suspend fun exchange(credential: GoogleCredential): GoogleBearerToken {
        val credentialFingerprint = fingerprint(credential)
        cachedBearer?.takeIf {
            it.isUsableAt(System.currentTimeMillis() / 1_000) &&
                cachedCredentialFingerprint?.contentEquals(credentialFingerprint) == true
        }?.let { return it }

        val freshBearer = httpClient.newCall(createAuthRequest(credential)).consumeCancellable { response ->
            parseAuthResponse(response, credential)
        }
        cachedBearer = freshBearer
        cachedCredentialFingerprint = credentialFingerprint
        return freshBearer
    }

    fun clearCachedSession() {
        cachedBearer = null
        cachedCredentialFingerprint = null
    }

    private fun fingerprint(credential: GoogleCredential): ByteArray {
        val canonicalForm = credential.authRequestFields()
            .joinToString("&") { (name, value) -> "$name=$value" }
        return MessageDigest.getInstance("SHA-256").digest(canonicalForm.toByteArray(StandardCharsets.UTF_8))
    }

    private fun createAuthRequest(credential: GoogleCredential): Request {
        val formBody = FormBody.Builder().apply {
            credential.authRequestFields().forEach { (name, value) -> add(name, value) }
        }.build()
        return Request.Builder()
            .url(defaultAuthEndpoint)
            .post(formBody)
            .header("Accept-Encoding", "gzip")
            .header("app", googlePhotosPackage)
            .header("Connection", "Keep-Alive")
            .header("device", credential.androidId)
            .header("User-Agent", googleAuthUserAgent)
            .build()
    }

    private fun parseAuthResponse(response: Response, credential: GoogleCredential): GoogleBearerToken =
        response.use {
            if (!response.isSuccessful) {
                throw GoogleAuthenticationException("Google authentication failed with HTTP ${response.code}.")
            }

            val fields = parseResponse(readResponseBody(response))
            val bearerToken = fields["Auth"]
            if (bearerToken.isNullOrBlank()) {
                if (credential.requiresTokenBinding()) {
                    throw GoogleAuthenticationException(
                        "This credential requires unsupported rooted-device token binding.",
                    )
                }
                throw GoogleAuthenticationException("Google authentication returned no bearer token.")
            }

            GoogleBearerToken(
                bearerToken = bearerToken,
                expiresAtEpochSeconds = fields["Expiry"]?.toLongOrNull() ?: 0L,
            )
        }

    private fun readResponseBody(response: Response): String {
        val source = (response.body ?: throw GoogleAuthenticationException(
            "Google authentication returned an empty response.",
        )).byteStream()
        val decodedSource = if (response.header("Content-Encoding")?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(source)
        } else {
            source
        }
        return decodedSource.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun parseResponse(body: String): Map<String, String> = body
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }
        .toMap()

    private companion object {
        const val defaultAuthEndpoint = "https://android.googleapis.com/auth"
        const val googlePhotosPackage = "com.google.android.apps.photos"
        const val googleAuthUserAgent = "GoogleAuth/1.4 (Pixel XL PQ2A.190205.001); gzip"
    }
}

class GoogleBearerToken internal constructor(
    private val bearerToken: String,
    val expiresAtEpochSeconds: Long,
) {
    @JvmSynthetic
    internal fun authorizationHeader(): String = "Bearer $bearerToken"

    @JvmSynthetic
    internal fun isUsableAt(epochSeconds: Long): Boolean = expiresAtEpochSeconds > epochSeconds

    override fun toString(): String = "GoogleBearerToken(expiresAtEpochSeconds=$expiresAtEpochSeconds)"
}

class GoogleAuthenticationException(message: String) : IllegalStateException(message)
