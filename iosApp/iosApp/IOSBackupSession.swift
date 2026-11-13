import CryptoKit
import Foundation
import Photos
import FoxyPhotosGoogleCore

struct BackupResult {
    let confirmed: Int
    let skipped: Int
    let failed: Int
    let deletionRequested: Int
    let deletionSucceeded: Bool
    let deletionIneligible: Int
}

private struct StagedAsset {
    let url: URL
    let filename: String
    let size: Int64
    let sha1: String
    let sha256: String
    let modifiedSeconds: Int64
    let safeToDelete: Bool
}

private enum BackupFailure: Error { case unavailable, noOriginal, emptyResource, missingCredential }

/// Never use a filename or an asset identifier as an upload identity. Hash the exact original bytes.
private func selectedAssets(_ selected: Set<String>) -> [PHAsset] {
    guard !selected.isEmpty else { return [] }
    var seen = Set<String>()
    var assets: [PHAsset] = []
    func append(_ found: PHFetchResult<PHAsset>) {
        found.enumerateObjects { asset, _, _ in
            guard asset.mediaType == .image || asset.mediaType == .video else { return }
            if seen.insert(asset.localIdentifier).inserted { assets.append(asset) }
        }
    }
    if selected.contains("foxyphotos:camera-roll") { append(PHAsset.fetchAssets(with: nil)) }
    for id in selected.sorted() where id != "foxyphotos:camera-roll" {
        let collections = PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [id], options: nil)
        collections.enumerateObjects { collection, _, _ in append(PHAsset.fetchAssets(in: collection, options: nil)) }
    }
    return assets
}

private func stage(_ asset: PHAsset) async throws -> StagedAsset {
    let resources = PHAssetResource.assetResources(for: asset)
    let wanted: [PHAssetResourceType] = asset.mediaType == .video
        ? [.video, .fullSizeVideo] : [.photo, .fullSizePhoto]
    guard let resource = wanted.compactMap({ type in resources.first { $0.type == type } }).first else {
        throw BackupFailure.noOriginal
    }
    // Live Photos, edits and multi-resource assets contain more than this staged
    // original. Upload the primary resource, but never offer deleting the asset.
    let safeToDelete = resources.count == 1 && !asset.mediaSubtypes.contains(.photoLive)
    let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    do {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let options = PHAssetResourceRequestOptions()
            options.isNetworkAccessAllowed = true // A device-visible iCloud-backed Camera Roll asset may need its original.
            PHAssetResourceManager.default().writeData(for: resource, toFile: file, options: options) { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume() }
            }
        }
        return try await Task.detached(priority: .utility) {
            let input = try FileHandle(forReadingFrom: file)
            defer { try? input.close() }
            var sha1 = Insecure.SHA1()
            var sha256 = SHA256()
            var size: Int64 = 0
            while let bytes = try input.read(upToCount: 128 * 1024), !bytes.isEmpty {
                try Task.checkCancellation()
                sha1.update(data: bytes)
                sha256.update(data: bytes)
                size += Int64(bytes.count)
            }
            guard size > 0 else { throw BackupFailure.emptyResource }
            return StagedAsset(
                url: file,
                filename: URL(fileURLWithPath: resource.originalFilename).lastPathComponent,
                size: size,
                sha1: sha1.finalize().map { String(format: "%02x", $0) }.joined(),
                sha256: sha256.finalize().map { String(format: "%02x", $0) }.joined(),
                modifiedSeconds: Int64((asset.modificationDate ?? asset.creationDate ?? Date()).timeIntervalSince1970),
                safeToDelete: safeToDelete
            )
        }.value
    } catch {
        try? FileManager.default.removeItem(at: file)
        throw error
    }
}

/// An atomic, credential-scoped receipt journal. Missing or corrupt journals never imply success.
private final class BackupReceipts {
    private let url: URL
    private var items: [String: String]

    init(credential: String) throws {
        let fingerprint = SHA256.hash(data: Data(credential.utf8)).map { String(format: "%02x", $0) }.joined()
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                    appropriateFor: nil, create: true)
            .appendingPathComponent("BackupReceipts", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        url = directory.appendingPathComponent(fingerprint + ".json")
        if FileManager.default.fileExists(atPath: url.path) {
            items = try JSONDecoder().decode([String: String].self, from: Data(contentsOf: url))
        } else { items = [:] }
    }

    func contains(_ sha256: String) -> Bool { items[sha256] != nil }

    func record(_ sha256: String, providerKey: String) throws {
        guard !providerKey.isEmpty else { throw BackupFailure.unavailable }
        var updated = items
        updated[sha256] = providerKey
        let data = try JSONEncoder().encode(updated)
        try data.write(to: url, options: [.atomic, .completeFileProtectionUnlessOpen])
        items = updated // Mark confirmed only after durable atomic write succeeds.
    }
}

/// Keeps source deletion in the same run, after provider confirmation AND durable receipt.
final class IOSBackupSession {
    private let transport = GoogleURLSessionTransport()
    func cancel() { transport.cancelActive() }

    func run(selected: Set<String>, deleteConfirmed: Bool,
             progress: @escaping (Int, Int, Int) async -> Void) async throws -> BackupResult {
        guard let credentialData = GoogleCredentialStore.load(),
              let credential = String(data: credentialData, encoding: .utf8) else {
            throw BackupFailure.missingCredential
        }
        let journal = try BackupReceipts(credential: credential)
        let assets = await Task.detached(priority: .userInitiated) { selectedAssets(selected) }.value
        var confirmed = 0, skipped = 0, failed = 0, deletionIneligible = 0
        var deletionCandidates: [PHAsset] = []
        let bridge = GoogleUploadBridge(transport: transport)
        for asset in assets {
            try Task.checkCancellation()
            do {
                let staged = try await stage(asset)
                defer { try? FileManager.default.removeItem(at: staged.url) }
                if journal.contains(staged.sha256) {
                    skipped += 1
                } else {
                    let receipt = try await bridge.upload(
                        credentialForm: credential, filePath: staged.url.path,
                        fileName: staged.filename, sizeBytes: staged.size,
                        modifiedEpochSeconds: staged.modifiedSeconds, sha1Hex: staged.sha1
                    )
                    try journal.record(staged.sha256, providerKey: receipt.mediaKey)
                    confirmed += 1
                    if deleteConfirmed {
                        if staged.safeToDelete { deletionCandidates.append(asset) }
                        else { deletionIneligible += 1 }
                    }
                }
            } catch is CancellationError { throw CancellationError() }
            catch { failed += 1 } // A failed or uncertain item never enters cleanup.
            await progress(confirmed + skipped + failed, assets.count, failed)
        }
        var deleted = false
        if deleteConfirmed && !deletionCandidates.isEmpty {
            try Task.checkCancellation()
            // Recheck just before the destructive transaction. An asset edited or
            // expanded while its original uploaded is not the original we confirmed.
            let safeCandidates = deletionCandidates.compactMap { asset -> PHAsset? in
                guard let current = PHAsset.fetchAssets(withLocalIdentifiers: [asset.localIdentifier], options: nil).firstObject,
                      current.modificationDate == asset.modificationDate,
                      PHAssetResource.assetResources(for: current).count == 1,
                      !current.mediaSubtypes.contains(.photoLive) else { return nil }
                return current
            }
            deletionIneligible += deletionCandidates.count - safeCandidates.count
            deletionCandidates = safeCandidates
            guard !deletionCandidates.isEmpty else {
                return BackupResult(confirmed: confirmed, skipped: skipped, failed: failed,
                                    deletionRequested: 0, deletionSucceeded: false,
                                    deletionIneligible: deletionIneligible)
            }
            do {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    PHPhotoLibrary.shared().performChanges({
                        PHAssetChangeRequest.deleteAssets(deletionCandidates as NSArray)
                    }) { success, error in
                        if success { continuation.resume() }
                        else { continuation.resume(throwing: error ?? BackupFailure.unavailable) }
                    }
                }
                deleted = true
            } catch { /* Receipts remain durable; surface failed deletion separately. */ }
        }
        return BackupResult(confirmed: confirmed, skipped: skipped, failed: failed,
                            deletionRequested: deletionCandidates.count, deletionSucceeded: deleted,
                            deletionIneligible: deletionIneligible)
    }
}
