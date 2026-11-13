import Foundation

/// One original whose upload is confirmed but whose deletion iOS still has to
/// mediate. A background pass cannot present the deletion prompt, so it records
/// the intent here and the foreground pass finishes the deletion.
struct PendingDeletion: Codable, Equatable {
    var entries: [Entry]
    var enqueuedAt: Date

    struct Entry: Codable, Equatable {
        var assetId: String
        /// The original's modification date when it was confirmed, so the
        /// foreground pass can prove it is deleting the same asset. `nil` when
        /// PhotoKit did not report one.
        var modificationDate: Double?
    }
}

enum PendingDeletionStore {
    private static var directory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }

    private static var url: URL { directory.appendingPathComponent("PendingDeletion.json") }

    static func load() -> PendingDeletion? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(PendingDeletion.self, from: data)
    }

    static func append(_ entries: [PendingDeletion.Entry]) {
        guard !entries.isEmpty else { return }
        var current = load()?.entries ?? []
        for entry in entries where !current.contains(where: { $0.assetId == entry.assetId }) {
            current.append(entry)
        }
        save(PendingDeletion(entries: current, enqueuedAt: Date()))
    }

    static func save(_ pending: PendingDeletion) {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        guard let data = try? JSONEncoder().encode(pending) else { return }
        try? data.write(to: url, options: [.atomic, .completeFileProtectionUnlessOpen])
    }

    static func clear() { try? FileManager.default.removeItem(at: url) }
}
