package gay.benjifox.foxyphotos.provider.google

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Platform HTTP seam: iOS uses URLSession file uploads; JVM can use OkHttp. */
interface GoogleHttpTransport {
    fun send(request: GoogleHttpRequest, completion: GoogleHttpCompletion)
    fun cancelActive()
}

interface GoogleHttpCompletion {
    fun complete(response: GoogleHttpResponse?, error: String?)
}

class GoogleHttpRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
    val originalFilePath: String? = null,
) {
    val bodyBase64: String? get() = body?.let(::base64)
}

class GoogleHttpResponse(val status: Int, val headers: Map<String, String>, val bodyBase64: String) {
    val body: ByteArray get() = decodeBase64(bodyBase64)
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
        val uploadId = uploadResponse.headers.entries.firstOrNull {
            it.key.equals("X-GUploader-UploadID", ignoreCase = true)
        }?.value?.takeIf { it.isNotBlank() }
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

    internal suspend fun authenticate(credential: GoogleCredential): String {
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

    internal suspend fun send(request: GoogleHttpRequest): GoogleHttpResponse = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { transport.cancelActive() }
        try {
            transport.send(request, object : GoogleHttpCompletion {
                override fun complete(response: GoogleHttpResponse?, error: String?) {
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
            })
        } catch (_: Exception) {
            if (continuation.isActive) continuation.resumeWithException(GoogleUploadSequenceException("Provider transport failed."))
        }
    }
}

class GoogleUploadSequenceException(message: String) : IllegalStateException(message)

/** Swift passes hex digests and a form string; neither needs an exported Kotlin ByteArray. */
class GoogleUploadBridge(private val transport: GoogleHttpTransport) {
    @Throws(Exception::class)
    suspend fun upload(
        credentialForm: String, filePath: String, fileName: String,
        sizeBytes: Long, modifiedEpochSeconds: Long, sha1Hex: String,
    ): ConfirmedGoogleUpload = GoogleUploadSequence(transport).upload(
        GoogleCredentialParser.parse(credentialForm), filePath, fileName,
        sizeBytes, modifiedEpochSeconds, decodeHexSha1(sha1Hex),
    )
}

private fun decodeHexSha1(value: String): ByteArray {
    require(value.length == 40)
    return ByteArray(20) { index ->
        val high = value[index * 2].digitToInt(16)
        val low = value[index * 2 + 1].digitToInt(16)
        ((high shl 4) or low).toByte()
    }
}

internal fun decodeBase64(value: String): ByteArray {
    require(value.length % 4 == 0)
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val result = ArrayList<Byte>(value.length / 4 * 3)
    for (i in value.indices step 4) {
        val a = alphabet.indexOf(value[i]); val b = alphabet.indexOf(value[i + 1])
        val c = if (value[i + 2] == '=') 0 else alphabet.indexOf(value[i + 2])
        val d = if (value[i + 3] == '=') 0 else alphabet.indexOf(value[i + 3])
        require(a >= 0 && b >= 0 && c >= 0 && d >= 0)
        result.add(((a shl 2) or (b shr 4)).toByte())
        if (value[i + 2] != '=') result.add((((b and 15) shl 4) or (c shr 2)).toByte())
        if (value[i + 3] != '=') result.add((((c and 3) shl 6) or d).toByte())
    }
    return result.toByteArray()
}

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
