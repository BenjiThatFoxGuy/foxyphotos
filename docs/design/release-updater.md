# Stable release metadata and Android updates

## Distribution contract (schemaVersion 1)

The public repository is `BenjiThatFoxGuy/foxyphotos` (visibility verified during implementation).
The stable discovery URL is:

`https://api.github.com/repos/BenjiThatFoxGuy/foxyphotos/releases/latest`

The updater reads the published, non-prerelease, immutable GitHub Release and its exact
`release-manifest.json` asset. A tag's manifest URL is:

`https://github.com/BenjiThatFoxGuy/foxyphotos/releases/download/<tag>/release-manifest.json`

Existing immutable releases are not rewritten. Until a new authorized tag is built with
this pipeline, older releases without a manifest report no compatible published update.
Main/nightly builds remain Actions artifacts, not persistent releases. An installed nightly
with code 66 only offers a later published tag whose APK code is greater than 66; a newer
nightly artifact alone will not appear. Debug packages cannot install release-package updates.

The manifest contains `schemaVersion`, `package`, `versionName`, `versionCode`, `minSdk`,
`tag`, `releaseUrl`, `releaseNotesUrl`, `publication`, and `apk` with `filename`, HTTPS `url`,
`size` in bytes, lowercase `sha256`, and lowercase `signingCertificateSha256`.
GitHub Release `body` and `published_at` are the authoritative notes and publication time;
the manifest points to that exact release rather than inventing a publication timestamp
before the draft becomes public. Additional fields may be added; unknown schema versions
are rejected. This contract can support future distribution tooling, but is neither an
F-Droid repository nor an AltStore source. No iOS updater is implemented.

## Publication safety

`.github/verify-apks.py` reads aapt badging and apksigner output from the exact built APK,
after checking package, tag-derived name, and shared workflow-run-number code. It produces
the manifest next to that APK, with measured byte size, SHA-256 and signing certificate.
Both files travel in the same run's artifact; both receive provenance attestations and
both attestations are checked against the producing SHA/workflow before publication.
The publisher validates the manifest's tag/size/hash against that downloaded APK, refuses
existing drafts or published releases, attaches both files to a new draft, verifies the
exact asset set and both digests, publishes by release ID, and reads that exact ID back
including immutability. Publication does not rebuild the APK. Failed gates leave a draft
for inspection, never an automatically overwritten release.

## Native flow and boundaries

Settings in either mode offers manual Check, Download, Cancel and Install. Modern's profile
sheet and Simple's settings icon provide compact available-update indications. Launch
checks are on by default, IO-dispatched and throttled to once per 24 hours; Settings can
turn them off. Checks never download APKs or prompt for installer permission.

An app-scoped, account-independent OkHttp client streams a bounded (256 MiB maximum)
download to one app-private partial. Explicit cancellation cancels the active HTTP call.
Only HTTPS GitHub repository release URLs and the explicit GitHub asset redirect hosts
are accepted, with bounded redirects, timeouts and metadata reads. There are no tokens,
account requests, diagnostics events, shared storage or MediaStore writes.

Before offering installation and again immediately before invoking the installer:

* The version code must be strictly newer than the currently installed package.
* The package, human version, minimum SDK and code must match the manifest/actual APK.
* The exact file length and streamed SHA-256 must match.
* PackageManager must parse signing information; the APK's current certificate must match
  the manifest and its verified signing history must include the installed current signer.
  Multiple-signature APKs are deliberately rejected; normal single-signer rotation is supported.

The platform-free `core:model` release contract/stream and identity verification gates have
no Android dependency. Android owns PackageManager parsing, permissions, cache and intents.
This is JVM-portable design, not an assertion that Kotlin/Native or iOS builds exist.

Install requests per-source permission only when needed using
`ACTION_MANAGE_UNKNOWN_APP_SOURCES` and the app's `package:` URI. The chosen flow is persisted;
returning with authorization resumes it, including after process recreation, only after
reverifying the preserved APK. It never auto-downloads. A narrow, separate FileProvider
exposes only `cache/updates/apks/`, with temporary read grant and APK MIME type. Android's
system installer still requires user confirmation. Denial leaves the Install action usable.
A single complete APK is retained for up to two days; partials/orphans are removed at startup.
Android may evict cache files; retry download if that happens. Downloads are not resumable
across process death; verified complete downloads are.

## Verification and CI

Local: `python3 -m unittest discover -s .github/tests -v` and `git diff --check`.
No local Gradle, Android SDK installation, or dependency cache was used.
CI must run the existing `check assembleDebug assembleRelease` main gate, including
`:core:model:test` and Android unit/lint checks. New JVM test sources cover version/SDK/package
eligibility, origin/path restrictions, truncated/oversized/corrupt streams, cancellation and
signing-lineage identity gates. These JVM tests were not executed locally.
A device smoke test should cover manual discovery/download/cancel, permission denial and
return, process recreation with a ready APK, and the final system install confirmation.
A live tagged publication is not exercised without explicit tag authorization.

Android API references:
* https://developer.android.com/reference/android/content/pm/SigningInfo
* https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()
* https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES
* https://developer.android.com/reference/androidx/core/content/FileProvider
