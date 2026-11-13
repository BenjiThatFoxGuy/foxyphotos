import Foundation

/// Credential-scoped, durable Google album library.
///
/// Listing the whole library is slow and can fail. A cached copy lets album
/// placement proceed on a later run and seeds the next delta refresh with the
/// library sync token captured by the last successful listing.
struct GoogleAlbumCache {
    private let fileURL: URL
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(directory: URL, scope: String) {
        fileURL = directory.appendingPathComponent("\(scope).google-albums.json")
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.withoutEscapingSlashes]
        decoder.dateDecodingStrategy = .iso8601
    }

    struct CachedAlbum: Codable, Equatable {
        let id: String
        let title: String
        let count: Int64?
        let webId: String?

        func album() -> GoogleAlbum { GoogleAlbum(id: id, title: title, count: count, webId: webId) }
    }

    struct State: Codable, Equatable {
        var albums: [CachedAlbum] = []
        var syncToken: String? = nil
        var fetchedAt: Date? = nil
        var complete: Bool = false
    }

    struct Snapshot {
        let albums: [GoogleAlbum]
        let syncToken: String?
        let fetchedAt: Date?
    }

    /// The last complete listing. An empty library is a valid library: only a
    /// missing or unfinished cache returns nil.
    func load() -> Snapshot? {
        guard let data = try? Data(contentsOf: fileURL),
              let state = try? decoder.decode(State.self, from: data),
              state.complete else { return nil }
        return Snapshot(albums: state.albums.map(\.album), syncToken: state.syncToken, fetchedAt: state.fetchedAt)
    }

    func store(albums: [GoogleAlbum], syncToken: String?, fetchedAt: Date) {
        let state = State(
            albums: albums.map { CachedAlbum(id: $0.id, title: $0.title, count: $0.count, webId: $0.webId) },
            syncToken: syncToken,
            fetchedAt: fetchedAt,
            complete: true
        )
        guard let data = try? encoder.encode(state) else { return }
        try? data.write(to: fileURL, options: [.atomic, .completeFileProtectionUnlessOpen])
    }

    func clear() { try? FileManager.default.removeItem(at: fileURL) }
}
