# iCloud Photos: deferred research

User decision: defer iCloud authentication and integration. Keep only a hidden auth scaffold that explicitly returns unavailable. Do not expose it in UI, onboarding, provider selection, or backup. Do not accept or persist Apple credentials until the protocol is researched and separately authorized.

## Pinned reference

- Repository: https://github.com/icloud-photos-downloader/icloud_photos_downloader
- Commit: `3caeae584c3d8bc6fcc51a1c9f7e5413b1422fd3`
- Local reference: `/home/benji/.hermes/cache/scratch/icloudpd-reference`
- Relevant paths: `src/pyicloud_ipd/base.py`, `session.py`, `services/photos.py`.
- License: `LICENSE.md`, MIT, copyright (c) 2016 Nathan Broadbent. No upstream implementation is included in this scaffold. Preserve required notices if copying substantial upstream code later.

## Future work, not implemented

- SRP sign-in init/complete, trusted-device verification and trust, session validation and expiry.
- Encrypted session restoration through the shared CredentialStore, strict cookie scope, validated Apple HTTPS endpoints, sanitized failures, and secret-safe state.
- Photos service discovery, full library zone identity, album continuation, and asset/master joins with asset-based pagination.
- Establish support and honest unavailable states for SMS, Advanced Data Protection, and security keys before exposing them.
- Offline protocol and privacy fixtures before any live account testing.

The pinned upstream Photos service has no upload protocol. Browsing or a successful sign-in is not backup parity. Do not report successful iCloud backups or write iCloud upload-ledger entries. Upload requires separate research; deletion is out of scope. Google backup, wizard, and foreground controls stay unchanged. This platform-free Kotlin/JVM scaffold is not an iOS implementation. Leave bclone untouched.
