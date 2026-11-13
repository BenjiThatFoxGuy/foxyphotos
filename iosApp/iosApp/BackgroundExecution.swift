import Darwin
import UIKit

/// Keeps a user-started run alive across the foreground→background transition.
///
/// `beginBackgroundTask` grants a bounded slice of extra execution (roughly 30
/// seconds). It is not a long-running background mode and it cannot revive a
/// killed process; it only bridges the moment the user locks the screen or
/// switches apps, which is exactly when a backup or an album listing would
/// otherwise be cut off mid-flight.
///
/// Long transfers need `URLSessionConfiguration.background`, which hands the
/// transfer to `nsurlsessiond`. This type is the cheap, always-available bridge
/// for the run the user just started.
final class BackgroundExecution {
    private let lock = NSLock()
    private var token: UIBackgroundTaskIdentifier = .invalid

    /// Starts the task. `onExpire` runs on the main thread when iOS is about to
    /// suspend the app; cancel the work there.
    func begin(name: String, onExpire: @escaping () -> Void) {
        lock.lock()
        defer { lock.unlock() }
        guard token == .invalid else { return }
        let start = { [weak self] in
            guard let self, self.token == .invalid else { return }
            self.token = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
                guard let self else { return }
                self.finish()
                onExpire()
            }
        }
        if Thread.isMainThread { start() }
        else { DispatchQueue.main.sync(execute: start) }
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

/// A cross-process exclusive lock. A foreground pass and a background pass must
/// not write the receipts, album journal or album cache at the same time.
final class ProcessLock {
    private let descriptor: Int32
    private var locked = false

    init?(path: String) {
        descriptor = open(path, O_CREAT | O_RDWR, 0o666)
        guard descriptor >= 0 else { return nil }
    }

    deinit { if descriptor >= 0 { close(descriptor) } }

    func lock() -> Bool {
        guard !locked else { return true }
        guard flock(descriptor, LOCK_EX | LOCK_NB) == 0 else { return false }
        locked = true
        return true
    }

    func unlock() {
        guard locked else { return }
        flock(descriptor, LOCK_UN)
        locked = false
    }
}
