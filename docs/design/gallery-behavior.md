# Native gallery and app modes

## Product requirements

- Modern mode is a native Android Compose gallery. No iOS target is introduced.
- Modern mode is intended to back up selected folders automatically, with pause support. Build the initial gallery first; automatic scheduling and its conditions are a later increment after the interactions are agreed. Conditions to design: Wi-Fi, cellular data, roaming, Data Saver, power saver and charging. Do not present automatic backup as implemented by the initial gallery.
- Simple mode remains a manual backup tool. A user starts the run; the existing foreground service continues it in the background. Navigation, permission callbacks, folder refresh and mode changes never initiate backup.
- Setup asks for Gallery or Simple mode. Settings allows switching the saved choice. Existing installations retain Simple mode unless the user chooses otherwise.
- Simple retains its corner Settings cog. Modern opens Settings from the profile sheet. Settings contains mode selection, backup folders, diagnostics and rerun setup.
- Backup folders refresh automatically each time their screen is entered. No manual refresh-folders button.
- The main screen shows an avatar, not the account email. An explicit account tap reveals account details.
- Google backup lifecycle and pause/cancel controls remain intact. The hidden iCloud scaffold stays hidden.

## First local-gallery increment

- Read actual accessible device media. Show newest-first thumbnails grouped by local calendar date, with locale-aware headings and separate rows per group.
- Adapt the grid to available width. Use asynchronous size-bounded thumbnails rather than full-resolution images in cells.
- Open tapped media in a viewer without thumbnail cropping. Retain the originating collection order and grid position when returning.
- Distinguish initial loading, permission denial, limited access, genuine empty results, query errors and unreadable individual media.
- Do not invent cloud items, provider-copy badges or successful backup indicators. Remote browsing and automatic scheduling are not implied by this increment.
- Advanced selection, pinch density, editing and elaborate viewer gestures can follow the initial usable slice.

## Modern main/profile polish

- Keep the main gallery compact: native Material 3 title/status bar and initials avatar, with no standalone Settings cog or backup-control wall.
- Open a native scrollable profile sheet for account email, existing backup status/counts/progress, start/pause/resume/cancel, timed pauses, overrides, immediate watching and Settings. Email stays off the gallery.
- Simple deliberately retains all-at-a-glance backup/policy controls and its existing Settings/account entry points.
- Display all accessible local images and videos independently of the automatic-backup folder allow-list. `LocalGallery` calls `queryLocalGallery`, which queries image/video metadata without folder predicates or content reads/hashing. Android media grants still bound visibility.
- Long press enters selection. Only then show selection count, Clear and Back up selected; Back clears selection. Ordinary taps retain the existing viewer and its Back up action.
- Preserve host-owned grid scroll state, cancellable MediaStore/thumbnail work and selected-media grant handling. No cloud-gallery expansion or unsupported confirmation badges.
- Release pipeline/metadata and in-app updater are a separate next task, not part of this polish.

## Reference and licensing boundary

Immich mobile was studied in a separate research pass at commit `3758c4e9598773d4c237e687b37edde214c94053` to understand user-visible gallery behavior. The native implementation receives plain-language behavior requirements, not upstream code, translated algorithms, assets or strings. This is a separated research/specification workflow, not legal clean-room certification.

Research provenance: https://github.com/immich-app/immich/tree/3758c4e9598773d4c237e687b37edde214c94053/mobile

The inspected upstream root license is AGPLv3; no mobile-wide permissive override was found. FoxyPhotos remains Apache 2.0. No Immich code or assets are incorporated by this specification. Any future third-party reuse requires checking its exact license and notices before integration. A license change requires a separate decision, not an automatic consequence of studying an app's behavior.

## Viewer interaction refresh

- Photos support native pinch zoom (1–5×), bounded pan and double tap between fit and 2.5× at the tapped point. At fit, horizontal swipes page; while zoomed, gestures pan the photo. Return to fit to page. Paging resets zoom/pan.
- Viewer shows collection position and retains the current media URI on configuration changes. Settings round trips retain gallery selection and viewer state; the host retains grid position.
- Selection stays URI-based, drops inaccessible items after a query completes and exits when no selected items remain. Upload still requires an explicit Back up action.
- Thumbnail loading and failed reads have distinct feedback; failed native video playback reports an inline error.
