package gay.benjifox.foxyphotos.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSelectionTest {
    @Test fun overlappingCameraRollAndAlbumAdmitEachAssetOnce() {
        val roll = LocalMediaItem("asset-1", "photo.jpg", 12, 1, "foxyphotos:camera-roll")
        val albumCopy = roll.copy(folderIdentifier = "album-1")
        val albumOnly = LocalMediaItem("asset-2", "clip.mov", 13, 2, "album-1")
        assertEquals(listOf(roll, albumOnly), deduplicateMediaItems(listOf(roll, albumCopy, albumOnly)))
    }

    @Test fun sameFilenameDoesNotCollapseDistinctAssets() {
        val first = LocalMediaItem("asset-1", "IMG_0001.jpg", 12, 1, "album-1")
        val second = first.copy(identifier = "asset-2", folderIdentifier = "album-2")
        assertEquals(listOf(first, second), deduplicateMediaItems(listOf(first, second)))
    }
}
