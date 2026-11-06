# Experimental library and storage roadmap

## PINNED: future browser-assisted iCloud session refresh

**Do not lose this follow-up. Decided direction: embedded WebView session refresh.** Benji, bclone's author, confirms his external login-refresh project launches a browser, lets him sign into Apple himself, then captures the resulting cookies after successful login. Adapt that proven browser-login/session-capture approach into an app-owned WebView, avoiding manual cookie copying. This integration already targets reverse-engineered private APIs; official Apple API support is not an acceptance gate, and the choice of WebView is not an open product question. Inspect the reference implementation to determine exact cookie/session extraction, persistence and refresh mechanics, then test the Android adaptation. Distinguish untested implementation details from the settled product direction; do not claim the Android implementation works until exercised.

This is **future scope**, not a prerequisite for the manual rclone-style session import or this goal's completion. If a bounded investigation yields a proven safe implementation, it may be proposed as a separate follow-up slice; do not derail the active roadmap or claim this flow exists from a login screen alone. Preserve manual import as fallback. User enters Apple credentials/2FA directly into the genuine Apple page; never intercept passwords, bypass browser isolation or log session cookies. Store captured session material only through encrypted provider credentials. Account for cancelled login, expired sessions, cookie updates and replacement. The external project's name/path is not yet identified in this plan; retrieve it when implementing rather than guess.

## Execution contract

Use Codex CLI for implementation and CI iteration. Hermes supervises milestones, verifies exact artifacts, and delivers signed release nightlies on Telegram. Do not run local Gradle, download an Android SDK, or recreate dependency caches on this disk-constrained host. No repeated review fan-out. Keep commits small and preserve source portability: platform-neutral policy/provider contracts in core, Android UI/storage/install adapters on Android. No iOS implementation now.

Finish the already running gallery interaction refresh first; coordinate with that Codex process before touching its files. Keep Simple at-a-glance UI and Modern profile sheet separation. Subjective final UI polish and device acceptance come later from Benji. Do not treat compilation as proof of live provider behavior.

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

## 2. Add iCloud read-only source

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

Separate global opt-in and device-local folder whitelist, both off/empty by default. Backup-folder selection does not grant deletion authorization. Only items successfully uploaded in an earlier run become verification candidates in a later run. Example: upload photo A in run 1; in run 2 fetch the actual corresponding remote media bytes and compare a content hash with current local bytes. Upload success, existence lookup, thumbnail, remote metadata, filename or provider hash claim alone is insufficient.

Cleanup requires a byte-for-byte content hash match from an actual remote download plus unchanged local identity/content and current whitelist authorization at deletion time. If Google serves a transformed representation, exact verification is unavailable: retain local file and explain safely. No deletion for uncertain, failed, unavailable, changed, excluded or unverifiable items. Preserve media permissions and use Android's required user-mediated deletion/trash flows for media the app cannot delete silently. Explain this platform limit instead of claiming automatic cleanup universally works.

Implement policy, durable evidence and a safe preview first. Do not execute real device deletion during development/testing. Enabling the feature is not permission for development scripts to remove files. Use fixtures and narrow injected adapters to test negative safety cases. Unknown/expired evidence must fail closed. Never delete cloud originals in this milestone.

## 4. Folder-derived Google albums and browsing

Quality of life is the product priority: a lean native client that can map phone folder A to backend album A, not merely another uploader. Prototype browsing real provider albums and local folders alongside the optional mapping. Keep future iOS reuse in the core; no iOS implementation in this goal.

### Sticky research requirement: Google Photos album capacity

**Confirmed documentation: Google Photos albums allow up to 20,000 photos or videos (mixed media items).** See [research and native-protocol gaps](../research/google-combined-library.md), retrieved 2026-11-06. The original user-reported claim was unverified before this research. This is an album limit claim, not an account/storage quota claim. Do not present it as confirmed until researched. Fact-check current authoritative Google documentation, investigate whether photo/video/shared-album rules differ, and trace bclone/gotohp behavior and error responses at pinned revisions. Record sources, retrieval dates, certainty and any gaps. Do not create/fill a huge live album just to test a boundary. Use fixture-based boundary/error tests where possible.

Track capacity handling for BOTH FoxyPhotos and bclone. Record the bclone follow-up in its own durable project documentation/tracker if cross-repo implementation is not part of the current goal; do not silently modify that repository under this app goal. Album-capacity exhaustion must not produce infinite retries, bogus duplicate albums, false membership success or data loss. Do not choose an automatic rollover/naming policy without a documented decision; surface a clear safe capacity result if necessary. Account storage entitlement does not remove a per-album bound.

Optional toggle, default off. Research bclone's Google Photos album lookup/matching/create logic at pinned revision before implementation. Resolve/reuse existing matching albums before creation. Define collision handling for equal leaf folder names and concurrent requests, without silently generating duplicate or bogus albums. Store confirmed provider album identity privately; do not log it. Distinguish album creation, membership and upload confirmation; preserve deduplication so enabling this does not reupload confirmed media unnecessarily. No massive account-wide album changes on toggle alone; act on admitted backup batches.

## Goal completion and blockers

Work sequentially to the fullest actually functional scope supported by researched protocols. Completion means all milestone acceptance criteria above implemented, focused regressions and CI green, exact signed APK verified and delivered. Never mark a provider milestone done from compiling a placeholder/unavailable adapter. Keep status explicit when live auth/verification is unavailable.

If a milestone requires missing credentials, unavailable provider capability, or a destructive user decision, record precise evidence and the smallest blocking input. Continue independent safe milestones where possible; stop honestly for genuinely required user input rather than looping or fabricating success. Do not keep polishing already accepted UI indefinitely. The goal judge may pause/re-scope; it is not a substitute for these concrete safety and completion criteria.
