import CryptoKit
import Foundation
import FoxyPhotosGoogleCore

private struct AlbumJournalState: Codable {
    var mappings: [String: String] = [:]
    var creates: Set<String> = []
    var candidates: Set<String> = []
    var members: Set<String> = []
    var acknowledged: Set<String> = []
    var reservations: [String: Int] = [:]

    init() {}
    private enum CodingKeys: String, CodingKey { case mappings, creates, candidates, members, acknowledged, reservations }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        mappings = try values.decode([String: String].self, forKey: .mappings)
        creates = try values.decode(Set<String>.self, forKey: .creates)
        candidates = try values.decode(Set<String>.self, forKey: .candidates)
        members = try values.decode(Set<String>.self, forKey: .members)
        acknowledged = try values.decodeIfPresent(Set<String>.self, forKey: .acknowledged) ?? []
        reservations = try values.decode([String: Int].self, forKey: .reservations)
    }
}

/// Write-ahead journal. If a remote write loses its response, never replay it blindly.
final class IOSAlbumJournal {
    private let url: URL
    private var state: AlbumJournalState
    private var operations = 0
    private var blocked = false
    private let maxWritesPerPass = 5_000
    private(set) var issue = "Album placement unavailable"

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
        issue = "Album placement unavailable"
        guard !blocked else { issue = "Album writes stopped after a failure"; return false }
        guard !title.isEmpty, title.count <= 500,
              !mediaKey.isEmpty, contentHash.count == 64 else {
            issue = "Invalid album metadata"; return false
        }
        let candidate = Self.digest(folder + "\u{0}" + contentHash)
        // A title collision is ambiguous. Never silently merge distinct albums.
        let matches = albums.filter { $0.title == title }
        guard matches.count <= 1 else { issue = "Duplicate destination album names"; return false }
        let id: String
        if let mapped = state.mappings[folder] {
            guard (matches.count == 1 && matches[0].id == mapped) ||
                  (matches.isEmpty && state.creates.contains(folder)) else {
                issue = "Saved album destination changed"; return false
            }
            id = mapped
        } else if let existing = matches.first {
            guard !state.creates.contains(folder) else {
                issue = "Previous album creation unresolved"; return false
            }
            id = existing.id
            do {
                var updated = state
                updated.mappings[folder] = id
                try persist(updated)
            } catch { issue = "Album journal unavailable"; return false }
        } else {
            guard !state.creates.contains(folder) else {
                issue = "Previous album creation unresolved"; return false
            }
            guard operations < maxWritesPerPass else { issue = "Album write limit reached"; return false }
            do {
                var updated = state
                updated.creates.insert(folder)
                updated.candidates.insert(candidate)
                try persist(updated) // intent before non-idempotent create-with-first-member
                operations += 1
                let created = try await bridge.create(credentialForm: credential, title: title,
                                                      initialMediaKey: mediaKey,
                                                      nowSeconds: Int64(Date().timeIntervalSince1970))
                updated = state
                updated.mappings[folder] = created
                let firstMember = Self.digest(created + "\u{0}" + mediaKey)
                updated.members.insert(firstMember)
                updated.acknowledged.insert(firstMember)
                updated.reservations[created] = 1
                try persist(updated)
                return true // Initial member is in create; never add it again.
            } catch {
                blocked = true
                issue = state.creates.contains(folder) ? "Album creation not confirmed" : "Album journal unavailable"
                return false // creation may have happened; never infer its ID from title
            }
        }
        let member = Self.digest(id + "\u{0}" + mediaKey)
        if state.candidates.contains(candidate) || state.members.contains(member) {
            issue = "Previous album membership unresolved"
            return state.acknowledged.contains(member)
        }
        // Existing albums with unknown count are not safe to append to. New
        // albums have known zero count and a local reservation in this pass.
        let album = albums.first { $0.id == id }
        let reserved = state.reservations[id] ?? 0
        guard operations < maxWritesPerPass else { issue = "Album write limit reached"; return false }
        guard album == nil ? matches.isEmpty && reserved < 20_000
              : bridge.hasCapacity(album: album, reserved: Int32(reserved)) else {
            issue = album?.count == nil && album != nil
                ? "Destination album count unavailable" : "Destination album capacity reached"
            return false
        }
        do {
            var updated = state
            updated.candidates.insert(candidate)
            updated.members.insert(member)
            updated.reservations[id] = reserved + 1
            try persist(updated) // uncertain membership, reserved before request
            operations += 1
            try await bridge.add(credentialForm: credential, albumId: id, mediaKey: mediaKey,
                                 nowSeconds: Int64(Date().timeIntervalSince1970))
            updated = state
            updated.acknowledged.insert(member)
            try persist(updated)
            return true
        } catch {
            blocked = true
            issue = state.members.contains(member) ? "Album membership not confirmed" : "Album journal unavailable"
            return false
        }
    }
}
