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
    let albumRequests: Int
    let albumUnresolved: Int
    let albumIssues: [String: Int]
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

private struct SelectedBackupAsset {
    let asset: PHAsset
    var albumIds: [String]
}

/// Never use a filename or an asset identifier as an upload identity. Hash the exact original bytes.
private func selectedAssets(_ selected: Set<String>) -> [SelectedBackupAsset] {
    guard !selected.isEmpty else { return [] }
    var positions: [String: Int] = [:]
    var assets: [SelectedBackupAsset] = []
    func append(_ found: PHFetchResult<PHAsset>, source: String) {
        found.enumerateObjects { asset, _, _ in
            guard asset.mediaType == .image || asset.mediaType == .video else { return }
            if let position = positions[asset.localIdentifier] {
                assets[position].albumIds.append(source)
            } else {
                positions[asset.localIdentifier] = assets.count
                assets.append(SelectedBackupAsset(asset: asset, albumIds: [source]))
            }
        }
    }
    if selected.contains("foxyphotos:camera-roll") {
        append(PHAsset.fetchAssets(with: nil), source: "foxyphotos:camera-roll")
    }
    for id in selected.sorted() where id != "foxyphotos:camera-roll" {
        let collections = PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [id], options: nil)
        collections.enumerateObjects { collection, _, _ in
            append(PHAsset.fetchAssets(in: collection, options: nil), source: id)
        }
    }
    return assets
}

/// Picked IDs are resolved through PhotoKit, never through the picker's temporary rendition.
private func pickedAssets(_ identifiers: [String]) throws -> ([SelectedBackupAsset], [String: String]) {
    var assets: [SelectedBackupAsset] = []
    var names: [String: String] = [:]
    var seen: Set<String> = []
    for id in identifiers where seen.insert(id).inserted {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject,
              asset.mediaType == .image || asset.mediaType == .video else { throw BackupFailure.noOriginal }
        var albumIds: [String] = []
        PHAssetCollection.fetchAssetCollectionsContaining(asset, with: .album, options: nil)
            .enumerateObjects { album, _, _ in
                albumIds.append(album.localIdentifier)
                names[album.localIdentifier] = album.localizedTitle ?? "Album"
            }
        if albumIds.isEmpty {
            albumIds = ["foxyphotos:camera-roll"]
            names["foxyphotos:camera-roll"] = "Camera Roll"
        }
        assets.append(SelectedBackupAsset(asset: asset, albumIds: albumIds))
    }
    return (assets, names)
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
    func mediaKey(_ sha256: String) -> String? { items[sha256] }

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

    func run(selected: Set<String>, albumNames: [String: String], pickedIds: [String]? = nil,
             deleteConfirmed: Bool,
             progress: @escaping (Int, Int, Int) async -> Void) async throws -> BackupResult {
        guard let credentialData = GoogleCredentialStore.load(),
              let credential = String(data: credentialData, encoding: .utf8) else {
            throw BackupFailure.missingCredential
        }
        let journal = try BackupReceipts(credential: credential)
        let albumJournal = try IOSAlbumJournal(credential: credential)
        let albumBridge = GoogleAlbumBridge(transport: transport)
        // Read-only album discovery can run while PhotoKit stages the first original.
        async let initialAlbumListing: [GoogleAlbum]? = try? await albumBridge.list(credentialForm: credential)
        var remoteAlbums: [GoogleAlbum]?
        var albumListingLoaded = false
        var retriedAlbumList = false
        let (assets, effectiveNames) = try await Task.detached(priority: .userInitiated) {
            if let pickedIds { return try pickedAssets(pickedIds) }
            return (selectedAssets(selected), albumNames)
        }.value
        let titleCounts = Dictionary(grouping: effectiveNames.values, by: { $0 }).mapValues(\.count)
        var confirmed = 0, skipped = 0, failed = 0, deletionIneligible = 0
        var albumRequests = 0, albumUnresolved = 0
        var albumIssues: [String: Int] = [:]
        func noteAlbumIssue(_ reason: String) {
            albumUnresolved += 1
            albumIssues[reason, default: 0] += 1
        }
        var deletionCandidates: [PHAsset] = []
        let bridge = GoogleUploadBridge(transport: transport)
        for selectedAsset in assets {
            let asset = selectedAsset.asset
            try Task.checkCancellation()
            do {
                let staged = try await stage(asset)
                defer { try? FileManager.default.removeItem(at: staged.url) }
                let mediaKey: String
                if let existing = journal.mediaKey(staged.sha256), !existing.isEmpty {
                    skipped += 1
                    mediaKey = existing
                } else {
                    let receipt = try await bridge.upload(
                        credentialForm: credential, filePath: staged.url.path,
                        fileName: staged.filename, sizeBytes: staged.size,
                        modifiedEpochSeconds: staged.modifiedSeconds, sha1Hex: staged.sha1
                    )
                    try journal.record(staged.sha256, providerKey: receipt.mediaKey)
                    confirmed += 1
                    mediaKey = receipt.mediaKey
                    if deleteConfirmed {
                        if staged.safeToDelete { deletionCandidates.append(asset) }
                        else { deletionIneligible += 1 }
                    }
                }
                if !albumListingLoaded {
                    remoteAlbums = await initialAlbumListing
                    albumListingLoaded = true
                }
                if remoteAlbums == nil && !retriedAlbumList {
                    retriedAlbumList = true
                    remoteAlbums = try? await albumBridge.list(credentialForm: credential)
                }
                for folder in selectedAsset.albumIds {
                    guard let title = effectiveNames[folder] else {
                        noteAlbumIssue("Source album name unavailable")
                        continue
                    }
                    guard titleCounts[title] == 1 else {
                        noteAlbumIssue("Duplicate source album names")
                        continue
                    }
                    guard let remoteAlbums else {
                        noteAlbumIssue("Google album listing unavailable")
                        continue
                    }
                    let acknowledged = await albumJournal.associate(
                        folder: folder, title: title, contentHash: staged.sha256, mediaKey: mediaKey,
                        albums: remoteAlbums, bridge: albumBridge, credential: credential
                    )
                    if acknowledged { albumRequests += 1 }
                    else { noteAlbumIssue(albumJournal.issue) }
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
                                    deletionIneligible: deletionIneligible,
                                    albumRequests: albumRequests, albumUnresolved: albumUnresolved,
                                    albumIssues: albumIssues)
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
                            deletionIneligible: deletionIneligible,
                            albumRequests: albumRequests, albumUnresolved: albumUnresolved,
                            albumIssues: albumIssues)
    }
}
