import UIKit

/// Keeps a user-started run alive across the foreground→background transition.
///
/// `beginBackgroundTask` grants a bounded slice of extra execution (roughly 30
/// seconds). It is not a long-running background mode and it cannot revive a
/// killed process; it only bridges the moment the user locks the screen or
/// switches apps, which is exactly when a backup or an album listing would
/// otherwise be cut off mid-flight.
///
/// Long transfers need a `BGProcessingTask`, which hands the run to the system
/// scheduler. This type is the cheap, always-available bridge for the run the
/// user just started.
final class BackgroundExecution {
    private let lock = NSLock()
    private var token: UIBackgroundTaskIdentifier = .invalid
    private var expired = false

    /// Starts the task. `onExpire` runs on the main thread when iOS is about to
    /// suspend the app; cancel the work there.
    func begin(name: String, onExpire: @escaping () -> Void) {
        lock.lock()
        expired = false
        guard token == .invalid else { lock.unlock(); return }
        lock.unlock()
        let start = { [weak self] in
            guard let self else { return }
            self.lock.lock()
            guard self.token == .invalid else { self.lock.unlock(); return }
            self.token = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
                guard let self else { return }
                self.lock.lock()
                self.expired = true
                let current = self.token
                self.token = .invalid
                self.lock.unlock()
                if current != .invalid { UIApplication.shared.endBackgroundTask(current) }
                onExpire()
            }
            self.lock.unlock()
        }
        if Thread.isMainThread { start() }
        else { DispatchQueue.main.sync(execute: start) }
    }

    /// True once iOS ended the bridge. Callers use this to tell a system
    /// cancellation apart from an explicit user cancel.
    var didExpire: Bool {
        lock.lock(); defer { lock.unlock() }
        return expired
    }

    func finish() {
        lock.lock()
        let current = token
        token = .invalid
        lock.unlock()
        guard current != .invalid else { return }
        if Thread.isMainThread {
            UIApplication.shared.endBackgroundTask(current)
        } else {
            DispatchQueue.main.sync { UIApplication.shared.endBackgroundTask(current) }
        }
    }
}

/// Serializes a foreground pass and a background pass within one process.
///
/// Both passes write the same durable journals, so only one may run at a time.
/// A `flock` would deny a second lock even when the same process opens the file
/// twice, which would make every background pass fail as `alreadyRunning`.
/// This gate is in-process (iOS apps are single-process) and the background
/// pass waits on it instead of failing.
enum RunCoordinator {
    private static let lock = NSLock()
    private static var active = false

    /// Waits up to `timeout` for the current pass to finish. Returns false when
    /// the wait is cancelled or the timeout elapses.
    static func acquire(timeout: TimeInterval) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while true {
            lock.lock()
            if !active { active = true; lock.unlock(); return true }
            lock.unlock()
            guard Date() < deadline else { return false }
            do {
                try await Task.sleep(nanoseconds: 200_000_000)
            } catch {
                return false
            }
        }
    }

    static func release() {
        lock.lock(); active = false; lock.unlock()
    }
}
