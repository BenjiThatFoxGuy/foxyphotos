import Foundation
import FoxyPhotosGoogleCore

/// Standalone, resumable Google album library refresh.
///
/// Album listing is a long, multi-page job and Google can fail part-way through.
/// The durable cache and page checkpoint make an interrupted listing
/// resumable, so it reports page-level progress instead of blocking the backup
/// UI. A full listing starts from page one when there is no checkpoint.
enum IOSAlbumIndexer {
    struct Result {
        let albumCount: Int
        let source: String
    }

    static func hasIncompleteRefresh() -> Bool {
        guard let data = GoogleCredentialStore.load(),
              let credential = String(data: data, encoding: .utf8),
              let journal = try? BackupReceipts(credential: credential) else { return false }
        return GoogleAlbumCache(directory: journal.directory, scope: journal.scope)
            .loadCheckpoint() != nil
    }

    /// Refreshes the cached album library. A checkpoint left by an interrupted
    /// run resumes from its cursor instead of restarting.
    ///
    /// There is deliberately no whole-run bound: a large library is many
    /// pages. The transport's request timeout guards a stalled request;
    /// BGProcessingTask expiry controls each background pass.
    static func refresh(progress: @escaping (String) async -> Void,
                        showsUI: Bool = true) async throws -> Result {
        guard let credentialData = GoogleCredentialStore.load(),
              let credential = String(data: credentialData, encoding: .utf8) else {
            throw BackupFailure.missingCredential
        }
        let journal = try BackupReceipts(credential: credential)
        let cache = GoogleAlbumCache(directory: journal.directory, scope: journal.scope)
        let transport = GoogleURLSessionTransport()
        let bridge = GoogleAlbumBridge(transport: transport)
        let background = BackgroundExecution()
        // Acquire before bridging. Waiting for a running pass must not consume
        // the bridge's bounded execution window.
        guard await RunCoordinator.acquire(timeout: showsUI ? 120 : 900) else { throw BackupFailure.alreadyRunning }
        defer { RunCoordinator.release() }
        if showsUI {
            background.begin(name: "foxyphotos-album-index") { transport.cancelActive() }
            defer { background.finish() }
            AlbumIndexActivity.start(name: "Google album library")
        }
        do {
            // Record intent before the first network request. If the process
            // is suspended on page one there is no cursor yet, but the next
            // foreground launch or BGProcessingTask still knows to resume.
            if cache.loadCheckpoint() == nil {
                try cache.storeCheckpoint(GoogleAlbumCache.Checkpoint(
                    cursor: nil, syncToken: nil, albums: [], completed: false,
                    fetchedAt: Date()))
            }
            if let complete = cache.loadCheckpoint(), complete.completed {
                try cache.storeVerified(albums: complete.albums.map { $0.album() },
                                        syncToken: complete.syncToken, fetchedAt: complete.fetchedAt)
                cache.clearCheckpoint()
                let count = complete.albums.count
                if showsUI { AlbumIndexActivity.end() }
                return Result(albumCount: count, source: "recovered Google album listing (\(count) albums)")
            }
            // An interrupted run resumes from its saved prefix. A transient
            // transport failure retries from the last durable page; auth and
            // malformed responses still surface to the user.
            var listing: (albums: [GoogleAlbum], syncToken: String?)? = nil
            for attempt in 0..<3 {
                do {
                    let checkpoint = cache.loadCheckpoint()
                    listing = try await list(credential: credential, cache: cache, bridge: bridge,
                                             syncToken: checkpoint?.syncToken,
                                             startCursor: checkpoint?.cursor,
                                             initialAlbums: checkpoint?.albums.map { $0.album() } ?? [],
                                             progress: progress, showsUI: showsUI)
                    break
                } catch {
                    if Task.isCancelled || background.didExpire { throw CancellationError() }
                    let reason = error.localizedDescription.lowercased()
                    guard attempt < 2,
                          reason.contains("transport") || reason.contains("timed out") ||
                          reason.contains("network connection") else { throw error }
                    await progress("Connection interrupted. Resuming from the saved album page…")
                    try await Task.sleep(nanoseconds: UInt64(attempt + 1) * 2_000_000_000)
                }
            }
            guard let listing else { throw BackupFailure.alreadyRunning }
            // Only a completed run is cached. Storing a fresh sync token against
            // an older album set would make the next delta refresh skip albums
            // that changed between the two; an interrupted run keeps the old
            // cache and resumes from its checkpoint next time.
            try cache.storeVerified(albums: listing.albums, syncToken: listing.syncToken, fetchedAt: Date())
            cache.clearCheckpoint()
            let source = listing.albums.isEmpty
                ? "fresh Google album listing (empty library)"
                : "fresh Google album listing (\(listing.albums.count) albums)"
            let summary = Result(albumCount: listing.albums.count, source: source)
            if showsUI {
                AlbumIndexActivity.update(phase: "\(summary.albumCount) Google albums cached",
                                          completed: summary.albumCount, total: summary.albumCount,
                                          failed: 0, library: source)
                AlbumIndexActivity.end()
            }
            return summary
        } catch {
            if showsUI { AlbumIndexActivity.end() }
            throw error
        }
    }

    private static func list(credential: String,
                             cache: GoogleAlbumCache,
                             bridge: GoogleAlbumBridge,
                             syncToken: String?,
                             startCursor: String?,
                             initialAlbums: [GoogleAlbum],
                             progress: @escaping (String) async -> Void,
                             showsUI: Bool) async throws -> (albums: [GoogleAlbum], syncToken: String?) {
        var checkpointError: Error?
        let result = try await bridge.listDetailed(
            credentialForm: credential,
            syncToken: syncToken,
            startCursor: startCursor,
            onPage: { page, albumCount, _, _ in
                let phase = "Fetching Google albums… page \(page) (\(albumCount) albums)"
                if showsUI {
                    AlbumIndexActivity.update(phase: phase, completed: Int(page), total: 0,
                                              failed: 0, library: "")
                }
                Task { await progress(phase) }
            },
            initialAlbums: initialAlbums,
            onPageSnapshot: { albums, syncToken, nextCursor in
                // A cursor without its preceding pages makes an incomplete cache.
                // Only advance it once the entire prefix has been saved atomically.
                guard checkpointError == nil else { return }
                do {
                    try cache.storeCheckpoint(GoogleAlbumCache.Checkpoint(
                        cursor: nextCursor, syncToken: syncToken,
                        albums: albums.map { GoogleAlbumCache.CachedAlbum(
                            id: $0.id, title: $0.title, count: $0.count?.int64Value,
                            webId: $0.webId) }, completed: nextCursor == nil,
                        fetchedAt: Date()))
                } catch { checkpointError = error }
            })
        if let checkpointError { throw checkpointError }
        return (result.albums, result.syncToken)
    }
}
