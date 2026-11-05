package gay.benjifox.foxyphotos

import android.net.Uri
import gay.benjifox.foxyphotos.core.media.LocalMedia
import gay.benjifox.foxyphotos.core.media.MediaStoreFolderScanner
import java.io.InputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupHashCancellationTest {
    @Test fun `cancelled hash stops reading after the current chunk`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val resolver = context.contentResolver
        val uri = Uri.parse("content://media/external/images/media/1")
        val work = Job()
        var reads = 0
        var closed = false
        shadowOf(resolver).registerInputStream(uri, object : InputStream() {
            override fun read(): Int = error("Use buffered reads")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                if (reads > 1) return -1
                work.cancel()
                buffer[offset] = 1
                return 1
            }
            override fun close() { closed = true }
        })
        try {
            withContext(work) { MediaStoreFolderScanner(resolver).hash(LocalMedia(uri, "private", 1, 1, "private")) }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, reads)
        assertTrue(closed)
    }
}
