package gay.benjifox.foxyphotos

import java.io.File
import java.io.IOException

/** private cache attachments, never permanent document exports. call off the main thread. */
internal object DiagnosticShareFiles {
    private const val MAX_SHARES = 8
    private const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

    @Synchronized fun create(cache: File, text: String): File {
        val directory = File(cache, "diagnostic-shares")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Share cache unavailable")
        val files = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }
            ?: throw IOException("Share cache unreadable")
        val oldestAllowed = System.currentTimeMillis() - MAX_AGE_MILLIS
        val retained = files.filter { file ->
            if (file.lastModified() < oldestAllowed) {
                if (!file.delete()) throw IOException("Share cache cleanup failed")
                false
            } else true
        }
        retained.take((retained.size - MAX_SHARES + 1).coerceAtLeast(0)).forEach {
            if (!it.delete()) throw IOException("Share cache cleanup failed")
        }
        val file = File.createTempFile("foxyphotos-logs-", ".txt", directory)
        try {
            file.writeText(text, Charsets.UTF_8)
            return file
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }
}
