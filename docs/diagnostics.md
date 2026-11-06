# Private in-app diagnostics

View logs, Export and Share use the same app-private persistent sink. The shared
400-event / 256-KiB bound covers old provider upload records and new typed updater,
portable-backup and Android scheduler records together. Existing v1 records remain
readable. Storage stays under noBackupFilesDir with atomic replacement and a
memory fallback. Operational writes use a bounded 128-entry IO queue; saturation
can drop events rather than block work. Installer handoff awaits the writer off
main, so download/save/verification/calling-installer records reach storage before
leaving the app (best effort if storage fails). Replacing the app may kill its old
process: no post-install success is inferred or required.

Updater checks log real network discovery even when no upgrade exists: automatic
or manual request, throttling/disabled/pending/busy, contacting GitHub, each HTTP
response, release discovery, manifest lookup/rejection, validated metadata,
advertised APK lookup/size, and current-versus-available version comparison.
Advertised APK discovery is not downloaded-byte verification. Download events
include start, quarter-size progress, completion, saved artifact, and separate
size/hash/package/version/SDK/signature gates. Failures have a typed category;
there are no automatic application-level network retries. Permission return is
reported only for a requested permission flow. ACTION_VIEW gives no reliable
installation/cancellation result: handoff never means installation succeeded.
Restored pending artifacts go through the same verification gates.

Portable core accepts an optional no-op diagnostic sink. It records first hashing
and upload-admission stages per pass and typed result counts; Android records scan,
plan, final condition wait versus user pause, and service-session transitions.
Scheduler records registration-result changes and job starts/stops; repeated skip
reasons are suppressed until the reason changes or work starts. Busy reasons
identify scheduled-job versus service ownership, never device/run identifiers.
No media identifiers, filenames, paths, content hashes, certificate fingerprints,
credentials, account names, URLs, headers, response bodies or exception text enter
these events. Version names are accepted only as bounded numeric release versions;
unknown formats are omitted rather than redacted into arbitrary strings.

## Coverage and limits

Focused JUnit/Robolectric sources cover fake updater no-update discovery, throttle,
missing manifest, network error privacy; mixed old/new persistence and retention;
portable failure counts/deferred admission and throwing sinks; scheduler skip-change suppression. No live cloud calls.
Local verification is static only by request: no Gradle, SDK or dependency-cache
creation. Compilation, lint and tests await CI; device installer/permission and
process-replacement behavior are not exercised locally. Logs are best effort,
not an audit trail; explicit user exports remain outside app retention/clear.
