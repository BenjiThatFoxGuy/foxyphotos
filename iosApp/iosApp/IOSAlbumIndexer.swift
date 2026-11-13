import Foundation
import FoxyPhotosGoogleCore

/// Standalone, resumable Google album library refresh.
///
/// Album listing is a long, multi-page job and Google can fail part-way through.
/// The durable cache, the page checkpoint and the library sync token make a
/// refresh resumable, so this runs outside a backup and reports page-level
/// progress instead of blocking the backup UI.
enum IOSAlbumIndexer {
    struct Result {
        let albumCount: Int
        let source: String
    }

    /// Refreshes the cached album library. The cached sync token makes this a
    /// delta refresh, so a repeat run only pulls what changed. A checkpoint left
    /// by an interrupted run is resumed from its cursor instead of restarting.
    ///
    /// There is deliberately no whole-run bound, matching Android: a large
    /// library is many pages, so the per-page bound is the stall guard. A
    /// whole-run cap shorter than a legitimate listing kills it and masks the
    /// real reason. A scheduled background pass is bounded by the processing
    /// task's own expiry instead.
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
            // A checkpoint from an interrupted run resumes from its cursor. A
            // cursor the provider rejects falls back to a full refresh rather
            // than wedging the library forever.
            let checkpoint = cache.loadCheckpoint()
            let listing = try await list(credential: credential, cache: cache, bridge: bridge,
                                         syncToken: checkpoint?.syncToken,
                                         startCursor: checkpoint?.cursor,
                                         initialAlbums: checkpoint?.albums.map { $0.album() } ?? [],
                                         progress: progress, showsUI: showsUI)
            // Only a completed run is cached. Storing a fresh sync token against
            // an older album set would make the next delta refresh skip albums
            // that changed between the two; an interrupted run keeps the old
            // cache and resumes from its checkpoint next time.
            cache.store(albums: listing.albums, syncToken: listing.syncToken, fetchedAt: Date())
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
                            webId: $0.webId) }, fetchedAt: Date()))
                } catch { checkpointError = error }
            })
        if let checkpointError { throw checkpointError }
        return (result.albums, result.syncToken)
    }
}
