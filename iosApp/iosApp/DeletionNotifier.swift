import Foundation
import Photos

/// Requests deletion of confirmed originals.
///
/// iOS mediates every photo-library deletion: the system presents its own
/// prompt ("FoxyPhotos wants to delete N photos") and the user approves or
/// declines. The prompt is surfaced by iOS itself, so the app does not post a
/// custom notification and does not need to be foregrounded to ask.
enum DeletionPrompt {
    /// Requests deletion of `assets`. Returns the number iOS actually removed.
    ///
    /// iOS presents its own confirmation. A decline or failure removes nothing.
    static func request(_ assets: [PHAsset]) async -> Int {
        guard !assets.isEmpty else { return 0 }
        return await withCheckedContinuation { continuation in
            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.deleteAssets(assets as NSArray)
            }, completionHandler: { success, _ in
                continuation.resume(returning: success ? assets.count : 0)
            })
        }
    }
}
