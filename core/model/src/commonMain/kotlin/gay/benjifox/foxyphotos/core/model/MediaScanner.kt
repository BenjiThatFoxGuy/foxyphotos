package gay.benjifox.foxyphotos.core.model

/**
 * Platform-agnostic representation of a media folder (album) with item counts.
 *
 * On Android this maps to a MediaStore RELATIVE_PATH; on iOS to a PHAssetCollection.
 */
data class MediaFolder(
    val identifier: String,
    val displayName: String,
    val imageCount: Int,
    val videoCount: Int,
) {
    val itemCount: Int get() = imageCount + videoCount
}

/**
 * Platform-agnostic handle to a local media item.
 *
 * [identifier] is an opaque string that the platform scanner can resolve back
 * to bytes: a content:// URI on Android, a PHAsset localIdentifier on iOS.
 */
data class LocalMediaItem(
    val identifier: String,
    val displayName: String,
    val sizeBytes: Long,
    val modifiedEpochSeconds: Long,
    val folderIdentifier: String,
)

/**
 * A local media item with both SHA-1 and SHA-256 digests of its original bytes.
 *
 * SHA-256 is the canonical content identity for the ledger; SHA-1 is required
 * by Google Photos' provider-specific duplicate check.
 */
data class HashedMediaItem(
    val media: LocalMediaItem,
    val sha1: ByteArray,
    val sha256: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HashedMediaItem) return false
        return media == other.media && sha1.contentEquals(other.sha1) && sha256.contentEquals(other.sha256)
    }

    override fun hashCode(): Int {
        var result = media.hashCode()
        result = 31 * result + sha1.contentHashCode()
        result = 31 * result + sha256.contentHashCode()
        return result
    }
}

/**
 * Platform-agnostic interface for scanning the device photo library.
 *
 * Implementations are platform-specific: MediaStore on Android, Photos
 * framework on iOS. The common backup engine uses this interface so it
 * doesn't need to know which platform it's running on.
 */
interface MediaScanner {
    /** List all folders/albums with their image and video counts. */
    suspend fun scanFolders(): List<MediaFolder>

    /** Scan all media items in the selected folders. */
    suspend fun scanSelectedFolders(folderIds: Set<String>): List<LocalMediaItem>

    /** Hash a single media item, producing SHA-1 and SHA-256 digests. */
    suspend fun hash(media: LocalMediaItem): HashedMediaItem
}
