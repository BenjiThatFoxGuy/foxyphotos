import CryptoKit
import Foundation
import FoxyPhotosGoogleCore

private struct AlbumJournalState: Codable {
    var mappings: [String: String] = [:]
    var creates: Set<String> = []
    var candidates: Set<String> = []
    var members: Set<String> = []
    var reservations: [String: Int] = [:]
}

/// Write-ahead journal. If a remote write loses its response, never replay it blindly.
final class IOSAlbumJournal {
    private let url: URL
    private var state: AlbumJournalState
    private var operations = 0

    init(credential: String) throws {
        let scope = Self.digest(credential)
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                    appropriateFor: nil, create: true)
            .appendingPathComponent("BackupReceipts", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        url = directory.appendingPathComponent(scope + ".albums.json")
        if FileManager.default.fileExists(atPath: url.path) {
            state = try JSONDecoder().decode(AlbumJournalState.self, from: Data(contentsOf: url))
        } else { state = AlbumJournalState() }
    }

    static func digest(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private func persist(_ updated: AlbumJournalState) throws {
        try JSONEncoder().encode(updated).write(to: url, options: [.atomic, .completeFileProtectionUnlessOpen])
        state = updated
    }

    /// Acknowledged means a request was accepted, not that remote membership was verified.
    func associate(folder: String, title: String, contentHash: String, mediaKey: String,
                   albums: [GoogleAlbum], bridge: GoogleAlbumBridge, credential: String) async -> Bool {
        guard operations < 20, !title.isEmpty, title.count <= 500,
              !mediaKey.isEmpty, contentHash.count == 64 else { return false }
        let candidate = Self.digest(folder + "\u{0}" + contentHash)
        guard !state.candidates.contains(candidate) else { return false }
        // A title collision is ambiguous. Never silently merge distinct albums.
        let matches = albums.filter { $0.title == title }
        guard matches.count <= 1 else { return false }
        let id: String
        if let mapped = state.mappings[folder] {
            guard (matches.count == 1 && matches[0].id == mapped) ||
                  (matches.isEmpty && state.creates.contains(folder)) else { return false }
            id = mapped
        } else if let existing = matches.first {
            guard !state.creates.contains(folder) else { return false }
            id = existing.id
            do {
                var updated = state
                updated.mappings[folder] = id
                try persist(updated)
            } catch { return false }
        } else {
            guard !state.creates.contains(folder) else { return false }
            do {
                var updated = state
                updated.creates.insert(folder)
                try persist(updated) // intent before non-idempotent create
                operations += 1
                let created = try await bridge.create(credentialForm: credential, title: title,
                                                      nowSeconds: Int64(Date().timeIntervalSince1970))
                updated = state
                updated.mappings[folder] = created
                try persist(updated)
                id = created
            } catch { return false } // creation may have happened; never infer its ID from title
        }
        let member = Self.digest(id + "\u{0}" + mediaKey)
        guard !state.members.contains(member) else { return false }
        // Existing albums with unknown count are not safe to append to. New
        // albums have known zero count and a local reservation in this pass.
        let album = albums.first { $0.id == id }
        let reserved = state.reservations[id] ?? 0
        guard operations < 20,
              (album == nil ? matches.isEmpty && reserved < 20_000
               : bridge.hasCapacity(album: album, reserved: Int32(reserved))) else { return false }
        do {
            var updated = state
            updated.candidates.insert(candidate)
            updated.members.insert(member)
            updated.reservations[id] = reserved + 1
            try persist(updated) // uncertain membership, reserved before request
            operations += 1
            try await bridge.add(credentialForm: credential, albumId: id, mediaKey: mediaKey,
                                 nowSeconds: Int64(Date().timeIntervalSince1970))
            return true
        } catch { return false }
    }
}
