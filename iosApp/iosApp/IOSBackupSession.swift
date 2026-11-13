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
    let albumSource: String
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

enum BackupFailure: Error { case unavailable, noOriginal, emptyResource, missingCredential, alreadyRunning, timeout }
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
final class BackupReceipts {
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

    var directory: URL { url.deletingLastPathComponent() }
    var scope: String { url.deletingPathExtension().lastPathComponent }

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
    private let albumTransport = GoogleURLSessionTransport()
    let background = BackgroundExecution()
    func cancel() {
        transport.cancelActive()
        albumTransport.cancelActive()
    }

    private static func stamp(_ date: Date?) -> String {
        guard let date else { return "unknown date" }
        let formatter = DateFormatter()
        formatter.dateStyle = .short
        formatter.timeStyle = .short
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter.string(from: date)
    }

    /// Read-only album discovery can run while PhotoKit stages the first original.
    func run(selected: Set<String>, albumNames: [String: String], pickedIds: [String]? = nil,
             deleteConfirmed: Bool, showsUI: Bool = true,
             onProgress: @escaping (Int, Int, Int, String) async -> Void) async throws -> BackupResult {
        guard let credentialData = GoogleCredentialStore.load(),
              let credential = String(data: credentialData, encoding: .utf8) else {
            throw BackupFailure.missingCredential
        }
        let journal = try BackupReceipts(credential: credential)
        let albumJournal = try IOSAlbumJournal(credential: credential)
        // A foreground pass and a background pass can overlap when the app is
        // suspended. Only one may write the receipts, album journal and cache.
        guard await RunCoordinator.acquire(timeout: showsUI ? 120 : 900) else { throw BackupFailure.alreadyRunning }
        defer { RunCoordinator.release() }
        if showsUI {
            // The user started this run and can see it. Keep it alive across the
            // foreground→background transition, and make it visible.
            background.begin(name: "foxyphotos-backup") { self.cancel() }
            defer { background.finish() }
            AlbumIndexActivity.start(name: "FoxyPhotos backup")
            defer { AlbumIndexActivity.end() }
        }
        let callerProgress = onProgress
        func report(_ completed: Int, _ total: Int, _ failed: Int, _ phase: String) async {
            if showsUI {
                AlbumIndexActivity.update(phase: phase, completed: completed, total: total,
                                          failed: failed, library: "")
            }
            await callerProgress(completed, total, failed, phase)
        }
        let albumBridge = GoogleAlbumBridge(transport: albumTransport)
        let albumCache = GoogleAlbumCache(directory: journal.directory, scope: journal.scope)
        let cachedAlbums = albumCache.load()
        // Read-only album discovery can run while PhotoKit stages the first original.
        async let initialAlbumListing: GoogleAlbumListing? = try? await albumBridge.listDiagnosed(
            credentialForm: credential, syncToken: cachedAlbums?.syncToken
        )
        var remoteAlbums: [GoogleAlbum]? = cachedAlbums?.albums
        var listingIssue: String? = nil
        var albumSource = cachedAlbums.map { snapshot in
            "cached Google albums (\(snapshot.albums.count)) from \(Self.stamp(snapshot.fetchedAt))"
        } ?? "no album library"
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
        for (index, selectedAsset) in assets.enumerated() {
            let asset = selectedAsset.asset
            try Task.checkCancellation()
            await report(confirmed + skipped + failed, assets.count, failed,
                           "Fetching original \(index + 1) of \(assets.count)…")
            do {
                let staged = try await stage(asset)
                defer { try? FileManager.default.removeItem(at: staged.url) }
                await report(confirmed + skipped + failed, assets.count, failed,
                               "Confirming original \(index + 1) of \(assets.count)…")
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
                await report(confirmed + skipped + failed, assets.count, failed,
                               "Finding matching Google albums…")
                if !albumListingLoaded {
                    let listing = await initialAlbumListing
                    albumListingLoaded = true
                    if let albums = listing?.albums {
                        remoteAlbums = albums
                        listingIssue = nil
                        albumSource = "fresh Google album listing (\(albums.count) albums)"
                        albumCache.store(albums: albums,
                                         syncToken: listing?.syncToken ?? cachedAlbums?.syncToken,
                                         fetchedAt: Date())
                    } else {
                        // Listing failed: placement still works from the durable cache.
                        remoteAlbums = cachedAlbums?.albums
                        listingIssue = listing?.issue ?? "Google album listing unavailable"
                        if cachedAlbums != nil { albumSource = "cached Google albums" }
                    }
                }
                if remoteAlbums == nil && !retriedAlbumList &&
                    (listingIssue?.hasPrefix("Google listing Provider returned HTTP 5") == true ||
                     listingIssue?.hasPrefix("Google listing Provider transport failed") == true) {
                    retriedAlbumList = true
                    let listing = try? await albumBridge.listDiagnosed(
                        credentialForm: credential, syncToken: cachedAlbums?.syncToken
                    )
                    if let albums = listing?.albums {
                        remoteAlbums = albums
                        listingIssue = nil
                        albumSource = "fresh Google album listing (\(albums.count) albums)"
                        albumCache.store(albums: albums,
                                         syncToken: listing?.syncToken ?? cachedAlbums?.syncToken,
                                         fetchedAt: Date())
                    } else {
                        listingIssue = listing?.issue ?? listingIssue
                    }
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
                        noteAlbumIssue(listingIssue ?? "Google album listing unavailable")
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
            await report(confirmed + skipped + failed, assets.count, failed,
                           "Checked \(confirmed + skipped + failed) of \(assets.count) · \(failed) failed")
        }
        var deleted = false
        var deletionRequested = 0
        if deleteConfirmed && !deletionCandidates.isEmpty {
            try Task.checkCancellation()
            if showsUI {
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
                deletionRequested = deletionCandidates.count
                if !deletionCandidates.isEmpty {
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
            } else {
                // A background pass cannot present iOS's deletion prompt. Record the
                // intent and tell the user; the foreground finishes it.
                deletionRequested = deletionCandidates.count
                let entries = deletionCandidates.map {
                    PendingDeletion.Entry(assetId: $0.localIdentifier,
                                          modificationDate: $0.modificationDate?.timeIntervalSince1970)
                }
                PendingDeletionStore.append(entries)
                DeletionNotifier.notify(entries: entries.count)
            }
        }
        if showsUI {
            let carried = await Self.finishPendingDeletions()
            deletionIneligible += carried.ineligible
            if carried.deleted > 0 {
                deleted = true
                deletionRequested += carried.deleted
            }
        }
        return BackupResult(confirmed: confirmed, skipped: skipped, failed: failed,
                            deletionRequested: deletionRequested, deletionSucceeded: deleted,
                            deletionIneligible: deletionIneligible,
                            albumRequests: albumRequests, albumUnresolved: albumUnresolved,
                            albumIssues: albumIssues, albumSource: albumSource)
    }

    /// Finishes deletions a background pass could not perform. iOS mediates each
    /// deletion with a prompt, which cannot be presented from a background pass,
    /// so the intent is recorded and completed here.
    ///
    /// Every asset is rechecked immediately before the destructive transaction:
    /// an asset edited or expanded while its original uploaded is not the
    /// original that was confirmed.
    static func finishPendingDeletions() async -> (deleted: Int, ineligible: Int) {
        guard let pending = PendingDeletionStore.load(), !pending.entries.isEmpty else { return (0, 0) }
        var eligible: [PHAsset] = []
        var ineligible = 0
        for entry in pending.entries {
            guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [entry.assetId], options: nil).firstObject else {
                // Already gone; nothing to delete.
                continue
            }
            if let stored = entry.modificationDate {
                guard let current = asset.modificationDate,
                      abs(current.timeIntervalSince1970 - stored) <= 1 else {
                    ineligible += 1
                    continue
                }
            }
            if PHAssetResource.assetResources(for: asset).count != 1
                || asset.mediaSubtypes.contains(.photoLive) {
                ineligible += 1
            } else {
                eligible.append(asset)
            }
        }
        guard !eligible.isEmpty else {
            PendingDeletionStore.clear()
            return (0, ineligible)
        }
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                PHPhotoLibrary.shared().performChanges({
                    PHAssetChangeRequest.deleteAssets(eligible as NSArray)
                }) { success, error in
                    if success { continuation.resume() }
                    else { continuation.resume(throwing: error ?? BackupFailure.unavailable) }
                }
            }
        } catch {
            // Keep the intent so a later foreground pass can retry.
            return (0, ineligible)
        }
        PendingDeletionStore.clear()
        return (eligible.count, ineligible)
    }
}

extension BackupFailure {
    /// A readable reason. The raw `NSError` code (0-5) tells the user nothing.
    var message: String {
        switch self {
        case .unavailable: return "The Google provider is unavailable."
        case .noOriginal: return "No original is available for this asset."
        case .emptyResource: return "The original resource is empty."
        case .missingCredential: return "The Google credential is missing."
        case .alreadyRunning: return "Another pass is already running."
        case .timeout: return "The album listing took too long."
        }
    }
}
