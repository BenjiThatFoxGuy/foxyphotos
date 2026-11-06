# Experimental library and storage roadmap

## PINNED: future browser-assisted iCloud session refresh

**Do not lose this follow-up. Decided direction: embedded WebView session refresh.** Benji, bclone's author, confirms his external login-refresh project launches a browser, lets him sign into Apple himself, then captures the resulting cookies after successful login. Adapt that proven browser-login/session-capture approach into an app-owned WebView, avoiding manual cookie copying. This integration already targets reverse-engineered private APIs; official Apple API support is not an acceptance gate, and the choice of WebView is not an open product question. Inspect the reference implementation to determine exact cookie/session extraction, persistence and refresh mechanics, then test the Android adaptation. Distinguish untested implementation details from the settled product direction; do not claim the Android implementation works until exercised.

This is **future scope**, not a prerequisite for the manual rclone-style session import or this goal's completion. If a bounded investigation yields a proven safe implementation, it may be proposed as a separate follow-up slice; do not derail the active roadmap or claim this flow exists from a login screen alone. Preserve manual import as fallback. User enters Apple credentials/2FA directly into the genuine Apple page; never intercept passwords, bypass browser isolation or log session cookies. Store captured session material only through encrypted provider credentials. Account for cancelled login, expired sessions, cookie updates and replacement. The external project's name/path is not yet identified in this plan; retrieve it when implementing rather than guess.

## PINNED polish feedback: one integrated main library

CORRECTION: use an explicit LIBRARY MODE selector, analogous to but independent of Simple/Modern UI mode. The stated choices are Local only, Google Photos, Google Photos + iCloud, and iCloud only. Apply the selected mode to the MAIN library rather than opening separate gallery destinations. Do not automatically add iCloud when enabled or merge every provider into every mode. Preserve those user-stated mode names; confirm any unresolved local-item overlay semantics during the polish pass rather than inventing them. Keep provider identity/backup marks intelligible; merge only proven equivalents within the selected mode. Earlier wording implying automatic all-provider aggregation is superseded. The separate experimental screen is a temporary prototype surface, not final navigation. Recover the mockups or request them at the polish pass if unavailable; do not invent their precise appearance.

Current product priority after #99: implement usable explicit per-folder destination-album selection wired into ordinary backup, and the four-way main-library mode selector. These are functional controls, not subjective polish; the separate combined-view button and metadata-only mapping screen do not satisfy them. Keep native album readback research bounded and independent so it does not displace these controls. Recover exact mode requirements before routing sources; do not silently merge all providers or invent local-overlay behavior. Subjective styling and mockup-level layout refinement remain deferred.

## Execution contract

### Current task: direct implementation, superseding Codex supervision

Alex directly owns implementation, debugging, CI fixes, artifact verification and Telegram delivery. Do not use Codex CLI or another coding-agent CLI. Normal tools and bounded implementation subagents are allowed where useful; avoid repeated review fan-out and overlapping writers. Prefer simple maintainable vertical slices over orchestration complexity.

Recovery checkpoint: signed nightly #97, source `9adec3107e765b3743950b158337d7605ea5bc12`, run `42247356144`, was independently verified and delivered on Telegram. Artifacts and signature/provenance evidence are under `/home/benji/.hermes/cache/scratch/foxyphotos-folder-albums/`. Do not rebuild or redeliver it unnecessarily. The previous supervision goal was explicitly closed by Benji, not because all roadmap requirements were complete. Codex and the read-only researcher were stopped.

Latest delivery checkpoint: signed nightly #99, source `61e248b577aff57340bc9149d570dbb88b4f6f10`, [run 42253831857](https://github.com/BenjiThatFoxGuy/foxyphotos/actions/runs/42253831857), passed CI and was downloaded, independently verified and delivered on Telegram. Evidence is under `/home/benji/.hermes/cache/scratch/foxyphotos-manual-copy/`. Package/version, exact source/run/workflow attestation, APK digest, local cryptographic signature and signing-certificate continuity passed. This is a usable manual Copy slice, not completion of the entire roadmap. Native Google album contents and membership readback remain unfinished. No live account/device acceptance is claimed.

The interrupted Copy work has been reused and committed, with focused tests in `6d7e7c0` and implementation in `61e248b`. Do not restart it or search for the old stopped Codex worker. Copy is default-off in the experimental iCloud album browser, selects up to 20 loaded items, stages one original of at most 32 MiB at a time, persists confirmed/uncertain outcomes and requires explicit resume after pause/backgrounding. Album organization is attempted and labelled unconfirmed, not falsely declared preserved; Move remains disabled. See [implementation and limits](../research/manual-copy.md). Inspect the current working tree before further edits and preserve unrelated changes. Milestones 0-3 have delivered slices; historical unchecked live-acceptance boxes are not instructions to restart them.

Finish the remaining real vertical slices: native Google album-content browsing and membership confirmation, then usable manual iCloud-to-Google Copy batches from the album/folder UI with album organization. Trace actual reference implementations and wire formats; never invent response schemas, fake remote data or equate HTTP acknowledgement with confirmed membership. Establish precise evidence for genuinely unavailable protocol capabilities, continue independent safe work and ask only when a real product decision is required. Documenting a limitation or passing CI does not silently waive unfinished implementation acceptance.

Transfers require explicit selection, bounded staging/queues, truthful progress, pause/cancel, safe retries and durable confirmed/uncertain outcomes. Preserve source albums using confirmed destination identities. Copy never deletes the source. Show Move disabled with an honest reason unless destination safety, album preservation and source deletion can genuinely be verified. No personal media deletion or live account mutations during development/tests. Keep local cleanup fail-closed. The current iCloud original reader permits at most 32 MiB per read; expose actual limits or implement a bounded transfer adapter rather than assuming unrestricted large-video support.

Preserve default-off experiments, backup behavior, Simple mode, Modern profile separation, the corrected independent library-mode requirements, encrypted credentials behind existing `@JvmSynthetic` helpers and sanitized diagnostics. Platform-neutral policies/provider contracts belong in portable core; Android-specific UI/storage adapters remain on Android. No iOS implementation. Subjective polish, main-library layout integration and live-device feedback remain deferred. WebView login refresh stays future scope.

Android compilation/tests are CI only: no local Gradle, Android SDK, dependency-cache recreation or emulator. Use focused high-value tests, not exhaustive scaffolding. Commit cohesive changes promptly without AI attribution. Main pushes are authorized; no release tags, deployments or paid services without permission.

For every usable milestone, push and verify CI for the exact source. When green, explicitly retrieve signed release artifacts with `gh run download <run-id> --dir <scratch-milestone-dir>`. GitHub Actions artifacts never appear locally automatically. Verify exact source/run/workflow attestation, APK package/version/signature, digest and signing-certificate continuity, then deliver using `MEDIA:/absolute/path/to/app-release.apk`. Downloads and verification require no local Android build. Alex owns this delivery; never hand it back to the user or repeatedly search for undownloaded artifacts.

Every continuation must advance an unfinished deliverable, resolve a real blocker or investigate an actual stall. Never park, pause or register goal waits on processes, sessions, delegations or timers. Avoid rapid polling, repetitive unchanged-status reports and busywork. Give brief updates at meaningful milestones. Ask only genuinely required questions when no independent safe work remains; do not ask whether to continue authorized work. Keep the goal active until remaining roadmap implementation acceptance criteria are satisfied, exact-source CI is green and the final verified signed APK is delivered on Telegram. Distinguish fixture/CI verification from unperformed live-device acceptance; do not invent a new approval gate for deferred device testing or polish.

Deliver each usable green slice rather than waiting for this entire roadmap. Do not create release tags without authorization. Log meaningful safe lifecycle events in View logs. Keep credentials encrypted and behind existing helpers; never log or commit secrets, account details, media names/paths, content hashes, signed URLs or raw responses.

## Product milestones (ordered)

- [ ] 0. Finish current gallery refresh and deliver its verified signed nightly.
  - Implementation: `8ceee63c913897643ecb0abaae22ed2e33437091`; CI #74, run [42229714948](https://github.com/BenjiThatFoxGuy/foxyphotos/actions/runs/42229714948) green. Parent verified the exact signed APK. Delivery remains parent-owned and pending confirmation here.
- [ ] 1. Experimental combined local + Google Photos library, explicitly enabled in Settings and off by default.
- [ ] 2. After milestone 1 is implemented and builds green, add a separate opt-in experimental local + Google Photos + iCloud library view, off by default.
- [ ] 3. Prioritize experimental verified local cleanup, off by default, with its own device-local folder whitelist and later-run remote content verification. This is the highest-priority functional convenience after the required provider groundwork.
- [ ] 4. Prototype real album/folder browsing and optional local-folder-to-Google-album name matching, with safe existing-album lookup/reuse before creation. Research album capacity before album writes.
- [ ] 5. Lower-priority experimental manual storage-to-storage transfer batches from the album/folder UI, initially iCloud to Google Photos, with Copy and Move choices and album preservation. Build a minimal usable prototype; fuller product scoping follows later. No iCloud upload requirement.

Record concrete implementation, CI evidence, delivery and remaining provider limitations below each milestone as work progresses. Research may show prerequisites that require reordering supporting implementation, but do not silently substitute fake provider data for a working milestone.

## 1. Combined local + Google library

Bounded read-only slice implemented at `a4d53c89ad0037eaf54e20f6254627504720cfc5`:
separate native opt-in grid accessible from Simple/Modern, real native protocol pages,
all accessible local media independently of backup folders, local/Google badges,
explicit next-page controls, bounded lazy previews and encrypted credential access.
Safe lifecycle diagnostics, retryable missing-auth/page/preview failures and visible
partial/session limits are implemented. No synthetic remote items or filename merging.

CI #79 [run 42232995176](https://github.com/BenjiThatFoxGuy/foxyphotos/actions/runs/42232995176)
is green, including protocol fixtures, opt-in/missing-auth UI coverage, existing
regressions and debug/release package/version/signature checks. Exact signed release
APK (version `0.1`, code `79`) downloaded and verified with `gh attestation verify`
against that full source digest and `.github/workflows/verify.yml`; its digest matches
the CI report and signing-certificate fingerprint matches the parent-verified gallery APK.
APK SHA-256: `985cdb05163dd5bf1e4b35f5afa677667fcaac0af08e82114c0d25755130eeb7`.
Evidence is in `/home/benji/.hermes/cache/scratch/foxyphotos-combined-google/ci79/`.

Remaining acceptance: live device/account listing and native-key preview resolution
have not been exercised. Google videos have labelled still previews, no playback.
Maximum ten requested pages / 5,000 Google items per view session, three thumbnail
jobs, bounded network responses and session bitmap LRU. No original-byte verification,
cleanup, album writes or delivery claim. Parent supervises acceptance and delivers.
Milestone stays unchecked pending live acceptance and parent delivery confirmation.
See [pinned protocol, capacity research and limits](../research/google-combined-library.md).
The bclone capacity follow-up is recorded in its own `docs/research/gotohp-album-capacity.md`,
local documentation-only commit `98cbe5c68` (not pushed); provider implementation unchanged.

Research actual existing bclone Google Photos read/list/download capability before designing adapters; pin reference revisions and trace real request and response handling. Do not assume the upload adapter already supports a remote library. Use existing credentials only through authorized private storage. No bulk live account crawling without bounded operation limits. Ordinary read-only listing needed for an explicitly enabled feature is in scope; do not run destructive account operations during development.

Provide real listings/previews, source badges and honest loading/partial/error states; local media remains all accessible items regardless routine backup-folder selection. Use stable provider identities. Merge proven equivalents only; a filename match is not proof of equality. No fake remote entries or invented backup badges. Bound paging, caching and thumbnail work. Preserve ordinary local-only mode as default and existing backup behavior.

Milestone 1 handoff update (parent report, 2026-11-06): source `9b02ee1bd86105f2b5078afc551f9d081b911998`, CI #80 [run 42233707821](https://github.com/BenjiThatFoxGuy/foxyphotos/actions/runs/42233707821) green. Parent reports exact signed artifact verified and is delivering it now. Delivery is parent-owned; live Google/device acceptance remains unverified and is not a blocker for milestone 2 implementation. Parent handoff `2d834ce` is preserved.

## 2. Add iCloud read-only source

Parent handoff (2026-11-06): final source `9283b7e6977e974654ce01078b9b75b918dfa559`,
CI #83 [run 42241611497](https://github.com/BenjiThatFoxGuy/foxyphotos/actions/runs/42241611497)
passed; parent verified the exact signed APK and is delivering it. Live account/device
acceptance remains unverified and does not block milestone 3 implementation.

Implemented read-only prototype (first green source `e9ca63771b934650d24dcce6c137ff517f103cc1`): separate default-off native local + Google + iCloud grid; explicit manual rclone remote-section import/replacement into existing encrypted credential storage; validation-only authentication; real bounded Photos zones/albums/asset-master listing, signed JPEG previews and explicit bounded original read via Android document picker. Existing Simple/local and Google opt-in defaults and backup queues are preserved. No cloud writes/deletion or interactive Apple sign-in. See [pinned source research and precise limitations](../research/icloud-photos.md). CI #82 [run 42241005165](https://github.com/BenjiThatFoxGuy/foxyphotos/actions/runs/42241005165) is green, including focused import/protocol fixtures, independent opt-in/missing-auth native UI coverage on API 33/35, existing regressions, and debug/release package/version/cryptographic signature checks. A small follow-up matches the pinned consumer's browser User-Agent and validation content type and labels the native screen. Final signed APK, exact source/run/workflow attestation, digest and matching parent signing-certificate evidence are tracked in `/home/benji/.hermes/cache/scratch/foxyphotos-icloud-library/verification.json` with the parent handoff. Parent owns delivery. Live Apple/Google account/device acceptance has not been performed; this milestone stays unchecked for that acceptance, not because of an implementation prerequisite.

User steps: Settings → enable Experimental local + Google + iCloud library → enter exact remote section name → Import / replace session file → choose a locally exported config with externally valid Cookie-header session → wait for validation success → Open iCloud combined library. Google uses its existing connection independently; absence/failure of either provider keeps the other sources accessible. Scroll the source controls if needed, load pages explicitly, choose iCloud libraries/albums, tap a photo for its preview, or save an original through Android's document picker (up to 32 MiB). On session rejection, renew externally, import replacement and reopen the view. Failed import leaves the old session and backup queues intact. WebView capture remains the decided future follow-up. Video playback, live compatibility acceptance, cloud writes/deletion, cleanup and transfers remain out of this prototype.


This explicitly reopens earlier deferred iCloud work ONLY for authenticating, listing and reading/downloading photos needed by the opt-in experiment and future transfers. No iCloud Photos upload implementation, no Apple Drive substitution, no iOS target. Keep normal/local mode free of iCloud prompts. Research bclone and icloudpd/pyicloud references, licenses and credential/challenge flows before implementing. Handle 2FA and expired sessions honestly; never claim unsupported auth or listing works. No new credential solicitation while Benji is busy: implement supported flows and identify exact missing user input when unavoidable.

### Authentication handoff and expiry

Benji reports bclone's interactive iCloud login is currently unreliable and obtains session cookies using another project. Treat bclone's actual iCloud Photos configuration/session consumer as the compatibility reference, not its broken login wizard as a required flow. Inspect configuration and API consumers without reading or exposing live secret config values. Initial source inspection: `backend/iclouddrive/icloud.go` registers service `photos`, Apple ID, password, optional trust token, session cookies and client ID. Distinguish registration-required fields from fields actually needed by an imported valid session before designing the app's requested data. Support secure session/cookie import and replacement rather than requiring a working in-app Apple password login. Do not assume cookies alone suffice until traced. Never ask for credentials in chat or persist them in plan/logs.

#### Confirmed bclone configuration compatibility

Reference source revision: bclone `f6ad6b63ec67694011c686f246f8832cb0e6fb9a`, `backend/iclouddrive/{icloud.go,icloudphotos.go,api/client.go,api/session.go}`. Provide a manual advanced session setup/import flow so Benji can reuse the output of an externally successful login. Support rclone config-style fields: `type = iclouddrive`, `service = photos`, `apple_id`, `password`, `trust_token`, `cookies`, `client_id`. Select one intended remote section explicitly when importing; never ingest unrelated remotes/secrets. `encoding` is a path option, not authentication data. Validate backend/service and supported fields without logging their values.

The `password` value in rclone config uses rclone obscure, NOT a password hash or secure encrypted storage. Implement compatible decoding for explicitly selected rclone-obscured input, not arbitrary guessing between plaintext and encoded values. Treat the decoded password and the original obscured text as secrets; store only within app encrypted credential storage using existing `@JvmSynthetic` boundaries. Do not retain the imported config or expose secrets in UI summaries/diagnostics. A config file encrypted as a whole is a distinct format: reject honestly unless explicitly supported, not silently parse it as plaintext.

bclone Photos currently requires nonempty `trust_token` before authenticating. `cookies` is a Cookie-header-style `name=value;name=value;` string, not a browser-cookie JSON file. Its session consumer first POSTs cookies to `/validate`; with a valid session it returns without password sign-in. Only after failed validation does it fall back to Apple ID/password and token-based login; the Photos constructor rejects a resulting 2FA challenge. Runtime requests retry authentication on 401/421 once before a non-reauthenticated request. Cookie updates are persisted through a callback; wizard 2FA also saves `trust_token`. API session JSON properties such as `session_token`, `session_id` and `scnt` are not independently registered rclone config keys. Do not invent extra required import fields from those struct members.

Make manual imported-session validation the supported prototype path, not the broken interactive login wizard. Preserve compatible trust token/client ID data; do not impose password sign-in when valid imported cookies suffice. Distinguish a missing credential from rejected/expired session and from network failure. Allow safe replacement using another externally valid session. No request for real credentials in chat, and no live-account authentication needed to implement fixture-based import/validation coverage.

Expect iCloud session expiry and renewed challenges: surface reauthentication required, stop repeated unauthorized retries, preserve queued work and local data, allow secure session replacement, then resume safely. No cleanup or destructive transfer completion on expired auth. Benji reports Google Photos credentials are repeatedly reusable and have not expired in his testing; this is observed behavior, NOT a guaranteed non-expiring API key. Preserve reusable Google credentials without arbitrary forced expiry, but handle revocation/authentication failure truthfully for both providers. Do not conflate transient network failure with expired credentials.

The third view must be gated separately and only added after the Google combined view builds. Show unavailable/error state if auth is absent, not synthetic content. Test adapter behavior with offline fixtures; report live verification separately.

## 5. Manual transfer prototype (lower priority)

The long-term use case is freeing paid iCloud capacity by transferring chosen albums/batches to Google Photos. Entry is a manual menu action from the real folder/album browsing UI, with an explicit Copy or Move choice, not an automatic migration daemon. Preserve source album organization at the destination using confirmed album identities and safe matching. Keep the first prototype narrow; detailed transfer UX and broader policy will be scoped later.

Both actions first copy: explicit source selection, bounded queue/staging, progress, pause/cancel, retries and truthful confirmed/uncertain outcomes. A Move request additionally requires explicit destructive confirmation and verified destination safety before source removal. Never delete source items on upload receipt alone, failed/uncertain transfer, cancellation, or unresolved membership. If safe remote verification/deletion cannot yet be implemented, disable Move with an honest reason rather than silently treating Copy as Move. No real cloud deletion during development or tests. Unsupported Apple upload is not a blocker for iCloud-to-Google direction.

## 3. Verified local cleanup

Parent handoff (2026-11-06): final source `93df29157faf634bac3e91450fff3e54cd10e052`,
CI #91 [run 42244877755](https://github.com/BenjiThatFoxGuy/foxyphotos/actions/runs/42244877755)
green; parent reports the exact signed artifact verified and is delivering it now.
No live/device acceptance is claimed. The prior process is complete.

Implemented (2026-11-06): default-off native cleanup screen in Settings,
independent actual-volume + folder whitelist, durable new-upload receipts and explicit later-run
actual Google `=d` byte-stream verification. A usable per-item Android 11+ write-consent
then trash path re-downloads and rechecks policy/content/identity after system consent.
No permanent delete or cloud delete. Original unredacted permission is required;
old receipts/presence checks cannot qualify. 20 candidates/preview, 256 MiB/item,
30-day upload evidence, 10-minute session verification; Android 10 cleanup disabled.
First green source `2c3dfbf95351700034f6dd4d10229bf4b494a10e`, CI #89
[run 42244217021](https://github.com/BenjiThatFoxGuy/foxyphotos/actions/runs/42244217021).
Includes focused portable policy/execution/download fixtures, durable receipt isolation
and default-off native UI tests on API 33/35, plus existing regressions and signed
package/version/signature CI checks. Final signed artifact and exact final
source/run/workflow attestation, local APK signature and manifest verification,
digest and previous certificate comparison are retained for parent delivery in
`/home/benji/.hermes/cache/scratch/foxyphotos-verified-cleanup/verification.json`
and `apk-signature-verification.json` (artifact handoff, not device acceptance).
See [protocol evidence, exact safety model and limitations](../research/google-original-cleanup.md).
Google photo location removal and video thumbnail/transcode semantics can prevent
byte equality; those items stay local. Live original download/device acceptance
remains unverified. Do not mark this milestone accepted from compilation or fixture success alone.
No live personal media/cloud deletion was performed; tests use fixtures.
Simple, combined experiments and backup remain independent. Parent owns delivery
and live account/device acceptance; the milestone stays unchecked for acceptance.

User steps: Settings → Experimental verified local cleanup → enable → allow original
media access → choose cleanup folders → back up **new** items normally → return and
Verify earlier uploads → review a matching item → Move local item to trash → confirm
in app and approve Android write access. FoxyPhotos repeats remote/local verification
before trashing. Use your device gallery to restore before device trash expires.


Separate global opt-in and device-local folder whitelist, both off/empty by default. Backup-folder selection does not grant deletion authorization. Only items successfully uploaded in an earlier run become verification candidates in a later run. Example: upload photo A in run 1; in run 2 fetch the actual corresponding remote media bytes and compare a content hash with current local bytes. Upload success, existence lookup, thumbnail, remote metadata, filename or provider hash claim alone is insufficient.

Cleanup requires a byte-for-byte content hash match from an actual remote download plus unchanged local identity/content and current whitelist authorization at deletion time. If Google serves a transformed representation, exact verification is unavailable: retain local file and explain safely. No deletion for uncertain, failed, unavailable, changed, excluded or unverifiable items. Preserve media permissions and use Android's required user-mediated deletion/trash flows for media the app cannot delete silently. Explain this platform limit instead of claiming automatic cleanup universally works.

Implement policy, durable evidence and a safe preview first. Do not execute real device deletion during development/testing. Enabling the feature is not permission for development scripts to remove files. Use fixtures and narrow injected adapters to test negative safety cases. Unknown/expired evidence must fail closed. Never delete cloud originals in this milestone.

## 4. Folder-derived Google albums and browsing

Implemented usable bounded request slice (2026-11-06), implementation through
`58ffce6`: Settings has an independent default-off folder-derived Google album
switch and real native Google album metadata listing, explicit paging, item counts
when supplied, and Google Photos content links using returned web album IDs.
Actual local folders open a native item/preview grid. Existing iCloud album/library
reads remain available in their separate opt-in experiment. **Google album contents
are not rendered in-app**; this is a remaining native protocol capability, not
fixture data presented as remote membership.

Admitted backup batches separately organize new confirmations and ledger-deduplicated
content. Read-only hash lookup resolves current-account media identities without
reuploading confirmed media or trusting the older account-unscoped upload ledger.
Exact unique existing album lookup precedes creation; incomplete enumeration,
duplicate titles, admitted equal-leaf folders and persisted cross-folder title
claims stop writes. Full actual-volume/folder identity, account-scoped confirmed
album identity, creation intent, candidate intent, membership request and capacity
reservations persist privately with checked synchronous commits. A process mutex
serializes local resolver/write operations. New creation includes one confirmed
media key; response confirms the album identity, **not membership**. Lost creation
responses never cause automatic creation retries or title-only adoption.

Existing albums need known room under the authoritative 20,000 mixed-item bound,
including conservative unresolved reservations. Full/unknown capacity stops safely;
there is no rollover policy. Requests are bounded to twenty current-account presence
checks / twenty album writes per admitted pass; later passes skip journaled candidates.
HTTP membership acknowledgement is explicitly unconfirmed. Uncertain/acknowledged
requests are never replayed automatically; no album failure changes confirmed
backup success. Lifecycle diagnostics contain no provider/media/folder identifiers.

Focused fixtures cover exact metadata/wire shapes, missing count, lost creation/add
responses, durable non-replay, 19,999/20,000 capacity, partial listing, ambiguous and
cross-volume folder collisions, concurrent instances, renamed mappings, default-off
native settings and upload/dedup outcome isolation. CI evidence is recorded below
when complete. No local Gradle/SDK/cache/emulator or live personal account mutation.
See [pinned protocol, write safety and precise limits](../research/google-folder-albums.md).

Remaining: live account/device acceptance, native Google album-content rendering,
confirmed membership decoder/readback, and explicit uncertain-journal reconciliation.
No client-only mutex can exclude external writers between lookup and create/add;
there is no researched native atomic compare-and-create primitive. External races
can yield duplicate creation or uncertain/rejected association; subsequent ambiguous
lookup stops safely. Conservative reservation overcount can reduce usable capacity.
This slice must not qualify destructive transfers as having preserved album membership.
The main-library mode selector and subjective integration polish remain deferred.
Parent owns delivery and acceptance; the milestone remains unchecked.

User steps: Settings → Browse folders and Google albums → select a local folder,
or Load Google albums and page explicitly → Open album in Google Photos for remote
contents. For optional mapping, enable Experimental folder → Google albums, then
start an ordinary selected-folder or selected-item backup. Return to Settings for
its separate album status and View logs for safe lifecycle events. Review the actual
Google album yourself: backup success and album creation are not membership proof.
Run backup again for later bounded candidates; earlier uncertain requests stay stopped.


Quality of life is the product priority: a lean native client that can map phone folder A to backend album A, not merely another uploader. Prototype browsing real provider albums and local folders alongside the optional mapping. Keep future iOS reuse in the core; no iOS implementation in this goal.

### Sticky research requirement: Google Photos album capacity

**Confirmed documentation: Google Photos albums allow up to 20,000 photos or videos (mixed media items).** See [research and native-protocol gaps](../research/google-combined-library.md), retrieved 2026-11-06. The original user-reported claim was unverified before this research. This is an album limit claim, not an account/storage quota claim. Do not present it as confirmed until researched. Fact-check current authoritative Google documentation, investigate whether photo/video/shared-album rules differ, and trace bclone/gotohp behavior and error responses at pinned revisions. Record sources, retrieval dates, certainty and any gaps. Do not create/fill a huge live album just to test a boundary. Use fixture-based boundary/error tests where possible.

Track capacity handling for BOTH FoxyPhotos and bclone. Record the bclone follow-up in its own durable project documentation/tracker if cross-repo implementation is not part of the current goal; do not silently modify that repository under this app goal. Album-capacity exhaustion must not produce infinite retries, bogus duplicate albums, false membership success or data loss. Do not choose an automatic rollover/naming policy without a documented decision; surface a clear safe capacity result if necessary. Account storage entitlement does not remove a per-album bound.

Optional toggle, default off. Research bclone's Google Photos album lookup/matching/create logic at pinned revision before implementation. Resolve/reuse existing matching albums before creation. Define collision handling for equal leaf folder names and concurrent requests, without silently generating duplicate or bogus albums. Store confirmed provider album identity privately; do not log it. Distinguish album creation, membership and upload confirmation; preserve deduplication so enabling this does not reupload confirmed media unnecessarily. No massive account-wide album changes on toggle alone; act on admitted backup batches.

## Goal completion and blockers

Work sequentially to the fullest actually functional scope supported by researched protocols. Completion means all milestone acceptance criteria above implemented, focused regressions and CI green, exact signed APK verified and delivered. Never mark a provider milestone done from compiling a placeholder/unavailable adapter. Keep status explicit when live auth/verification is unavailable.

If a milestone requires missing credentials, unavailable provider capability, or a destructive user decision, record precise evidence and the smallest blocking input. Continue independent safe milestones where possible; stop honestly for genuinely required user input rather than looping or fabricating success. Do not keep polishing already accepted UI indefinitely. The goal judge may pause/re-scope; it is not a substitute for these concrete safety and completion criteria.
