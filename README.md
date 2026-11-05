# FoxyPhotos

FoxyPhotos is an Android-first, open-source photo and video backup app.

The first prototype backs up local device media directly to one Google Photos account. It is designed as a trustworthy, daily-use backup tool: local state and upload history are durable, progress is truthful, and confirmed content is not blindly uploaded twice.

## Status

Early prototype. The current implementation target is described in [the Android prototype plan](docs/foxyphotos-android-prototype-plan.md).

## v0.1 scope

- Android 10 and later
- Local MediaStore library
- One direct Google Photos destination
- Original-quality uploads
- Durable on-device queue, retry, and truthful backup states

A Hub, iCloud Photos support, remote browsing, deletion, and a backend are intentionally out of scope for v0.1.

## Build and release pipeline

Two GitHub Actions workflows build the app:

- **Nightlies** (`.github/workflows/verify.yml`, every push to `main`) build and test, then upload **both** a debug APK and a signed release APK.
- **Releases** (`.github/workflows/release.yml`, tag push `v*`) build **only** the signed release APK.

Versioning is derived, not hand-edited:

- `versionCode` is the GitHub Actions run number, which is monotonically increasing across every build, so an install is never rejected as a downgrade. Locally a marker file (`app/local.version.marker`) increments the same way.
- `versionName` comes from the git tag (`v0.1` produces `0.1`); non-tag builds fall back to the `VERSION` file.

Debug builds use the debug key and the package ID `gay.benjifox.foxyphotos.debug`, so they can sit alongside a release install. Release builds are signed with the release keystore, whose credentials live in GitHub secrets and are never written to disk in the repo.

### Reproducible builds and provenance

Every build uploads a **reproducible build report** (`.github/repro-report.sh`) containing the commit, Gradle version, JDK, a dependency fingerprint, and the SHA-256 of each APK.

Each APK also carries a **GitHub artifact attestation** (Sigstore-signed SLSA provenance) binding its digest to the workflow run that produced it. Verify a downloaded APK with:

```sh
gh attestation verify app-release.apk --repo BenjiThatFoxGuy/foxyphotos
```

An attestation proves the artifact came from this repository and workflow. It does not by itself prove the build is bit-for-bit reproducible; that is what the report and the pinned toolchain are for.

## Licence

Licensed under the [Apache License, Version 2.0](LICENSE).
