package gay.benjifox.foxyphotos.provider.google

import gay.benjifox.foxyphotos.provider.foundation.HttpClient
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class GoogleAuthClient {
    private val httpClient = HttpClient(OkHttpClient())

    suspend fun exchange(credential: GoogleCredential): GoogleBearerToken =
        parseAuthResponse(httpClient.execute(createAuthRequest(credential)), credential)

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

    override fun toString(): String = "GoogleBearerToken(expiresAtEpochSeconds=$expiresAtEpochSeconds)"
}

class GoogleAuthenticationException(message: String) : IllegalStateException(message)
