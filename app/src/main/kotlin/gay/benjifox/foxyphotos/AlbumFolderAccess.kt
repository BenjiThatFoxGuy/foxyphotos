package gay.benjifox.foxyphotos

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.*

internal data class AlbumLocalFolder(val volume: String, val path: String, val count: Int) {
    val identity: String get() = "$volume:$path"
}

/** Query only accessible image/video rows; never infer a device volume from a leaf folder name. */
@OptIn(InternalCoroutinesApi::class)
internal suspend fun accessibleAlbumFolders(context: Context): List<AlbumLocalFolder> = withContext(Dispatchers.IO) {
    val signal = android.os.CancellationSignal()
    val handle = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
        if (it != null) signal.cancel()
    }
    try {
        val counts = linkedMapOf<Pair<String, String>, Int>()
        for (collection in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            val columns = arrayOf(MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
            val cursor = context.contentResolver.query(collection, columns, "${MediaStore.MediaColumns.IS_PENDING} = 0", null, null, signal)
                ?: error("Folders unavailable")
            cursor.use { c ->
                while (c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val volume = c.getString(0)
                    val path = c.getString(1)
                    if (!volume.isNullOrBlank() && volume != "external" && !path.isNullOrBlank()) {
                        val key = volume to path
                        counts[key] = (counts[key] ?: 0) + 1
                    }
                }
            }
        }
        counts.map { (key, count) -> AlbumLocalFolder(key.first, key.second, count) }
            .sortedWith(compareBy({ it.path }, { it.volume }))
    } finally { handle?.dispose() }
}

/** Legacy/persisted requests can contain aggregate external URIs. Resolve the actual volume read-only. */
internal suspend fun actualAlbumFolder(context: Context, uri: Uri, path: String): String = withContext(Dispatchers.IO) {
    val volume = MediaStore.getVolumeName(uri)
    val actual = if (volume != "external") volume else {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.VOLUME_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }
    check(!actual.isNullOrBlank() && actual != "external")
    "$actual:$path"
}
