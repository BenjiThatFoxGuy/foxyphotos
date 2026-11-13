import ActivityKit
import Foundation

/// Compiled into both the app and the Live Activity extension so the payload
/// stays binary-compatible between them. Never put credential material here:
/// the state crosses a process boundary and lands on the Lock Screen.
struct AlbumIndexAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var phase: String
        var completed: Int
        var total: Int
        var failed: Int
        var library: String
        var startedAt: Date
    }

    var name: String
}
