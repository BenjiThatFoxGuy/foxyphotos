package gay.benjifox.foxyphotos.core.media

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaFolder(
    val relativePath: String,
    val imageCount: Int,
    val videoCount: Int,
) {
    val itemCount: Int get() = imageCount + videoCount
}

data class LocalMedia(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val modifiedEpochSeconds: Long,
    val relativePath: String,
)

data class HashedMedia(
    val media: LocalMedia,
    val sha1: ByteArray,
    val sha256: ByteArray,
)

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

    suspend fun scanSelectedFolders(selectedFolders: Set<String>): List<LocalMedia> = withContext(Dispatchers.IO) {
        if (selectedFolders.isEmpty()) return@withContext emptyList()
        buildList {
            scanMediaCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selectedFolders, this)
            scanMediaCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, selectedFolders, this)
        }.sortedBy(LocalMedia::modifiedEpochSeconds)
    }

    suspend fun hash(media: LocalMedia): HashedMedia = withContext(Dispatchers.IO) {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(media.uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sha1.update(buffer, 0, read)
                sha256.update(buffer, 0, read)
            }
        } ?: throw IllegalStateException("Selected media is no longer readable.")
        HashedMedia(media, sha1.digest(), sha256.digest())
    }

    private fun scanMediaCollection(
        collection: Uri,
        selectedFolders: Set<String>,
        target: MutableList<LocalMedia>,
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        contentResolver.query(collection, projection, "${MediaStore.MediaColumns.IS_PENDING} = 0", null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val path = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val relativePath = cursor.getString(path)?.ifBlank { "Unsorted media" } ?: "Unsorted media"
                if (relativePath in selectedFolders) {
                    target += LocalMedia(
                        uri = Uri.withAppendedPath(collection, cursor.getLong(id).toString()),
                        displayName = cursor.getString(name),
                        sizeBytes = cursor.getLong(size),
                        modifiedEpochSeconds = cursor.getLong(modified),
                        relativePath = relativePath,
                    )
                }
            }
        }
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
