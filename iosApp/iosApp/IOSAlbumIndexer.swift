import Foundation
import FoxyPhotosGoogleCore

/// Standalone, resumable Google album library refresh.
///
/// Album listing is a long, multi-page job and Google can fail part-way through.
/// The durable cache plus the library sync token make a refresh resumable, so
/// this runs outside a backup and reports page-level progress instead of
/// blocking the backup UI.
enum IOSAlbumIndexer {
    struct Result {
        let albumCount: Int
        let source: String
    }

    /// Refreshes the cached album library. The cached sync token makes this a
    /// delta refresh, so a repeat run only pulls what changed.
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
        let cached = cache.load()
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
            let result = try await bridge.listDetailed(credentialForm: credential,
                                                       syncToken: cached?.syncToken,
                                                       onPage: { page, albumCount, _ in
                let phase = "Fetching Google albums… page \(page) (\(albumCount) albums)"
                if showsUI {
                    AlbumIndexActivity.update(phase: phase, completed: Int(page), total: 0,
                                              failed: 0, library: "")
                }
                Task { await progress(phase) }
            },
                                                       pageTimeoutMillis: 180_000)
            // Only a completed run is cached. Storing a fresh sync token against
            // an older album set would make the next delta refresh skip albums
            // that changed between the two; an interrupted run keeps the old
            // cache and does a full refresh next time.
            cache.store(albums: result.albums, syncToken: result.syncToken, fetchedAt: Date())
            let source = result.albums.isEmpty
                ? "fresh Google album listing (empty library)"
                : "fresh Google album listing (\(result.albums.count) albums)"
            let summary = Result(albumCount: result.albums.count, source: source)
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
}
