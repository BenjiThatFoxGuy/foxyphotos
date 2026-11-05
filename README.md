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

## Licence

Licensed under the [Apache License, Version 2.0](LICENSE).
