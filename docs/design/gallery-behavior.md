# Native gallery and app modes

## Product requirements

- Gallery is a native Android Compose experience. No iOS target is introduced.
- Simple mode remains a manual backup tool. A user starts the run; the existing foreground service continues it in the background. Navigation, permission callbacks, folder refresh and mode changes never initiate backup.
- Setup asks for Gallery or Simple mode. Settings allows switching the saved choice. Existing installations retain Simple mode unless the user chooses otherwise.
- A corner settings cog opens Settings from either mode. Settings contains mode selection, backup folders, diagnostics and rerun setup.
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

## Reference and licensing boundary

Immich mobile was studied in a separate research pass at commit `3758c4e9598773d4c237e687b37edde214c94053` to understand user-visible gallery behavior. The native implementation receives plain-language behavior requirements, not upstream code, translated algorithms, assets or strings. This is a separated research/specification workflow, not legal clean-room certification.

Research provenance: https://github.com/immich-app/immich/tree/3758c4e9598773d4c237e687b37edde214c94053/mobile

The inspected upstream root license is AGPLv3; no mobile-wide permissive override was found. FoxyPhotos remains Apache 2.0. No Immich code or assets are incorporated by this specification. Any future third-party reuse requires checking its exact license and notices before integration. A license change requires a separate decision, not an automatic consequence of studying an app's behavior.
