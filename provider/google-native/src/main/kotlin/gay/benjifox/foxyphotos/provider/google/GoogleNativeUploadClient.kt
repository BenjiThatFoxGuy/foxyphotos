package gay.benjifox.foxyphotos.provider.google

import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

/** Real bclone/gotohp-compatible Google Photos upload sequence for one original file. */
class GoogleNativeUploadClient internal constructor(
    private val diagnostics: UploadDiagnosticSink,
    private val httpClient: OkHttpClient,
    private val authorization: suspend (GoogleCredential) -> String,
    private val retryDelay: suspend (Long) -> Unit,
    private val singleAttempt: Boolean = false,
) {
    constructor(diagnostics: UploadDiagnosticSink = UploadDiagnosticSink {}, singleAttempt: Boolean = false) : this(
        diagnostics, OkHttpClient.Builder().retryOnConnectionFailure(!singleAttempt).build(), cachedAuthorization(), { delay(it) }, singleAttempt,
    )

    suspend fun upload(request: GoogleUploadRequest): GoogleUploadOutcome = withContext(Dispatchers.IO) {
        val trace = UploadDiagnosticTrace(diagnostics, request.fileType, request.sizeBytes)
        trace.step(UploadStage.UPLOAD) {
            require(request.sizeBytes > 0) { "Google Photos cannot upload an empty file." }
            require(request.sha1.size == SHA1_LENGTH) { "A SHA-1 digest is required for Google duplicate detection." }

            val authorization = trace.step(UploadStage.AUTH) { authorization(request.credential) }
            val existingKey = trace.step(UploadStage.HASH_CHECK) { checkExisting(request.sha1, authorization, trace) }
            if (existingKey != null) {
                trace.event(UploadStage.UPLOAD, UploadAction.PRESENT)
                return@step GoogleUploadOutcome.Present(existingKey)
            }

            val uploadToken = trace.step(UploadStage.UPLOAD_TOKEN) { acquireUploadToken(request.sha1, request.sizeBytes, authorization, trace) }
            val commitToken = uploadBytes(uploadToken, request, authorization, trace)
            val mediaKey = commitUpload(commitToken, request, authorization, trace)
            GoogleUploadOutcome.Committed(mediaKey)
        }
    }

    /** Read-only presence check in the currently authenticated account; never uploads bytes. */
    suspend fun findExisting(credential: GoogleCredential, sha1: ByteArray): String? = withContext(Dispatchers.IO) {
        require(sha1.size == SHA1_LENGTH)
        val trace = UploadDiagnosticTrace(diagnostics, UploadFileType.OTHER, 0)
        checkExisting(sha1, authorization(credential), trace)
    }

    private suspend fun checkExisting(sha1: ByteArray, authorization: String, trace: UploadDiagnosticTrace): String? =
        postProtobuf(
            endpoint = GoogleNativeProtocol.hashCheckEndpoint,
            body = GoogleNativeProtocol.encodeHashCheck(sha1),
            authorization = authorization,
            extendedHeaders = false,
            trace = trace,
            stage = UploadStage.HASH_CHECK,
        ).let(GoogleNativeProtocol::decodeExistingMediaKey)

    private suspend fun acquireUploadToken(sha1: ByteArray, sizeBytes: Long, authorization: String, trace: UploadDiagnosticTrace): String {
        val request = Request.Builder()
            .url(GoogleNativeProtocol.uploadEndpoint)
            .post(GoogleNativeProtocol.encodeUploadTokenRequest(sizeBytes).toRequestBody(PROTOBUF))
            .header("Accept-Encoding", "gzip")
            .header("Accept-Language", DEFAULT_LANGUAGE)
            .header("User-Agent", PHOTOS_USER_AGENT)
            .header("Authorization", authorization)
            .header("X-Goog-Hash", "sha1=${Base64.getEncoder().encodeToString(sha1)}")
            .header("X-Upload-Content-Length", sizeBytes.toString())
            .build()
        return execute(request, trace, UploadStage.UPLOAD_TOKEN) { response ->
            requireSuccess(response, "Google rejected the upload-token request.")
            response.header("X-GUploader-UploadID")?.takeIf(String::isNotBlank)
                ?: throw GoogleUploadException("Google upload-token response was incomplete.")
        }
    }

    private suspend fun uploadBytes(
        uploadToken: String,
        request: GoogleUploadRequest,
        authorization: String,
        trace: UploadDiagnosticTrace,
    ): GoogleCommitToken = retrySideEffectingRequest(trace, UploadStage.MEDIA_BYTES) { attempt ->
        if (!request.beforeBytes()) throw GoogleUploadDeferredException()
        val url = "${GoogleNativeProtocol.uploadEndpoint}?upload_id=$uploadToken"
        val byteRequest = Request.Builder()
            .url(url)
            .put(StreamingRequestBody(request.openSource))
            .header("Accept-Encoding", "gzip")
            .header("Accept-Language", DEFAULT_LANGUAGE)
            .header("User-Agent", PHOTOS_USER_AGENT)
            .header("Authorization", authorization)
            .build()
        execute(byteRequest, trace, UploadStage.MEDIA_BYTES, attempt) { response ->
            requireSuccess(response, "Google rejected the media bytes.")
            GoogleNativeProtocol.decodeCommitToken(readBody(response))
        }
    }

    private suspend fun commitUpload(
        token: GoogleCommitToken,
        request: GoogleUploadRequest,
        authorization: String,
        trace: UploadDiagnosticTrace,
    ): String = retrySideEffectingRequest(trace, UploadStage.COMMIT) { attempt ->
        val body = GoogleNativeProtocol.encodeCommitRequest(
            token = token,
            fileName = request.fileName,
            sha1 = request.sha1,
            modifiedEpochSeconds = request.modifiedEpochSeconds,
        )
        postProtobuf(
            endpoint = GoogleNativeProtocol.commitEndpoint,
            body = body,
            authorization = authorization,
            extendedHeaders = true,
            trace = trace,
            stage = UploadStage.COMMIT,
            attempt = attempt,
        ).let(GoogleNativeProtocol::decodeCommittedMediaKey)
    }

    private suspend fun postProtobuf(
        endpoint: String,
        body: ByteArray,
        authorization: String,
        extendedHeaders: Boolean,
        trace: UploadDiagnosticTrace,
        stage: UploadStage,
        attempt: Int = 1,
    ): ByteArray {
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody(PROTOBUF))
            .header("Accept-Encoding", "gzip")
            .header("Accept-Language", DEFAULT_LANGUAGE)
            .header("User-Agent", PHOTOS_USER_AGENT)
            .header("Authorization", authorization)
            .apply {
                if (extendedHeaders) {
                    header("X-Goog-Ext-173412678-Bin", "CgcIAhClARgC")
                    header("X-Goog-Ext-174067345-Bin", "CgIIAg==")
                }
            }
            .build()
        return execute(request, trace, stage, attempt) { response ->
            requireSuccess(response, "Google rejected the provider request.")
            readBody(response).also {
                if (stage == UploadStage.COMMIT) {
                    trace.event(stage, UploadAction.HTTP_RESPONSE, attempt, response.code, CommitResponseShape.summarize(it))
                }
            }
        }
    }

    private suspend fun <T> retrySideEffectingRequest(trace: UploadDiagnosticTrace, stage: UploadStage, block: suspend (Int) -> T): T {
        var lastFailure: Throwable? = null
        val maxAttempts = if (singleAttempt) 1 else MAX_SIDE_EFFECTING_ATTEMPTS
        repeat(maxAttempts) { attempt ->
            try {
                return trace.step(stage, attempt + 1) { block(attempt + 1) }
            } catch (failure: GoogleHttpException) {
                if (!failure.retryable || attempt == maxAttempts - 1) throw failure
                lastFailure = failure
            } catch (failure: IOException) {
                if (attempt == maxAttempts - 1) {
                    throw GoogleUploadUncertainException("Connection ended during a Google upload request.", failure)
                }
                lastFailure = failure
            }
            trace.event(stage, UploadAction.RETRY, attempt + 1)
            retryDelay(RETRY_DELAYS_MILLIS[attempt])
        }
        throw GoogleUploadException("Google upload failed.", lastFailure)
    }

    private suspend fun <T> execute(
        request: Request, trace: UploadDiagnosticTrace, stage: UploadStage, attempt: Int = 1,
        consume: (Response) -> T,
    ): T = httpClient.newCall(request).consumeCancellable { response ->
        trace.event(stage, UploadAction.HTTP_RESPONSE, attempt, response.code)
        consume(response)
    }

    private fun requireSuccess(response: Response, message: String) {
        if (!response.isSuccessful) {
            throw GoogleHttpException(message, response.code, response.code in RETRYABLE_STATUSES)
        }
    }

    private fun readBody(response: Response): ByteArray {
        val body = response.body ?: throw GoogleUploadException("Google returned an empty response.")
        val stream = body.byteStream()
        val decoded = if (response.header("Content-Encoding")?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(stream)
        } else {
            stream
        }
        return decoded.use(InputStream::readBytes)
    }

    private companion object {
        fun cachedAuthorization(): suspend (GoogleCredential) -> String {
            val authClient = GoogleAuthClient()
            return { credential -> authClient.exchange(credential).authorizationHeader() }
        }

        val PROTOBUF = "application/x-protobuf".toMediaType()
        const val DEFAULT_LANGUAGE = "en_US"
        const val PHOTOS_USER_AGENT = "Google Photos/49029607 (Pixel XL; Android 9)"
        const val SHA1_LENGTH = 20
        const val MAX_SIDE_EFFECTING_ATTEMPTS = 4
        val RETRY_DELAYS_MILLIS = longArrayOf(1_000, 2_000, 4_000)
        val RETRYABLE_STATUSES = setOf(429, 500, 502, 503, 504)
    }
}

data class GoogleUploadRequest(
    val credential: GoogleCredential,
    val fileName: String,
    val sizeBytes: Long,
    val modifiedEpochSeconds: Long,
    val sha1: ByteArray,
    val openSource: () -> InputStream,
    val fileType: UploadFileType = UploadFileType.OTHER,
    val beforeBytes: suspend () -> Boolean = { true },
)

sealed interface GoogleUploadOutcome {
    data class Present(val mediaKey: String) : GoogleUploadOutcome
    data class Committed(val mediaKey: String) : GoogleUploadOutcome
}

private class StreamingRequestBody(
    private val openSource: () -> InputStream,
) : RequestBody() {
    override fun contentType() = null

    override fun contentLength() = -1L

    override fun writeTo(sink: BufferedSink) {
        openSource().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
            }
        }
    }
}

class GoogleUploadDeferredException : IllegalStateException("Waiting for backup conditions")

class GoogleUploadException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class GoogleUploadUncertainException(message: String, cause: Throwable) : IllegalStateException(message, cause)

internal class GoogleHttpException(
    message: String,
    val statusCode: Int,
    val retryable: Boolean,
) : IllegalStateException(message)
