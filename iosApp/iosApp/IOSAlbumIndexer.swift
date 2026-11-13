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
        background.begin(name: "foxyphotos-album-index") { transport.cancelActive() }
        defer { background.finish() }
        if showsUI { AlbumIndexActivity.start(name: "Google album library") }
        let startedAt = Date()
        do {
            // Bounded: a stalled Google request must not hang the refresh.
            let timeout = Task {
                try? await Task.sleep(nanoseconds: 45 * 1_000_000_000)
                transport.cancelActive()
            }
            defer { timeout.cancel() }
            let result = try await bridge.listDetailed(credentialForm: credential,
                                                       syncToken: cached?.syncToken,
                                                       onPage: { page, albumCount in
                let phase = "Fetching Google albums… page \(page) (\(albumCount) albums)"
                if showsUI {
                    AlbumIndexActivity.update(phase: phase, completed: Int(page), total: 0,
                                              failed: 0, library: "")
                }
                Task { await progress(phase) }
            })
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
            // A cancelled transport after the bound elapsed means a stalled
            // listing, which is a different failure from a transport error.
            if Date().timeIntervalSince(startedAt) >= 44 { throw BackupFailure.timeout }
            throw error
        }
    }
}
