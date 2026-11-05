package gay.benjifox.foxyphotos.provider.google

import java.io.IOException
import kotlinx.coroutines.*
import okhttp3.*
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class CancellableCallTest {
    @Test fun `cancelling provider IO cancels the actual call without waiting for network`() = runBlocking {
        val call = HeldCall()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { call.consumeCancellable { it.code } }
        assertNotNull(call.callback)
        job.cancelAndJoin()
        assertTrue(call.cancelled)
        // Late network callback must not resurrect success or throw from its thread.
        call.callback!!.onFailure(call, IOException("cancelled"))
        assertTrue(job.isCancelled)
    }
    private class HeldCall : Call {
        var cancelled = false
        var callback: Callback? = null
        override fun request() = Request.Builder().url("https://unused.invalid/").build()
        override fun execute(): Response = error("Synchronous network is forbidden")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = callback != null
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = HeldCall()
    }
}
