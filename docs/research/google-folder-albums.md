# Folder-derived Google albums — 2026-11-06

## Protocol evidence

Read-only reference inspection, without personal account requests:

- bclone `f6ad6b63ec67694011c686f246f8832cb0e6fb9a`,
  `backend/gotohp/api/{client.go,protobuf.go}`: library page endpoint
  `18047484249733410717`, container `1`, albums `1.3`, native key `.1`,
  title `.2.5`; create endpoint `8386163679468898444`, create response key
  `1.1`; add endpoint `484917746253879292`. Creation can include initial
  media in repeated field `4` (entry `1.1`). Empty creation is unnecessary.
- [gpmc](https://github.com/xob0t/gpmc/tree/94b1b267ca6819da542d4330a7dd216747eb0437)
  `gpmc/db_update_parser.py`, commented `_parse_collection_item`: collection
  metadata item count `2.7`, web album ID `4.2.3`. These fields have source
  evidence, **not live Android validation**. Absent counts stay unknown rather
  than treating protobuf omission as proof of an empty album. Web ID and native
  key are different identities; the browser link uses only a returned web ID.
- gotohp `2a5c1b9678281b6a2ff08661a53ea1dc6b1e2edc` inspected as supporting
  source. Neither reference implements a native album-content reader or a
  membership response decoder. Do not invent either from HTTP 200.

Google's [Android album help](https://support.google.com/photos/answer/6128849?hl=en)
and [manage-albums guide](https://developers.google.com/photos/library/guides/manage-albums)
were fetched again 2026-11-06: **20,000 photos or videos / media items per album**.
The API guide states requests beyond capacity fail. This is a mixed-item album
bound, independent of storage entitlement. No separate higher shared-album bound
was established. Public API errors do not establish native error envelopes.
Existing durable bclone follow-up remains `bclone/docs/research/gotohp-album-capacity.md`;
this milestone does not modify that repository.

## Implemented behavior and write safety

Settings has an independent default-off folder mapping switch and real native
Google album metadata browsing. Listing is explicit, bounded to ten pages and
5,000 albums per session; partial, auth/network, missing-count and missing-link
states are visible. Local folder browsing queries actual accessible MediaStore
items and opens native thumbnail/large-preview grids. Google album contents open
in Google Photos through the provider-returned web ID. **Google album contents
are not rendered in-app**; local contents are not represented as remote members.

Only admitted backup items participate. Both successful uploads and durable-ledger
hits reach a separate post-confirmation hook. Before album writes, read-only SHA-1
presence lookup resolves the key in the *current* authenticated Google account:
the older upload ledger is not account-scoped and must not authorize cross-account
album association. No confirmed media is reuploaded to obtain an album membership.
No presence receipt qualifies for cleanup.

Mapping retains actual MediaStore volume plus full relative folder path. Exact,
unique title matching uses the prior portable policy. Complete bounded enumeration
is required before lookup or creation; errors, repeated cursors, conflicting
identity metadata and truncation fail closed. Duplicate exact titles, equal leaf
names among admitted folders, and a durable title claim by another volume/folder
block writes. Persisted native mappings must still match a unique current title
and identity; disappearance/rename never creates a replacement automatically.

A process-wide mutex serializes resolver/write operations across app instances.
The journal is app-private and scoped by account identity. Checked synchronous
commits persist creation intent before the non-idempotent request, and persist
confirmed creation key plus initial unresolved membership atomically afterward.
Lost/malformed creation responses remain uncertain; matching by title alone
cannot repair them or authorize another creation. The create request includes
one confirmed item. This acknowledges creation identity separately from the
unconfirmed membership of that included item.

Existing albums require a known count and conservative room under 20,000 including
durable reservations. Unknown/full count returns safely with no association write,
no rollover and no duplicate album creation. Each subsequent membership request
reserves capacity and records candidate/media intent atomically **before** network
I/O. HTTP acknowledgement remains unconfirmed membership. Failure/timeout/cancel
retains the reservation/intent. Neither uncertain nor acknowledged requests are
automatically replayed; application and OkHttp write retries are disabled. Repeated
backup passes skip journaled candidates and advance remaining candidates, bounded
to twenty current-account presence checks and twenty album writes per pass.

No client-only lock can exclude Google app or other external writers between
lookup and create/add. There is no established native compare-and-create or
idempotency primitive. External title/capacity races can therefore yield a duplicate
creation or rejected/uncertain association. Later exact lookup fails closed on
ambiguity; no membership success is inferred and no repair/rollover is attempted.
The reservation overcounts when Google already included an acknowledged item;
this sacrifices capacity rather than silently risking overflow. Native capacity
errors are not specifically classified: the request becomes terminal uncertain,
never a reason to create another album or retry forever.

## Remaining limits

No live Google/device acceptance. Membership confirmation, in-app Google album
contents and explicit journal reconciliation are remaining protocol/UI work.
The switch is usable for one-attempt organization requests, **not verified album
preservation**, and must not qualify a destructive transfer. The latest safe
status appears in Settings; lifecycle events in View logs omit all media/folder
names, paths, account details, IDs, hashes and raw responses. The metadata/content
browser is a temporary feature surface; the pinned main-library mode selector
and subjective integration polish remain deferred. No iCloud writes/deletion,
no iOS and no local Gradle/SDK/emulator operation were performed.
