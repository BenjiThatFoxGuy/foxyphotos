# Experimental library and storage roadmap

## Execution contract

Use Codex CLI for implementation and CI iteration. Hermes supervises milestones, verifies exact artifacts, and delivers signed release nightlies on Telegram. Do not run local Gradle, download an Android SDK, or recreate dependency caches on this disk-constrained host. No repeated review fan-out. Keep commits small and preserve source portability: platform-neutral policy/provider contracts in core, Android UI/storage/install adapters on Android. No iOS implementation now.

Finish the already running gallery interaction refresh first; coordinate with that Codex process before touching its files. Keep Simple at-a-glance UI and Modern profile sheet separation. Subjective final UI polish and device acceptance come later from Benji. Do not treat compilation as proof of live provider behavior.

Deliver each usable green slice rather than waiting for this entire roadmap. Do not create release tags without authorization. Log meaningful safe lifecycle events in View logs. Keep credentials encrypted and behind existing helpers; never log or commit secrets, account details, media names/paths, content hashes, signed URLs or raw responses.

## Product milestones (ordered)

- [ ] 0. Finish current gallery refresh and deliver its verified signed nightly.
- [ ] 1. Experimental combined local + Google Photos library, explicitly enabled in Settings and off by default.
- [ ] 2. After milestone 1 is implemented and builds green, add a separate opt-in experimental local + Google Photos + iCloud library view, off by default.
- [ ] 3. Experimental explicit storage-to-storage transfer batches, initially iCloud download to Google Photos upload. No iCloud upload requirement.
- [ ] 4. Experimental verified local cleanup, off by default, with its own device-local folder whitelist and later-run remote content verification.
- [ ] 5. Optional deriving Google Photos album names from local folder names, with safe existing-album lookup/reuse before creation.

Record concrete implementation, CI evidence, delivery and remaining provider limitations below each milestone as work progresses. Research may show prerequisites that require reordering supporting implementation, but do not silently substitute fake provider data for a working milestone.

## 1. Combined local + Google library

Research actual existing bclone Google Photos read/list/download capability before designing adapters; pin reference revisions and trace real request and response handling. Do not assume the upload adapter already supports a remote library. Use existing credentials only through authorized private storage. No bulk live account crawling without bounded operation limits. Ordinary read-only listing needed for an explicitly enabled feature is in scope; do not run destructive account operations during development.

Provide real listings/previews, source badges and honest loading/partial/error states; local media remains all accessible items regardless routine backup-folder selection. Use stable provider identities. Merge proven equivalents only; a filename match is not proof of equality. No fake remote entries or invented backup badges. Bound paging, caching and thumbnail work. Preserve ordinary local-only mode as default and existing backup behavior.

## 2. Add iCloud read-only source

This explicitly reopens earlier deferred iCloud work ONLY for authenticating, listing and reading/downloading photos needed by the opt-in experiment and future transfers. No iCloud Photos upload implementation, no Apple Drive substitution, no iOS target. Keep normal/local mode free of iCloud prompts. Research bclone and icloudpd/pyicloud references, licenses and credential/challenge flows before implementing. Handle 2FA and expired sessions honestly; never claim unsupported auth or listing works. No new credential solicitation while Benji is busy: implement supported flows and identify exact missing user input when unavoidable.

The third view must be gated separately and only added after the Google combined view builds. Show unavailable/error state if auth is absent, not synthetic content. Test adapter behavior with offline fixtures; report live verification separately.

## 3. Explicit transfer batches

The long-term use case is freeing paid iCloud capacity by copying chosen batches to Google Photos. Start copy-first: explicit source selection, bounded queue, progress, pause/cancel, retries and truthful confirmed/uncertain outcomes. Download to bounded app-private staging; verify source bytes where feasible before uploading through existing Google adapter. Do not automatically delete source cloud assets. Freeing source capacity requires a separately explicit destructive user confirmation and verified destination safety; do not infer that authorization from a transfer request. Unsupported Apple upload is not a blocker for iCloud-to-Google direction.

## 4. Verified local cleanup

Separate global opt-in and device-local folder whitelist, both off/empty by default. Backup-folder selection does not grant deletion authorization. Only items successfully uploaded in an earlier run become verification candidates in a later run. Example: upload photo A in run 1; in run 2 fetch the actual corresponding remote media bytes and compare a content hash with current local bytes. Upload success, existence lookup, thumbnail, remote metadata, filename or provider hash claim alone is insufficient.

Cleanup requires a byte-for-byte content hash match from an actual remote download plus unchanged local identity/content and current whitelist authorization at deletion time. If Google serves a transformed representation, exact verification is unavailable: retain local file and explain safely. No deletion for uncertain, failed, unavailable, changed, excluded or unverifiable items. Preserve media permissions and use Android's required user-mediated deletion/trash flows for media the app cannot delete silently. Explain this platform limit instead of claiming automatic cleanup universally works.

Implement policy, durable evidence and a safe preview first. Do not execute real device deletion during development/testing. Enabling the feature is not permission for development scripts to remove files. Use fixtures and narrow injected adapters to test negative safety cases. Unknown/expired evidence must fail closed. Never delete cloud originals in this milestone.

## 5. Folder-derived Google albums

Optional toggle, default off. Research bclone's Google Photos album lookup/matching/create logic at pinned revision before implementation. Resolve/reuse existing matching albums before creation. Define collision handling for equal leaf folder names and concurrent requests, without silently generating duplicate or bogus albums. Store confirmed provider album identity privately; do not log it. Distinguish album creation, membership and upload confirmation; preserve deduplication so enabling this does not reupload confirmed media unnecessarily. No massive account-wide album changes on toggle alone; act on admitted backup batches.

## Goal completion and blockers

Work sequentially to the fullest actually functional scope supported by researched protocols. Completion means all milestone acceptance criteria above implemented, focused regressions and CI green, exact signed APK verified and delivered. Never mark a provider milestone done from compiling a placeholder/unavailable adapter. Keep status explicit when live auth/verification is unavailable.

If a milestone requires missing credentials, unavailable provider capability, or a destructive user decision, record precise evidence and the smallest blocking input. Continue independent safe milestones where possible; stop honestly for genuinely required user input rather than looping or fabricating success. Do not keep polishing already accepted UI indefinitely. The goal judge may pause/re-scope; it is not a substitute for these concrete safety and completion criteria.
