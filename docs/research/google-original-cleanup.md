# Native Google download and verified cleanup — 2026-11-06

Read-only reference inspection: bclone source `f6ad6b63ec67694011c686f246f8832cb0e6fb9a`,
local HEAD `98cbe5c68d830020249911c61fad3fb49e1dd2df` (documentation follow-up).
`backend/gotohp/api/client.go:528-560` DownloadMedia exchanges the native Android
credential, GETs `https://lh3.googleusercontent.com/p/{mediaKey}=d` with bearer
and configured User-Agent, accepts 2xx and returns the body stream. Object.Open
in `gotohp.go` delegates to this route. There is no response proof that this is
byte-identical to the uploaded original. No live credential or account was read.

Current authoritative supporting documentation retrieved 2026-11-06:
https://developers.google.com/photos/library/guides/access-media-items
states that photo `=d` downloads retain Exif **except location metadata**.
Video `=dv` is a high quality **transcoded** version; `=d` on a video is a thumbnail.
This public API documentation is not proof of private native endpoint behavior.
Consequently we implement bclone's actual route but never treat the suffix,
HTTP success, filename, metadata or provider hash as original verification.
Only downloaded byte count plus SHA-256 equality against the unchanged unredacted
device original qualifies. Videos, location-stripped photos, edited/compressed
representations and unsupported routes generally cannot qualify and are preserved.
No synthetic evidence or fallback rendition is used. Live native compatibility
and availability of byte-identical downloads remain unverified.

Android reference retrieved 2026-11-06:
https://developer.android.com/reference/android/provider/MediaStore#createWriteRequest(android.content.ContentResolver,%20java.util.Collection%3Candroid.net.Uri%3E)
The system-mediated write grant explicitly permits subsequent IS_TRASHED updates.
We choose that flow instead of immediately executing createTrashRequest: after
consent returns, we can download again and recheck local content and policy before
trashing. Android 11+ only; Android 10 cleanup disabled. No permanent delete fallback.
Original reads use setRequireOriginal and ACCESS_MEDIA_LOCATION; denied access
cannot authorize deleting an unredacted original using redacted bytes.

## Implemented limits

Default off; independent private folder whitelist starts empty. Only new committed
uploads while enabled/whitelisted on Android 11+ can create durable private evidence;
presence checks and the old dedup ledger never become cleanup receipts. Evidence
is written only if local identity and full unredacted hash still match the upload
snapshot after commit. No original permission, changed media or failed persistence
leaves backup successful but creates no cleanup authority. Evidence records run ID,
time, media identity (URI, MediaStore database version, size, modified generation,
modified/added times, folder), exact hash and returned remote key. No such values
are logged. MediaStore generations/database versions provide conservative identity;
URI alone is not identity. Permission or database changes fail closed. A separate
durable run-ended marker is required: in-flight uploads and process death before
recording the end of the upload invocation cannot qualify.

A separate explicit preview run checks receipts from earlier upload runs only,
upload age <=30 days, maximum 20 candidates per explicit preview page (next/start-over controls), 256 MiB each, 32 KiB
streaming buffers, no media staging. Network has a 30-second call deadline,
no redirects and cancellable response consumption. Verification is session-only,
expires after 10 minutes and disappears on leaving/recreation. Receipt persistence
is durable across restarts, not remote-verification persistence. Local folder scan
uses the existing cancellable MediaStore scanner (enumerates accessible folder
metadata; no network crawl). No automatic deletion/background cleanup.

Each item has a labelled preview result and explicit trash confirmation, then the
native write permission dialog. After permission, download bytes again, compare
hash, requery and hash local original, requery identity, recheck receipt, current
opt-in/whitelist and expiry, then condition the trash update on current generation,
size and nonpending/nontrashed state. No backup queue mutations. Cancellation,
missing/expired evidence, missing/auth-expired remote, changed local file, mismatch
or revoked whitelist cannot reach trash. Android cannot provide an atomic hash-and-
trash operation against another process writing raw file bytes without updating
MediaStore; that platform race remains a limit. The generation-conditioned update
narrows normal MediaStore mutation races; it does not claim a filesystem lock.
System write grants may persist for the activity lifetime; they never override
FoxyPhotos rechecks. Interrupted mutation has an honest uncertain UI message.

Fixtures test policy admission, age/future evidence, same-run rejection, changed
identity/hash/size/whitelist, bounded hashing/truncation/cancellation, download
response status/size and execution failures before the injected trash boundary.
No personal media deletion, cloud writes/deletion or live device test performed.
