package gay.benjifox.foxyphotos.provider.google

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Platform HTTP seam: iOS uses URLSession file uploads; JVM can use OkHttp. */
interface GoogleHttpTransport {
    fun send(request: GoogleHttpRequest, completion: (GoogleHttpResponse?, String?) -> Unit)
    fun cancelActive()
}

class GoogleHttpRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
    val originalFilePath: String? = null,
)

class GoogleHttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

data class ConfirmedGoogleUpload(val mediaKey: String, val alreadyPresent: Boolean)

/** Provider protocol, auth and confirmation are shared; transport and local media are platform-specific. */
class GoogleUploadSequence(private val transport: GoogleHttpTransport) {
    suspend fun upload(
        credential: GoogleCredential,
        filePath: String,
        fileName: String,
        sizeBytes: Long,
        modifiedEpochSeconds: Long,
        sha1: ByteArray,
    ): ConfirmedGoogleUpload {
        require(filePath.isNotBlank() && fileName.isNotBlank() && sizeBytes > 0 && sha1.size == 20)
        require(credential.hasRequiredFields)
        val authorization = authenticate(credential)
        val commonHeaders = mapOf(
            "Authorization" to authorization,
            "Accept-Language" to "en_US",
            "User-Agent" to "Google Photos/49029607 (Pixel XL; Android 9)",
        )
        val hashResponse = send(GoogleHttpRequest(
            GoogleNativeProtocol.hashCheckEndpoint, "POST",
            commonHeaders + ("Content-Type" to "application/x-protobuf"),
            GoogleNativeProtocol.encodeHashCheck(sha1),
        ))
        GoogleNativeProtocol.decodeExistingMediaKey(hashResponse.body)?.let { key ->
            return ConfirmedGoogleUpload(key, alreadyPresent = true)
        }
        val uploadResponse = send(GoogleHttpRequest(
            GoogleNativeProtocol.uploadEndpoint, "POST",
            commonHeaders + mapOf(
                "Content-Type" to "application/x-protobuf",
                "X-Goog-Hash" to "sha1=${base64(sha1)}",
                "X-Upload-Content-Length" to sizeBytes.toString(),
            ), GoogleNativeProtocol.encodeUploadTokenRequest(sizeBytes),
        ))
        val uploadId = uploadResponse.header("X-GUploader-UploadID")?.takeIf { it.isNotBlank() }
            ?: throw GoogleUploadSequenceException("Upload token response was incomplete.")
        // The original file is sent from disk; never load a video into memory.
        val bytesResponse = send(GoogleHttpRequest(
            "${GoogleNativeProtocol.uploadEndpoint}?upload_id=${encodeForm(uploadId)}",
            "PUT", commonHeaders, originalFilePath = filePath,
        ))
        val token = GoogleNativeProtocol.decodeCommitToken(bytesResponse.body)
        val commitResponse = send(GoogleHttpRequest(
            GoogleNativeProtocol.commitEndpoint, "POST",
            commonHeaders + mapOf(
                "Content-Type" to "application/x-protobuf",
                "X-Goog-Ext-173412678-Bin" to "CgcIAhClARgC",
                "X-Goog-Ext-174067345-Bin" to "CgIIAg==",
            ), GoogleNativeProtocol.encodeCommitRequest(token, fileName, sha1, modifiedEpochSeconds),
        ))
        // A 2xx without this receipt is uncertain, never a confirmed backup.
        val key = try { GoogleNativeProtocol.decodeCommittedMediaKey(commitResponse.body) }
        catch (_: Exception) { throw GoogleUploadSequenceException("Upload confirmation is uncertain.") }
        return ConfirmedGoogleUpload(key, alreadyPresent = false)
    }

    private suspend fun authenticate(credential: GoogleCredential): String {
        val form = credential.authRequestFields().joinToString("&") { (name, value) ->
            "${encodeForm(name)}=${encodeForm(value)}"
        }.encodeToByteArray()
        val response = send(GoogleHttpRequest(
            "https://android.googleapis.com/auth", "POST",
            mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "app" to "com.google.android.apps.photos",
                "device" to credential.androidId,
                "User-Agent" to "GoogleAuth/1.4 (Pixel XL PQ2A.190205.001); gzip",
            ), form,
        ))
        val values = response.body.decodeToString().lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
        val token = values["Auth"]?.takeIf { it.isNotBlank() }
            ?: throw GoogleUploadSequenceException(if (credential.requiresTokenBinding())
                "Credential requires unsupported token binding." else "Authentication returned no bearer token.")
        return "Bearer $token"
    }

    private suspend fun send(request: GoogleHttpRequest): GoogleHttpResponse = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { transport.cancelActive() }
        try {
            transport.send(request) { response, error ->
                when {
                    !continuation.isActive -> Unit
                    error != null -> continuation.resumeWithException(GoogleUploadSequenceException("Provider transport failed."))
                    response == null -> continuation.resumeWithException(GoogleUploadSequenceException("Provider response missing."))
                    response.status !in 200..299 -> continuation.resumeWithException(
                        GoogleUploadSequenceException("Provider returned HTTP ${response.status}.")
                    )
                    else -> continuation.resume(response)
                }
            }
        } catch (_: Exception) {
            if (continuation.isActive) continuation.resumeWithException(GoogleUploadSequenceException("Provider transport failed."))
        }
    }
}

class GoogleUploadSequenceException(message: String) : IllegalStateException(message)

private fun encodeForm(value: String): String = buildString {
    val digits = "0123456789ABCDEF"
    for (byte in value.encodeToByteArray()) {
        val n = byte.toInt() and 255
        if ((n in 65..90) || (n in 97..122) || (n in 48..57) || n == 45 || n == 95 || n == 46 || n == 42) {
            append(n.toChar())
        } else if (n == 32) append('+')
        else { append('%'); append(digits[n shr 4]); append(digits[n and 15]) }
    }
}

private fun base64(input: ByteArray): String = buildString {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    for (i in input.indices step 3) {
        val b0 = input[i].toInt() and 255
        val b1 = if (i + 1 < input.size) input[i + 1].toInt() and 255 else 0
        val b2 = if (i + 2 < input.size) input[i + 2].toInt() and 255 else 0
        append(alphabet[b0 shr 2]); append(alphabet[((b0 and 3) shl 4) or (b1 shr 4)])
        append(if (i + 1 < input.size) alphabet[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
        append(if (i + 2 < input.size) alphabet[b2 and 63] else '=')
    }
}
