import BackgroundTasks
import Foundation

/// Background passes for the two long jobs: the Google album library refresh and
/// a user-started backup.
///
/// A Live Activity only displays progress; it does not extend runtime. What makes
/// the pairing work is that an app showing a visible, user-started run is treated
/// more leniently by the scheduler, and the foreground run keeps going through
/// BackgroundExecution's beginBackgroundTask bridge until the processing task
/// takes over.
enum IOSBackgroundJobs {
    static let backupIdentifier = "gay.benjifox.foxyphotos.backup"
    static let indexIdentifier = "gay.benjifox.foxyphotos.album-index"

    /// Must run before the app finishes launching, or the scheduler rejects
    /// every later submission.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: backupIdentifier, using: nil) { task in
            handleBackup(task: task)
        }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: indexIdentifier, using: nil) { task in
            handleIndex(task: task)
        }
    }

    static func scheduleBackup() {
        let request = BGProcessingTaskRequest(identifier: backupIdentifier)
        request.earliestBeginDate = Date()
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        try? BGTaskScheduler.shared.submit(request)
    }

    static func scheduleIndex(earliest: Date = Date().addingTimeInterval(90)) {
        let request = BGProcessingTaskRequest(identifier: indexIdentifier)
        request.earliestBeginDate = earliest
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        try? BGTaskScheduler.shared.submit(request)
    }

    private static func handleIndex(task: BGTask) {
        // Chain the next refresh so the cache stays warm without the app.
        scheduleIndex()
        let work = Task {
            var succeeded = false
            defer { task.setTaskCompleted(success: succeeded) }
            do {
                _ = try await IOSAlbumIndexer.refresh(progress: { _ in }, showsUI: false)
                succeeded = true
            } catch is CancellationError {
                succeeded = false
            } catch {
                succeeded = false
            }
        }
        task.expirationHandler = { work.cancel() }
    }

    private static func handleBackup(task: BGTask) {
        guard PendingBackupStore.load() != nil else {
            task.setTaskCompleted(success: true)
            return
        }
        let work = Task {
            var succeeded = false
            defer { task.setTaskCompleted(success: succeeded) }
            do {
                guard let pending = PendingBackupStore.load() else { return }
                let session = IOSBackupSession()
                _ = try await session.run(selected: Set(pending.albumIds),
                                          albumNames: pending.albumNames,
                                          pickedIds: pending.pickedIds,
                                          deleteConfirmed: pending.deleteConfirmed,
                                          showsUI: false) { _, _, _, _ in }
                PendingBackupStore.clear()
                succeeded = true
            } catch is CancellationError {
                succeeded = false
            } catch {
                succeeded = false
            }
        }
        task.expirationHandler = { work.cancel() }
    }
}
