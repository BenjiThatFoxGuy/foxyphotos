# Gallery and backup implementation checklist

Keep this checklist alongside gallery-behavior.md and backup-policy.md. User-approved requirements must survive session changes. Check an item only after implementation and verification; design agreement is not implementation. Gallery work goes first while later backup-policy details are planned in parallel.

## Current: Modern main/profile polish

Source implementation is ready for CI; no local Gradle build, SDK download or new build cache was used. The historical checkboxes below describe earlier slices, not verification of this change.

- [x] Source: Modern-only compact Material 3 title/status bar and initials profile control; backup/policy controls and Settings moved to a scrollable native sheet.
- [x] Source: Simple at-a-glance controls, Settings cog and account dialog preserved.
- [x] Source trace: gallery queries all accessible image/video metadata independently of included backup folders; no display hashing. Loader/cancellation paths unchanged.
- [x] Source: long-press selection, contextual count/Clear/Back up selected; existing viewer backup retained.
- [x] Focused existing UI regression updated for profile privacy; Modern profile/settings/navigation regression added.
- [ ] CI compilation, tests and lint for this polish (parent delivery task).
- [ ] Signed nightly delivery after authorized push (parent delivery task).

Pipeline metadata/updater work remains a separate next task. No expanded cloud gallery, copied reference code/assets/strings, or license changes.

## First: native gallery, modes and Settings

Implementation commit: `09ff19e`. Parent verification: full check, debug assembly and lint passed; 84 tests, zero failures/errors/skips. Independent review passed after fixing selected-media lifecycle handling and cancellable provider I/O. No physical-device visual smoke test or live-media interoperability claim. Local commits only, not yet pushed.

- [x] Verify and finish real local photo/video grid, date sections, responsive thumbnails and tap-to-view/paging.
- [x] Fix review findings: consistent selected-media permission checks across lifecycle; cancellable MediaStore query and thumbnail I/O.
- [x] Modern / Simple mode choice in wizard and persisted Settings switch; existing installs retain Simple until changed.
- [x] Corner Settings cog in either mode. Settings owns logs, rerun setup, account connection and backup-folder selection.
- [x] Main screen hides account email behind explicit profile/avatar tap.
- [x] Folder list refreshes on every entry; no Refresh folders button. Explicit empty selection remains empty.
- [x] Preserve Simple manual-start background backup and pause/cancel. Navigation never starts a run.
- [x] Independent review and full build/test verification before marking this slice complete.

## Next: gallery backup actions

- [ ] Explicit individual or multiple-item backup, including items outside included folders; no implicit inclusion of neighboring media or containing folders.
- [ ] Reuse deduplication, progress, pause/cancel and truthful outcomes; serialize admission with existing runs.
- [ ] Keep folder inclusion distinct from network/power override permission.

## After gallery: Modern automatic backup and policy

- [ ] Automatic selected-folder backup in Modern; Simple remains manual.
- [ ] Wi-Fi/cellular eligibility, metered Wi-Fi, roaming, Data Saver, power saver and charging rules.
- [ ] Report real blocking reasons; explicit user pause remains separate and wins over condition changes.
- [ ] Modern-to-Simple disables future automatic work while current work may finish.
- [ ] Time-bounded immediate-upload monitoring, independent of condition exceptions and subject to selected folders.
- [ ] Back up anyway flow: duration / This batch, then Current blockers only / All backup conditions.
- [ ] Explicit confirmation of cellular/roaming scope; no bypass of permissions/auth/provider failures or OS restrictions.
- [ ] Bounded batch snapshot; new arrivals do not prolong a batch override; failures do not keep it alive indefinitely.
- [ ] Timed and indefinite user pauses; blocker appearance/disappearance never clears user pause.
- [ ] Pause/override expiry, persistence, independent clocks, explicit Resume and End override actions.
- [ ] Agree remaining presets and lifecycle/clock behavior before implementation; test policy transitions rather than relying only on UI tests.

## Standing boundaries

- Native Compose implementation from behavior requirements, no Immich code/assets/translations; Apache 2.0 unchanged.
- Android only now; reusable provider/core boundaries ready for later platform work, no claim of an iOS build.
- iCloud remains a hidden unavailable auth scaffold pending Photos-upload research. No iCloud UI or fake upload success.
- No new release tags or publishing inferred from this checklist. Distinguish local implementation, tested builds and published delivery.
