# Manual iCloud → Google Copy prototype

Entry: enable the separate experimental local + Google + iCloud library in Settings,
import a valid iCloud session and connect Google, open the library, choose an iCloud
album, load the desired pages, then enable **Experimental manual iCloud → Google
Copy** (default off). Open **Copy / Move from this iCloud album**, select up to 20
loaded items by preview, review the Google destination and press **Copy selected**.
This is a foreground, explicit pass, not a migration daemon. Other library modes and
ordinary backup remain independent.

Each original is bounded to 32 MiB and staged alone in app-private no-backup storage.
Oversized, missing-metadata or unavailable originals fail safely and remain at source.
Only loaded items can be selected. The existing iCloud browser limits still apply
(ten explicitly requested pages per session, up to 5,000 items). No iCloud writes or
delete adapter exists. Move is visibly disabled: destination byte safety and source
deletion are unsupported. No real-account mutation was used for development/tests.

Pause/cancel stop the active coroutine; leaving/backgrounding pauses work. Cancellation
is not rollback: a remote write may already have completed. The private queue and
confirmed/uncertain receipts survive process death and batch closure. Resume explicitly,
using the original connections; reload the same source album pages before retrying reads.
Every start/retry checks the current encrypted imported session's validated account,
the browsing source account, and the Google credential account against the saved batch.
Missing/mismatched identities stop the pass. Credentials only cross existing encrypted
`@JvmSynthetic` boundaries; logs contain closed-vocabulary lifecycle events, never account,
media, URL or hash material.

An upload intent is synchronously persisted before writes. This Copy adapter disables
both uploader-side retries and transport connection retries. A lost upload response is
uncertain, never an invitation to upload again: subsequent explicit passes perform only
a read-only digest presence lookup until an identity is recovered. Negative presence is
not proof of absence. Account-scoped content receipts also apply when selecting the same
source item from another album, while album-specific request journals remain separate.
Closing a batch keeps those receipts; uninstalling/clearing app data loses them.

Album organization is best effort; this slice does not confirm membership. The existing
FolderAlbums resolver reuses a uniquely named album or creates one with a returned
identity, with durable pre-write intent, collision checks and conservative capacity
reservations. Acknowledged/uncertain membership writes are not automatically repeated.
The UI separates confirmed upload identity from **unconfirmed** membership. HTTP success,
creation identity and digest presence do not prove original-byte safety or preservation.
Native Google album-content browsing/readback is separate work, not a prerequisite for
this slice. See google-folder-albums.md for protocol/capacity limits.

Focused offline tests cover lost upload responses, presence-only recovery, cancellation,
pause, failed journal writes, account isolation, cross-album receipts, default-off entry,
source metadata/validated identity and single-attempt upload transport behavior. Android
compilation and tests run only in GitHub Actions; no local Gradle/SDK/emulator execution.
Live-device/account acceptance remains unperformed.
