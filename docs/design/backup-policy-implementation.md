# Backup policy implementation notes

The later explicit implementation authorization supersedes the brainstorming-only restriction in backup-policy.md. Checklist status is intentionally left for integration review.

## Requirement mapping

- Gallery individual and multi-item requests: LocalGallery selection controls and viewer Back up action; AndroidBackupPolicy persists an exact URI whitelist. Busy admission is rejected with selection retained. MediaStoreFolderScanner queries only those URIs, including excluded folders, without updating folder preferences.
- Deduplication/progress/outcomes: AndroidBackupWork uses the same portable runBackup, committed-content ledger and Google adapter. Paused requests keep their snapshot across recreation; resumption rechecks confirmed hashes. Shared BackupExecution ownership serializes service and job hosts.
- Automatic selected-folder backup: BackupScheduling registers a persisted 15-minute periodic JobScheduler job only in Modern. BackupJobService executes AndroidBackupWork directly; it never starts a foreground service. Simple may resume a durable existing request, never start a new folder scan from a timer.
- Gates and truthful waiting: portable BackupPolicy evaluates independent Wi-Fi, cellular, metered Wi-Fi, roaming, Data Saver, power saver and charging restrictions. Android reads actual network/power state. UI lists simultaneous reasons; WAITING differs from explicit PAUSED. Admission is checked before each hash and again after hashing, before upload. The current transfer finishes at the provider's safe boundary when a condition changes.
- Mode transition: Simple cancels future periodic/content/eager scheduling while an admitted automatic job may finish. Terminal cleanup removes its remaining schedule.
- Bounded immediate watching: separate nonpersisted MediaStore content-trigger job, rearmed after processing; an eager regular job starts the initial check. Presets are 15 minutes, 1 hour and 4 hours. Window expiry/Stop removes observation. Selected folders and all normal policy checks still apply.
- Exceptions: Back up anyway chooses This batch / 15 minutes / 1 hour / 4 hours, then Current blockers only / All backup conditions, then explicit confirmation listing covered conditions and cellular/roaming cost warning. Covered condition names are frozen. Batch URI snapshots exclude arrivals. Completion, errors or cancellation ends batch exceptions. Timed exceptions are independent of pause/watch timers.
- Pauses: 15 minutes / 1 hour / 4 hours / Until I resume are persisted separately from blockers and exceptions. Resume clears only pause; End override clears only exception; Stop backup cancels the actual owner. Blocker changes cannot erase pause.
- Clocks/restart: persisted UTC creation/duration and monotonic creation/boot count; expiry or a detected backward clock invalidates a timer durably rather than allowing reactivation. Independent inexact timer jobs re-evaluate pending work, and boot restores observation/timers. No exact alarm or battery-exemption request.
- Notification service behavior: existing run-matched cancellation and terminal FGS removal remain. Automatic jobs use in-app progress, not FGS notifications with misleading service actions.

## Verification and limits

Focused JVM/Robolectric tests cover clock rollback/expiry, pause versus independent exceptions, costly network gates, after-hash admission, persisted selected scope, exact URI query boundaries, shared owner serialization, periodic/content job separation and Modern-to-Simple scheduling. Full Gradle check and debug assembly are the local gate. No physical-device background-latency/Doze test or live cloud-library operation is claimed. Android quotas and force-stop apply; relaunch is needed after force-stop. No iOS target, iCloud integration, upstream source reuse, release publication or CI push is included.
