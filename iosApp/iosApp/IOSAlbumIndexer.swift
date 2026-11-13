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
            let listing: (albums: [GoogleAlbum], syncToken: String?)
            if let cursor = checkpoint?.cursor {
                do {
                    listing = try await list(credential: credential, cache: cache, bridge: bridge,
                                             syncToken: nil, startCursor: cursor,
                                             progress: progress, showsUI: showsUI)
                } catch {
                    cache.clearCheckpoint()
                    listing = try await list(credential: credential, cache: cache, bridge: bridge,
                                             syncToken: cache.load()?.syncToken, startCursor: nil,
                                             progress: progress, showsUI: showsUI)
                }
            } else {
                listing = try await list(credential: credential, cache: cache, bridge: bridge,
                                         syncToken: cache.load()?.syncToken, startCursor: nil,
                                         progress: progress, showsUI: showsUI)
            }
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
                             progress: @escaping (String) async -> Void,
                             showsUI: Bool) async throws -> (albums: [GoogleAlbum], syncToken: String?) {
        let result = try await bridge.listDetailed(
            credentialForm: credential,
            syncToken: syncToken,
            startCursor: startCursor,
            onPage: { page, albumCount, syncToken, nextCursor in
                // Durable after every page: a suspended app must not throw away
                // the pages it already read.
                cache.storeCheckpoint(GoogleAlbumCache.Checkpoint(
                    cursor: nextCursor, syncToken: syncToken, fetchedAt: Date()))
                let phase = "Fetching Google albums… page \(page) (\(albumCount) albums)"
                if showsUI {
                    AlbumIndexActivity.update(phase: phase, completed: Int(page), total: 0,
                                              failed: 0, library: "")
                }
                Task { await progress(phase) }
            })
        return (result.albums, result.syncToken)
    }
}
