import Foundation

/// The selection a user started backing up, persisted so a background pass can
/// finish it when iOS suspends the foreground run.
///
/// Receipts and the album ledger are durable and idempotent, so a foreground and
/// a background pass can overlap without uploading the same original twice.
struct PendingBackup: Codable, Equatable {
    var albumIds: [String]
    var albumNames: [String: String]
    var pickedIds: [String]?
    var deleteConfirmed: Bool
    var enqueuedAt: Date
}

enum PendingBackupStore {
    private static var directory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }

    private static var url: URL { directory.appendingPathComponent("PendingBackup.json") }

    static func save(_ pending: PendingBackup) {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        guard let data = try? JSONEncoder().encode(pending) else { return }
        try? data.write(to: url, options: [.atomic, .completeFileProtectionUnlessOpen])
    }

    static func load() -> PendingBackup? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(PendingBackup.self, from: data)
    }

    static func clear() { try? FileManager.default.removeItem(at: url) }
}
