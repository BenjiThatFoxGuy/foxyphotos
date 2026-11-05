package gay.benjifox.foxyphotos.provider.google

import java.io.IOException
import kotlinx.coroutines.CancellationException

/** one trace per upload. the sink never receives requests, credentials, exceptions or identifiers. */
internal class UploadDiagnosticTrace(
    private val sink: UploadDiagnosticSink,
    private val fileType: UploadFileType,
    private val sizeBytes: Long,
) {
    private val id = UploadCorrelationId.create()

    fun event(
        stage: UploadStage,
        action: UploadAction,
        attempt: Int = 1,
        status: Int? = null,
        shape: CommitResponseShape? = null,
        failure: UploadFailure = UploadFailure.NONE,
    ) {
        try {
            sink.record(UploadDiagnosticEvent(id, stage, action, attempt, status, fileType,
                sizeBytes.coerceAtLeast(0), failure, responseShape = shape))
        } catch (_: Exception) { /* diagnostics must not change upload outcomes */ }
    }

    suspend fun <T> step(stage: UploadStage, attempt: Int = 1, block: suspend () -> T): T {
        event(stage, UploadAction.START, attempt)
        try {
            return block().also { event(stage, UploadAction.SUCCESS, attempt) }
        } catch (failure: Exception) {
            val category = when (failure) {
                is CancellationException -> UploadFailure.CANCELLED
                is GoogleHttpException -> UploadFailure.HTTP
                is GoogleUploadUncertainException -> UploadFailure.UNCERTAIN
                is IOException -> UploadFailure.NETWORK
                is GoogleProtocolException -> UploadFailure.PROTOCOL
                is IllegalArgumentException -> UploadFailure.INVALID_INPUT
                else -> UploadFailure.OTHER
            }
            event(stage, UploadAction.FAILURE, attempt, (failure as? GoogleHttpException)?.statusCode, failure = category)
            throw failure
        }
    }
}
