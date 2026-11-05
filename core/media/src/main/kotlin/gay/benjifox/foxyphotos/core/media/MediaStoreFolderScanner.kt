package gay.benjifox.foxyphotos.core.media

import android.content.ContentResolver
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaFolder(
    val relativePath: String,
    val imageCount: Int,
    val videoCount: Int,
) {
    val itemCount: Int get() = imageCount + videoCount
}

class MediaStoreFolderScanner(
    private val contentResolver: ContentResolver,
) {
    suspend fun scan(): List<MediaFolder> = withContext(Dispatchers.IO) {
        val counts = mutableMapOf<String, Counts>()
        scanCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, counts, isVideo = false)
        scanCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, counts, isVideo = true)
        counts.map { (path, count) ->
            MediaFolder(path, imageCount = count.images, videoCount = count.videos)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, MediaFolder::relativePath))
    }

    private fun scanCollection(
        collection: android.net.Uri,
        counts: MutableMap<String, Counts>,
        isVideo: Boolean,
    ) {
        contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
            "${MediaStore.MediaColumns.IS_PENDING} = 0",
            null,
            null,
        )?.use { cursor ->
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val path = cursor.getString(pathColumn)?.ifBlank { "Unsorted media" } ?: "Unsorted media"
                val count = counts.getOrPut(path, ::Counts)
                if (isVideo) count.videos++ else count.images++
            }
        }
    }

    private class Counts(var images: Int = 0, var videos: Int = 0)
}
