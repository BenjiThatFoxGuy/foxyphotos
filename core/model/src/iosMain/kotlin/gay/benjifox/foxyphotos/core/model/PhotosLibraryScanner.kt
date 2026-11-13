package gay.benjifox.foxyphotos.core.model

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import gay.benjifox.foxyphotos.core.platform.Sha256Digest
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Photos.PHAsset
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionSubtypeAny
import platform.Photos.PHAssetCollectionTypeAlbum
import platform.Photos.PHAssetCollectionTypeSmartAlbum
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHFetchOptions
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsVersionOriginal
import platform.Photos.PHPhotoLibrary
import platform.posix.memcpy
import kotlin.coroutines.resume

/**
 * iOS Photos framework-backed [MediaScanner].
 *
 * Scans the device photo library using PHAsset and PHAssetCollection. Hashing
 * uses CommonCrypto (CC_SHA1, CC_SHA256) for hardware-accelerated digests.
 *
 * The caller must ensure PHPhotoLibrary authorization before using this scanner.
 */
@OptIn(ExperimentalForeignApi::class)
class PhotosLibraryScanner : MediaScanner {

    override suspend fun scanFolders(): List<MediaFolder> = withContext(Dispatchers.Default) {
        checkAuthorization()
        val folders = mutableListOf<MediaFolder>()

        // Smart albums (Camera Roll, Favorites, Screenshots, etc.)
        val smartAlbums = PHAssetCollection.fetchAssetCollectionsWithType(
            type = PHAssetCollectionTypeSmartAlbum,
            subtype = PHAssetCollectionSubtypeAny,
            options = null,
        )
        for (i in 0 until smartAlbums.count.toInt()) {
            val collection = smartAlbums.objectAtIndex(i.toULong()) as PHAssetCollection
            val title = collection.localizedTitle ?: continue
            val identifier = collection.localIdentifier
            val counts = countMediaTypes(collection)
            if (counts.first + counts.second > 0) {
                folders.add(MediaFolder(identifier, title, counts.first, counts.second))
            }
        }

        // User albums
        val userAlbums = PHAssetCollection.fetchAssetCollectionsWithType(
            type = PHAssetCollectionTypeAlbum,
            subtype = PHAssetCollectionSubtypeAny,
            options = null,
        )
        for (i in 0 until userAlbums.count.toInt()) {
            val collection = userAlbums.objectAtIndex(i.toULong()) as PHAssetCollection
            val title = collection.localizedTitle ?: continue
            val identifier = collection.localIdentifier
            val counts = countMediaTypes(collection)
            if (counts.first + counts.second > 0) {
                folders.add(MediaFolder(identifier, title, counts.first, counts.second))
            }
        }

        folders.sortedBy { it.displayName.lowercase() }
    }

    override suspend fun scanSelectedFolders(folderIds: Set<String>): List<LocalMediaItem> =
        withContext(Dispatchers.Default) {
            checkAuthorization()
            if (folderIds.isEmpty()) return@withContext emptyList()

            val items = mutableListOf<LocalMediaItem>()

            for (folderId in folderIds) {
                val collections = PHAssetCollection.fetchAssetCollectionsWithLocalIdentifiers(
                    identifiers = listOf(folderId),
                    options = null,
                )
                if (collections.count.toInt() == 0) continue
                val collection = collections.objectAtIndex(0u) as PHAssetCollection

                val fetchOptions = PHFetchOptions()
                val assets = PHAsset.fetchAssetsInAssetCollection(collection, options = fetchOptions)

                for (i in 0 until assets.count.toInt()) {
                    val asset = assets.objectAtIndex(i.toULong()) as PHAsset
                    items.add(
                        LocalMediaItem(
                            identifier = asset.localIdentifier,
                            displayName = assetDisplayName(asset),
                            sizeBytes = 0L, // PHAsset doesn't expose size without requesting data
                            modifiedEpochSeconds = asset.modificationDate?.timeIntervalSince1970?.toLong() ?: 0L,
                            folderIdentifier = folderId,
                        ),
                    )
                }
            }

            items.sortedBy { it.modifiedEpochSeconds }
        }

    override suspend fun hash(media: LocalMediaItem): HashedMediaItem {
        val data = requestAssetData(media.identifier)
            ?: throw IllegalStateException("Media ${media.identifier} is no longer readable.")

        val bytes = data.toByteArray()
        val sha1 = sha1(bytes)
        val sha256 = Sha256Digest().run {
            update(bytes, 0, bytes.size)
            val hex = digestHex()
            close()
            ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        // Update sizeBytes now that we have the actual data
        val sized = media.copy(sizeBytes = bytes.size.toLong())
        return HashedMediaItem(sized, sha1, sha256)
    }

    // --- Private helpers ---

    private fun countMediaTypes(collection: PHAssetCollection): Pair<Int, Int> {
        val imageOpts = PHFetchOptions().apply {
            predicate = platform.Foundation.NSPredicate.predicateWithFormat("mediaType == %d", PHAssetMediaTypeImage)
        }
        val videoOpts = PHFetchOptions().apply {
            predicate = platform.Foundation.NSPredicate.predicateWithFormat("mediaType == %d", PHAssetMediaTypeVideo)
        }
        val images = PHAsset.fetchAssetsInAssetCollection(collection, options = imageOpts).count.toInt()
        val videos = PHAsset.fetchAssetsInAssetCollection(collection, options = videoOpts).count.toInt()
        return images to videos
    }

    private fun assetDisplayName(asset: PHAsset): String {
        // PHAsset doesn't have a displayName property; use the resource's original filename
        val resources = platform.Photos.PHAssetResource.assetResourcesForAsset(asset)
        val resource = resources.firstOrNull() as? platform.Photos.PHAssetResource
        return resource?.originalFilename ?: "unknown"
    }

    private suspend fun requestAssetData(localIdentifier: String): NSData? {
        val assets = PHAsset.fetchAssetsWithLocalIdentifiers(
            identifiers = listOf(localIdentifier),
            options = null,
        )
        if (assets.count.toInt() == 0) return null
        val asset = assets.objectAtIndex(0u) as PHAsset

        return suspendCancellableCoroutine { cont ->
            val options = PHImageRequestOptions().apply {
                synchronous = false
                version = PHImageRequestOptionsVersionOriginal
                networkAccessAllowed = true
            }
            PHImageManager.defaultManager().requestImageDataAndOrientationForAsset(
                asset = asset,
                options = options,
            ) { data, _, _, _ ->
                cont.resume(data)
            }
        }
    }

    private fun checkAuthorization() {
        val status = PHPhotoLibrary.authorizationStatus()
        check(status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited) {
            "Photos library access not authorized (status: $status)"
        }
    }

}

/** Convert NSData to ByteArray. */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    return ByteArray(len).also { bytes ->
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), this.bytes, this.length)
        }
    }
}
