# Google combined library research — 2026-11-06

Reference: local bclone `f6ad6b63ec67694011c686f246f8832cb0e6fb9a` (read only).
`backend/gotohp/api/client.go` ListLibrary sends the captured initial field mask
and then resumes with field 1.4 to native endpoint `18047484249733410717`.
`api/protobuf.go` DecodeLibraryResponse reads container 1, resume 1.1,
items 1.2, media key item.1, metadata item.2, epoch seconds metadata.7,
and video discriminator item.5.1. Album entries are separate container.3;
we do not misclassify them as photos. No filename equivalence is inferred.
The native read API uses the existing Android credential exchange and extended
headers, not public OAuth Library API scopes. No extra account credential is requested.

bclone DownloadMedia uses authenticated `lh3.googleusercontent.com/p/{mediaKey}=d`.
FoxyPhotos uses the same route with bounded `=w320-h320` and `=w1600-h1600`
display renditions. Google documents width/height rendition parameters at
https://developers.google.com/photos/library/guides/access-media-items
(retrieved 2026-11-06). That public documentation is supporting evidence for
rendition syntax, **not proof** that every native media key resolves this way.
Live account listing, thumbnail availability and auth compatibility still require
device acceptance. Preview failures stay visible and retryable. Video playback
is not implemented in this experimental view; video previews are labelled.
No full original download or byte-equality claim is made from a preview.

## Bounded operation and privacy

Settings opt-in defaults off and opens a separate native grid from either mode.
Local access queries all accessible MediaStore items independently of backup folders.
One initial Google page, then explicit next-page presses; at most ten pages and
5,000 distinct Google items per view session. Eight MiB maximum decoded page body;
provider-defined page size has no proven request-side limit. Oversized pages fail
honestly rather than silently dropping items. Repeated cursor stops paging.
Thumbnail work is lazy, three concurrent jobs, two MiB network cap (eight MiB
large preview), sampled decode and 12 MiB session bitmap LRU. No disk cache.
HTTP calls time out after 30 seconds, cancel on leaving and refuse redirects.
Google IDs/cursors/cache live only within the view; no request URLs, errors,
account data or credentials enter logs. Existing encrypted credential helper is
used, loaded byte arrays cleared, safe lifecycle events logged by closed vocabulary.
No cloud writes, live development account requests or bulk crawler were run.

## Album capacity and matching groundwork

Confirmed by authoritative sources retrieved 2026-11-06:
- https://support.google.com/photos/answer/6128849?hl=en — up to 20,000 photos or videos per album.
- https://developers.google.com/photos/library/guides/manage-albums — 20,000 media items; requests beyond capacity fail.

This is one mixed photo/video item count, not storage entitlement. Neither source
establishes a separate shared-album allowance; do not assume sharing increases it.
Public API failure semantics do not establish native protobuf error envelopes.
No boundary was tested against a live account.

bclone `gotohp.go` addToAlbum refreshes the library cache before resolving titles,
then creates only if missing; its title-to-key map cannot represent ambiguous equal
titles. Creation is outside a single atomic resolve/create critical section.
`api/client.go` AddMediaToAlbum ignores successful response bytes and has no capacity
classification; CreateAlbum only decodes the returned key. No native capacity
fixture/error schema or preflight item count is implemented. HTTP success alone
cannot establish correct membership at a limit. FoxyPhotos currently has no album
writes, so it cannot trigger album exhaustion in this slice.

bclone follow-up: add native capacity/error fixtures and typed terminal capacity
results, verify membership, preserve already-uploaded media identity on association
failure, avoid retries/duplicate creation and define ambiguous-title behavior.
Track in the bclone project before enabling folder-to-album writes; this app task
does not alter that repository or invent a rollover policy. Folder-derived matching
must retain full local folder identity, resolve unique existing provider identity,
block ambiguous equal titles and serialize admitted create operations.
