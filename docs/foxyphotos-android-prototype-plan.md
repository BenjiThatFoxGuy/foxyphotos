# FoxyPhotos Android prototype — implementation plan

**Status:** Ready to hand to an agent working in the Android repository
**Prepared:** 2026-11-04
**Goal:** Ship a trustworthy, daily-use Android backup prototype now, while preserving the data model and boundaries needed for FoxyPhotos, an optional Hub, and later iCloud support.

## 1. Product decision

Build an Android-first FoxyPhotos app that backs up local photos and videos directly to one Google Photos account. It is a real backup tool, not a demo: it scans the local library, retains a durable upload queue across reboots, reports truthful progress and failures, and avoids uploading the same content again where the provider can confirm it already has it.

The initial release deliberately does **not** implement a Hub, cross-device catalogue, Google Photos browsing, deletion, or iCloud access. It does, however, use a content-plus-destination queue model so all of those can be added without rewriting the backup engine.

### Frozen choices for this prototype

| Area | Decision |
|---|---|
| Platform | Android 10+ (`minSdk 29`), target the current stable Android SDK when implementing. |
| UI | Kotlin + Jetpack Compose + Material 3. Use dynamic Material color when the device supports it, with a Foxy fallback color scheme. |
| App structure | One native Android app with Gradle feature/core modules. No Electron, React Native, KMP, Rust, Go Mobile, or separate backend in this prototype. |
| Persistence | Room for durable product state, DataStore for preferences, Android Keystore-backed encryption for provider credentials. |
| Background work | WorkManager for durable scans and scheduled uploads; a user-visible `dataSync` foreground run only for an explicitly user-started long backup. |
| Google destination | A Kotlin implementation of the upload flow evidenced by bclone's `gotohp` backend. Treat bclone as the behavioral reference and provenance source, not as a binary to embed or shell out to. |
| Default destination | One Google Photos album named `FoxyPhotos`, created or resolved lazily on first successful upload. The user can change this during setup. |
| Quality | Original quality only in v0.1. Storage Saver may be added as an explicit later setting. |
| Canonical content ID | SHA-256 of the original byte stream. SHA-1 is also calculated when required by the Google adapter's provider-specific duplicate check. |
| Concurrency | One Google transfer at a time by default. Make it a single constant in the scheduler so it can be raised after measured testing. |

This avoids premature cross-platform FFI and Go runtime work, while keeping the boundary where a Rust shared core can later replace the pure queue/catalog code. Kotlin is confined to Android UI, storage, permissions, and the first provider adapter; its on-device runtime is Android's normal ART/DEX environment.

## 2. Scope and user-visible behaviour

### In scope

1. Browse the local on-device photo and video library from `MediaStore`.
2. Select either **all device media** for continuous backup or a selected set for one-off/manual backup.
3. Connect one direct Google Photos destination with an explicitly imported, user-managed credential.
4. Create/resolve the configured album and upload originals to it.
5. Scan incrementally, hash content, deduplicate locally and against the provider's content-hash check, and retain an on-device queue.
6. Resume queued work after the app is killed, the phone restarts, connectivity changes, or a worker times out.
7. Show a local Library, a Backup queue/status screen, and Settings. Use plain text states such as `Queued`, `Uploading`, `Uploaded`, `Needs attention`, and `Could not confirm`; do not use a checkmark as a generic backup-status icon.
8. Provide `Back up now`, `Pause`, `Retry failed`, `View reasons`, and `Disconnect Google Photos` actions.

### Explicitly out of scope

- A hosted service, account system, Hub pairing UI, LAN discovery, or remote catalogue.
- Downloading, deleting, or editing anything in Google Photos.
- Treating a Google Photos library listing as the source of truth for backup state.
- Direct iCloud access, importing Apple credentials, or promising iCloud upload. The current bclone iCloud Photos implementation is a useful later read/catalogue reference, but its documented upload limitation means it is not a v0.1 backup destination.
- Multi-account Google switching, shared albums, Storage Saver, RAW conversion, or local media editing.
- Automatic capture/extraction of Google credentials. The app only accepts an explicit credential the user imports after an informed-consent screen.

## 3. bclone reference boundary

The agent must read the following code and documentation before writing the Google adapter:

- [gotohp behaviour and limitations](https://github.com/benjithatfoxguy/bclone/blob/main/docs/content/gotohp.md)
- [gotohp backend and queue/cache behaviour](https://github.com/benjithatfoxguy/bclone/blob/main/backend/gotohp/gotohp.go)
- [Google upload client](https://github.com/benjithatfoxguy/bclone/blob/main/backend/gotohp/api/client.go)
- [protobuf request/response encoding](https://github.com/benjithatfoxguy/bclone/blob/main/backend/gotohp/api/protobuf.go)
- [iCloud Photos backend documentation](https://github.com/benjithatfoxguy/bclone/blob/main/docs/content/iclouddrive.md)

The observed Google flow is: obtain/refresh a bearer token from the user-provided credential, query the provider's SHA-1 hash index, obtain an upload token, stream the original bytes, commit the media item with its filename and capture timestamp, then create/resolve or associate the configured album. The bclone client uses shared throttle/back-off for 429 and transient 5xx responses. Mirror that *behaviour* in a small, testable Kotlin adapter.

Do not copy bclone wholesale into the app. Do not use its remote-listing path as a correctness dependency: bclone describes the integration as unofficial and write-only, while its newer code has experimental listing support. The app's own queue and local database remain authoritative for its attempts and outcomes. If source is copied verbatim, retain the original MIT licence notice and record the exact upstream path and commit in `THIRD_PARTY_NOTICES.md`.

## 4. Repository layout

Adapt names to the existing repository, but preserve these responsibilities. Do not create a second repository.

```text
app/                         Android application, DI composition, navigation, manifest
core:model/                  Kotlin value types, IDs, enums, provider-neutral contracts
core:database/               Room entities, DAOs, migrations, transaction helpers
core:media/                  MediaStore queries, permission state, metadata extraction, thumbnails
core:backup/                 Scan/reconcile use cases, hashing, queue state machine, scheduling policy
core:security/               Keystore encryption and credential references; no provider protocol
provider:contract/           DestinationAdapter and destination capability interfaces
provider:google-native/      Kotlin port of the bounded gotohp upload behaviour
feature:library/             Compose library screen and library ViewModel
feature:backup/              Compose queue/status screen and backup ViewModel
feature:settings/            Setup, permission, destination, and scheduling settings
test:fake-provider/          Deterministic in-memory destination adapter for app/core tests
docs/                        ADRs, protocol provenance, manual QA checklist
```

Keep `provider:google-native` from importing Compose, Room, or Android UI types. It may depend on small Android-compatible abstractions for encrypted credential retrieval and an `InputStream` source. Keep `core:model` free of Android framework imports so it can later become the starting point for a Rust core and a versioned wire contract.

## 5. Canonical model and Room schema

Use UUID strings for app-owned IDs. Use UTC `Instant` values. Every status transition is written transactionally before UI state is emitted.

### `media_item`

| Field | Meaning |
|---|---|
| `id` | App-generated primary key. |
| `media_store_id`, `content_uri` | Current local locator; URI is never treated as globally stable identity. |
| `generation_modified`, `date_modified_ms` | MediaStore change detection. |
| `display_name`, `mime_type`, `size_bytes`, `date_taken_ms`, `duration_ms`, `width`, `height`, `relative_path` | Read-only local metadata for UI and upload metadata. |
| `sha256_hex` | Canonical content identity once calculated. |
| `sha1_hex` | Google-specific hash check input, calculated in the same stream pass as SHA-256. |
| `source_state` | `AVAILABLE`, `MISSING`, `PERMISSION_REVOKED`, or `UNSUPPORTED`. |
| `last_scanned_at` | Last successful metadata reconciliation. |

### `destination`

`id`, `kind` (`GOOGLE_NATIVE`, future `HUB`, future `ICLOUD_PHOTOS`), display name, enabled flag, album routing mode, album name/opaque provider ID, network policy, and credential-key alias. Store no raw credential in Room.

### `backup_task`

This is the enduring product abstraction: **one content item can have many destination tasks**.

| Field | Meaning |
|---|---|
| `id`, `media_item_id`, `destination_id` | Identity and foreign keys. Unique on `(media_item_id, destination_id)`. |
| `state` | `DISCOVERED`, `HASHING`, `QUEUED`, `UPLOADING`, `VERIFYING`, `UPLOADED`, `SKIPPED_PRESENT`, `RETRY_AT`, `PAUSED`, `NEEDS_AUTH`, `NEEDS_PERMISSION`, `FAILED_PERMANENT`, `OUTCOME_UNCERTAIN`, `CANCELLED`. |
| `attempt_count`, `next_attempt_at`, `last_error_code`, `last_error_detail` | Resumption and support context. Never store an auth response body. |
| `bytes_transferred`, `last_attempt_at`, `completed_at` | Accurate progress/history. |
| `provider_media_key`, `provider_album_key` | Opaque provider values when a commit confirms them. |
| `content_sha256_at_attempt` | Prevent a changed file at the same URI from inheriting an old success. |

### Supporting tables

- `scan_checkpoint`: MediaStore generation / cursor and last full reconciliation time.
- `provider_session`: non-secret expiry/health metadata only; raw tokens stay in encrypted storage and may be recreated.
- `backup_policy`: Wi-Fi-only, charging-only, battery-not-low, include-images, include-videos, selected-only, and paused flags.
- `activity_event`: small local audit trail, e.g. `Queued 42 items`, `Google authentication expired`, `14 uploads confirmed`.
- `hub_pairing` and `routing_override`: create these only when Hub work begins. Their future presence must never be inferred from whether a direct destination is enabled.

## 6. Provider-neutral contracts

Define these contracts before the Google implementation:

```kotlin
interface DestinationAdapter {
    val descriptor: DestinationDescriptor
    suspend fun validate(): DestinationHealth
    suspend fun locateOrCreateAlbum(request: AlbumRequest): AlbumRef
    suspend fun checkContent(request: ContentCheck): RemoteContentState
    suspend fun upload(request: UploadRequest, progress: (UploadProgress) -> Unit): UploadResult
}

data class UploadRequest(
    val taskId: TaskId,
    val media: MediaSnapshot,
    val source: MediaByteSource,
    val destination: DestinationRef,
)
```

`MediaByteSource` must expose a fresh, closeable stream for each attempt. It must never hand an Android `Uri` to the provider layer. `MediaSnapshot` is immutable and contains the hashes, byte size, filename, MIME type, and timestamp captured when the task began.

The state machine owns retries; provider adapters return a typed result only:

- `Present(providerKey?)`
- `Committed(providerKey, albumKey?)`
- `Retryable(code, retryAfter?)`
- `AuthRequired`
- `PermissionRequired`
- `PermanentFailure(code, userMessage)`
- `UnknownAfterRequest` for connection loss after a side-effecting request begins.

On `UnknownAfterRequest`, move to `OUTCOME_UNCERTAIN`, run the provider hash check again on the next permitted attempt, and upload only if it is still absent. Never blindly resend an item that may have committed.

## 7. Google direct adapter

### Setup and credential handling

1. Present a clear setup page that says this is an unofficial, reverse-engineered Google Photos upload integration and may stop working or affect the account.
2. Require an explicit acknowledgement before showing the import field.
3. Validate required fields locally without logging the value.
4. Encrypt the raw credential with a random AES-GCM data-encryption key protected by Android Keystore; persist only the encrypted blob, IV, and Keystore alias in app-private storage.
5. Provide `Replace credential` and `Disconnect`. Disconnect destroys encrypted credential data, pauses dependent tasks with `NEEDS_AUTH`, and leaves their local history intact.
6. Never place credentials, bearer tokens, request headers, or provider bodies in logs, crash reports, WorkManager input/output `Data`, notifications, or database rows.

Android Keystore keeps key material non-exportable and can constrain its use; use it rather than inventing a custom secret store. [Android Keystore guidance](https://developer.android.com/privacy-and-security/keystore)

### Upload algorithm

For each `QUEUED` task:

1. Re-open the `ContentResolver` stream and confirm the stored size and MediaStore generation still match. If not, return task to `DISCOVERED` and rescan/hash it.
2. In one buffered pass, update SHA-256 and SHA-1 digests. Reuse persisted hashes only when generation and size match.
3. Call the provider's SHA-1 presence check. `Present` becomes `SKIPPED_PRESENT`; this is a truthful dedup result, not an inferred upload success.
4. Resolve the configured album once per destination and cache only its opaque key.
5. Request an upload token, stream the original bytes with a counting request body, and publish byte progress no more than four times per second.
6. Commit filename, original capture time when available, SHA-1, and quality mode. Then ensure album association.
7. Write `UPLOADED` and provider keys in one Room transaction. Do not claim completion before that commit response has been interpreted.

Use a shared exponential back-off for `429`, `500`, `502`, `503`, and `504`, respecting `Retry-After` when provided. Start at one second, cap at 30 seconds inside one attempt, then use WorkManager exponential back-off for later attempts. Authentication failures do not retry automatically. File format rejection is permanent and exposes the filename/reason in the UI.

The adapter must send original bytes without a lossy re-encode. It must not spoof or collect device/account data beyond what the imported credential and the provider flow require. Keep all protocol constants in one internal `GoogleNativeProtocol` class with links to the bclone source and test fixtures.

## 8. Local library and permissions

### Access modes

- **Continuous backup:** request images and videos access, explain why full library access is required, and handle full, selected, denied, and revoked states.
- **Selected backup:** use the system photo picker; queue only returned media items and label the mode `Selected items`.

On Android 14+, selected-media access can be temporary. Reconcile permission state in `onResume`, before scans, and when a read fails. Offer a `Choose more photos` action rather than treating an inaccessible URI as an empty library. [Selected Photos Access guidance](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)

### Scanning

- Query `MediaStore.Images` and `MediaStore.Video` separately, excluding pending/trashed items where available.
- First scan creates or updates `media_item` rows in pages. It must be cancellable.
- Later scans use generation/version signals where available; run a conservative full reconciliation at least weekly and after access changes.
- Register a `ContentObserver` only while the process is alive to schedule a debounced reconcile. It is an optimisation, never correctness machinery.
- Do not copy media into app storage just to index it. Stream from `ContentResolver` at hash/upload time.
- Mark vanished items `MISSING`; never delete a successful backup history because a local file disappears.

## 9. Scheduling, background execution, and notifications

Use one unique reconciliation chain and one unique upload-drain chain per destination. WorkManager is the durable scheduler; it persists scheduled work across app restarts and reboots and can apply network, battery, charging, and storage constraints. [Android background task guidance](https://developer.android.com/develop/background-work/background-tasks/persistent)

### Default policy

- Initial setup: scan immediately after user confirms.
- Normal operation: schedule an every-12-hours reconciliation, plus a one-time upload drain whenever new eligible tasks appear.
- Default transfer constraints: unmetered network and battery not low. Charging-only is off by default but available.
- `Back up now`: enqueue expedited work. If the user starts a long, visible run, promote that run to a `dataSync` foreground worker with a progress notification and a Pause action.
- Uploads started by background reconciliation remain ordinary constrained work; do not start a foreground service from boot.
- Pause cancels/enqueues no new work but preserves every task. Resume creates a fresh unique drain.

Declare the Android `dataSync` foreground-service type and its required permission only if implementing the user-initiated long-run path. Android documents it specifically for upload, backup/restore, and device-to-cloud transfers, and imposes Android 15 time limits, so implement `onTimeout` and return unfinished tasks to `QUEUED`. [Data-sync service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types) and [timeout rules](https://developer.android.com/develop/background-work/services/fgs/timeout)

### Notification copy

- `FoxyPhotos is backing up 14 of 88 items`
- `Waiting for Wi-Fi`
- `Paused`
- `Google Photos needs attention`

Notification actions: `Pause`, `Open`, and, on an error notification, `Review`. Never show filenames or thumbnails on the lock screen unless the user explicitly enables detailed notification content.

## 10. Screens and interaction specification

### First-run setup

1. Welcome: `Back up your photos directly from this phone.`
2. Access choice: `Back up all photos and videos` or `Choose specific items`.
3. Google destination: explanation of the unofficial integration, explicit acknowledgement, credential import, and validation.
4. Backup preferences: album name, Wi-Fi-only, include videos, and optional charging-only.
5. Review: selected media scope, destination, and constraints. `Start backup` creates the first scan and queue.

### Library

- Material 3 adaptive grid of local media in descending capture date order.
- Filter chips: `All`, `Queued`, `Uploading`, `Uploaded`, `Needs attention`.
- Per-item secondary text shows state in words. A provider-specific cloud mark is allowed only alongside text.
- Item detail shows local metadata, content size, queue history, and the exact destination task states. No remote file browser is implied.

### Backup

- Header: current destination and a text progress summary.
- Sections: active, waiting for constraints, needs attention, recently completed.
- Task rows expose a retry/review action only where relevant; no destructive remote action exists in this release.
- `Back up now` begins a foreground-visible transfer if a material queue exists.

### Settings

- Backup toggle, network and charging constraints, media type inclusion, selected-vs-full scope, album name, credential replacement, Disconnect.
- A future `Hub` section is hidden until the Hub feature exists. Do not show disabled fake controls.

Use Compose unidirectional state flow with screen-level ViewModels. [Compose architecture guidance](https://developer.android.com/develop/ui/compose/architecture)

## 11. Future-proofing rules

### Hub

Do not build the Hub in this milestone, but preserve these invariants:

1. A Hub pairing is a saved relationship; disabling Hub routing or choosing direct Google backup must never unpair it.
2. `Direct`, `Hub`, and `Auto` are routing choices for a destination task, not account states.
3. A temporary `Backup directly` override expires after **24 hours** for that destination. It is recorded as a routing override and never removes the pairing.
4. Hub deduplication is content-first and destination-independent. It may enqueue a second destination task only after the Hub has fully received and verified the file. It must not claim it has reusable bytes while another client is still uploading them.
5. The client/Hub sync contract will exchange versioned deltas of the same content and task model used here: `revision`, operation (`upsert`/`delete`), canonical payload, and per-client cursor. Do not make the Android UI consume provider-shaped records.

### iCloud Photos

Add `provider:icloud-photos` later under the same `DestinationAdapter` contract. Start with library/catalogue browsing and metadata, using bclone's iCloud Photos code as a reference for personal/shared library discovery, album hierarchy, delta cache, and Apple SRP/2FA session handling. The bclone documentation currently says iCloud Photos upload is unsupported, so do not represent iCloud as a backup target until an upload implementation has independent integration tests and a recovery story. [bclone iCloud Photos notes](https://github.com/benjithatfoxguy/bclone/blob/main/docs/content/iclouddrive.md)

### Shared core

After the Android prototype is stable, extract the Android-free model/state-machine tests into a language-neutral specification and versioned JSON fixtures. Only then decide whether the shared implementation becomes Rust + UniFFI. Do not introduce a Rust bridge merely to make this first Android release look cross-platform.

## 12. Implementation sequence

### Phase A — foundation

1. Audit the existing repository; preserve its conventions and do not replace working build tooling wholesale.
2. Add this plan, an ADR for the direct-Google scope, and `THIRD_PARTY_NOTICES.md`.
3. Create the module boundaries and the provider-neutral model/contracts.
4. Add Room schema version 1, migrations policy, repositories, fake provider, and the task-state transition guard.

**Exit:** a test can enqueue one content item for two fake destinations and prove they remain separate tasks.

### Phase B — local library

1. Implement permission/access state and MediaStore scanner.
2. Implement library grid, item detail, and scheduled reconcile.
3. Implement streaming dual-hash calculation and changed-file detection.

**Exit:** a real phone with the supplied sample library displays items, persists them after restart, and reports revoked/selected-only access honestly.

### Phase C — Google upload adapter

1. Implement encrypted credential storage, setup validation, and health check.
2. Port the minimal bclone-observed auth/hash-check/upload-token/upload/commit/album flow behind `DestinationAdapter`.
3. Add MockWebServer fixtures for success, duplicate, throttle, token expiry, connection loss after commit begins, and unsupported media.
4. Implement safe retry classification and `OUTCOME_UNCERTAIN` recovery.

**Exit:** a manually configured test account receives one original image in the target album; a second attempt reaches `SKIPPED_PRESENT` without another upload.

### Phase D — daily-use backup

1. Wire queue drain into WorkManager and constraints.
2. Add user-initiated foreground progress and pause/resume.
3. Build Backup and Settings screens, error explanations, notification privacy defaults, and activity history.
4. Test process death, restart, offline/online transition, Wi-Fi policy, auth expiry, changed local file, and queue resume.

**Exit:** the app can back up an ordinary real library over several days without duplicating confirmed files or losing the queue.

### Phase E — release hardening

1. Enforce strict log redaction and run a credential-storage review.
2. Verify all database migrations and WorkManager re-enqueue paths.
3. Add a diagnostic export that excludes credentials and raw provider bodies.
4. Produce a signed internal APK, a manual test checklist, known limitations, and rollback/uninstall instructions.

## 13. Required tests and acceptance criteria

The implementation agent must add tests; do not rely on a successful manual upload alone.

- Unit-test every allowed and rejected task-state transition.
- Unit-test an item with identical SHA-256 feeding two destination tasks.
- Unit-test that a changed MediaStore generation invalidates a previous hash/success snapshot.
- Unit-test encrypted credential round-trip and verify log redaction never includes secret fragments.
- Mock-provider test normal commit, duplicate hash match, 429/5xx throttling, expired auth, permanent rejection, cancellation, and uncertain commit.
- Room migration test from every shipped schema version.
- Compose/UI tests for full-access, selected-access, no-access, uploading, waiting, error, and paused states.
- Instrumented test for process recreation while a task is queued.
- Manual device matrix: Android 10, Android 13, and Android 14+ selected-media access; Wi-Fi/cellular changes; low battery; reboot; a library with images, videos, duplicates, and a file removed after scan.

Release is acceptable when a user can install the APK, grant access, configure one direct Google destination, start a backup, close/reopen the app, and still see a truthful queue that drains when its declared constraints are satisfied.

## 14. Non-negotiable engineering constraints

- Do not use provider tokens in source code, fixtures, screenshots, or CI secrets exported into artifacts.
- Do not model `uploaded` from a UI event, a scheduled-work completion, or a filename match. It requires a provider result or a provider duplicate confirmation tied to the computed content hash.
- Do not claim Google library browsing, deletion, or download support in the UI.
- Do not make a network call on the main thread.
- Do not use a catch-all `Exception` state; map errors to typed categories and retain the original safe diagnostic class.
- Do not create a Hub, Cloudflare worker, or server merely as scaffolding.
- Do not silently switch off a saved Hub pairing when direct mode is selected in later work.
- Keep product copy consumer-facing. Protocol, token, and cache implementation details belong behind a `Details` view only when needed for recovery.

## 15. Agent handoff

Implement Phases A–D in small reviewable commits, one phase or coherent vertical slice at a time. Before each provider-facing change, compare the request/response behaviour against the linked bclone sources and add/adjust a fixture. Before changing a stable schema, add its migration test. Stop after Phase D with a short implementation report that lists the actual supported Android versions, any protocol differences from bclone, and anything that prevented the daily-use acceptance criteria from being met.
