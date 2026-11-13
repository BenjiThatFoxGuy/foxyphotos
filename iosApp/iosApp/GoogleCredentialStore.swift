import Foundation
import Security

/// Imported Android Google Photos auth form. Stored only in this device's Keychain.
/// No credential bytes are persisted in UserDefaults or included in logs.
enum GoogleCredentialStore {
    private static let service = "gay.benjifox.foxyphotos.google-native"
    private static let account = "google-native"
    private static let maximumBytes = 64 * 1024
    private static let requiredFields: Set<String> = [
        "androidId", "app", "client_sig", "Email", "Token", "lang", "service"
    ]

    static var isConfigured: Bool {
        var query: [String: Any] = baseQuery
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        return SecItemCopyMatching(query as CFDictionary, nil) == errSecSuccess
    }

    static func importFile(_ url: URL) throws {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        guard let data = try handle.read(upToCount: maximumBytes + 1),
              !data.isEmpty, data.count <= maximumBytes,
              let raw = String(data: data, encoding: .utf8) else {
            throw ImportError.invalidFile
        }
        // This is a form payload, not a bearer token. Validate shape without
        // displaying or recording any field value; the shared parser validates
        // the contents again before an auth request is ever made.
        let fields = raw.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: "&")
        var present = Set<String>()
        for field in fields {
            guard let separator = field.firstIndex(of: "="), separator != field.startIndex,
                  field.index(after: separator) != field.endIndex else { throw ImportError.invalidFile }
            present.insert(String(field[..<separator]))
        }
        guard requiredFields.isSubset(of: present) else { throw ImportError.invalidFile }
        try store(data)
    }

    static func load() -> Data? {
        var query: [String: Any] = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    private static func store(_ data: Data) throws {
        let status = SecItemUpdate(baseQuery as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else { throw ImportError.keychainUnavailable }
        var add = baseQuery
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else {
            throw ImportError.keychainUnavailable
        }
    }

    private static var baseQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    enum ImportError: Error { case invalidFile, keychainUnavailable }
}
