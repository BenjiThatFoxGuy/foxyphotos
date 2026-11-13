import ActivityKit
import Foundation

/// Lock Screen / Dynamic Island surface for a running backup or album index.
///
/// A Live Activity only displays progress. It does not keep the app alive and it
/// cannot be updated while the app is suspended, so it freezes at its last state
/// if the process is killed. BackgroundExecution owns the actual run time; this
/// just makes the run visible while it is alive.
enum AlbumIndexActivity {
    private static var current: Activity<AlbumIndexAttributes>?

    /// Must be called while the app is in the foreground: iOS rejects a Live
    /// Activity start from a suspended app.
    static func start(name: String) {
        Task { @MainActor in
            guard ActivityAuthorizationInfo().areActivitiesEnabled, current == nil else { return }
            let attributes = AlbumIndexAttributes(name: name)
            let state = AlbumIndexAttributes.ContentState(
                phase: "Starting…", completed: 0, total: 0, failed: 0,
                library: "", startedAt: Date()
            )
            do { current = try await Activity.request(attributes: attributes, contentState: state) }
            catch { current = nil }
        }
    }

    static func update(phase: String, completed: Int, total: Int, failed: Int, library: String) {
        Task { @MainActor in
            guard let activity = current else { return }
            let state = AlbumIndexAttributes.ContentState(
                phase: phase, completed: completed, total: total, failed: failed,
                library: library, startedAt: activity.contentState.startedAt
            )
            await activity.update(using: state)
        }
    }

    /// Ends the activity. Called on completion and on cancellation: a frozen
    /// activity on the Lock Screen is worse than none.
    static func end() {
        Task { @MainActor in
            guard let activity = current else { return }
            let state = activity.contentState
            await activity.end(using: state, dismissalPolicy: .immediate)
            current = nil
        }
    }
}
