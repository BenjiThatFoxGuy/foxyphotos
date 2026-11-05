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

One GitHub Actions workflow (`.github/workflows/verify.yml`) owns the build counter:

- **Nightlies** (every push to `main`) build and test, then upload **both** a debug APK and a signed release APK as Actions artifacts.
- **Releases** (tag push `v*`) build **only** the signed release APK from that tag's commit, attest it, then attach that exact APK to a draft GitHub Release. The publisher checks the uploaded SHA-256 before publishing, then checks the published asset and release immutability again. Build jobs have no release-write permission; only the tag-only publishing job does.
- **Pull requests** build/test debug only, without release signing, attestations, or publishing. Manual runs validate builds without publishing.

Versioning is derived, not hand-edited:

- `versionCode` is exactly `GITHUB_RUN_NUMBER`, shared by nightly and tag builds because they use the **same workflow**. The existing workflow counter continues rather than resetting. Both APKs in a nightly have the same code. New runs advance it; reruns retain the original run's code, and PR/manual runs may leave gaps. Installing an older run over a newer one can still be a downgrade. Local builds use an independent marker file (`app/local.version.marker`), not the CI sequence.
- `versionName` comes from the git tag (`v0.1` produces `0.1`); non-tag builds fall back to the `VERSION` file.

Debug builds use the debug key and the package ID `gay.benjifox.foxyphotos.debug`, so they can sit alongside a release install. Release builds are signed with the release keystore, whose credentials live in GitHub secrets and are never written to disk in the repo.

CI reads back each APK's package ID, version code, version name, and signature before uploading it. A failed release upload stays a draft; an existing release is never overwritten automatically. Inspect/remove an incomplete draft before retrying publication. Immutable releases must remain enabled in the repository settings. Tags must point to commits containing this unified workflow; old tags are not rewritten automatically.

### Reproducible builds and provenance

Every build uploads a **reproducible build report** (`.github/repro-report.sh`) containing the commit, Gradle version, JDK, a dependency fingerprint, and the SHA-256 of each APK.

Each non-PR APK also carries a **GitHub artifact attestation** (Sigstore-signed SLSA provenance) binding its digest to the workflow run that produced it. The publisher verifies the release APK's attestation against the exact source commit before uploading it. Verify a downloaded APK with:

```sh
gh attestation verify app-release.apk --repo BenjiThatFoxGuy/foxyphotos
```

An attestation proves the artifact came from this repository and workflow. Neither the attestation nor the metadata report proves bit-for-bit reproducibility; that requires an independent rebuild and byte comparison.

## Licence

Licensed under the [Apache License, Version 2.0](LICENSE).
