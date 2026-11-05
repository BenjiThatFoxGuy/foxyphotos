package gay.benjifox.foxyphotos.provider.google

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Keep the call cancellable through request streaming and response consumption. */
internal suspend fun <T> Call.consumeCancellable(consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) { response.close(); return }
            val result = runCatching { response.use(consume) }
            continuation.resumeWith(result)
        }
    })
}
