# ADR 0001: Start with a direct-Google Android prototype

- **Status:** Accepted
- **Date:** 2026-11-05

## Context

FoxyPhotos is intended to become a dependable, user-friendly open-source photo backup app. The wider product direction leaves room for direct provider destinations, an optional Hub, and later iCloud Photos support.

A first implementation needs to deliver useful backup behaviour without prematurely committing the app to a backend, cross-platform FFI, or a shared native runtime.

## Decision

Build a native Android 10+ application for the first prototype.

- Kotlin, Jetpack Compose, and Material 3 provide the UI.
- Room stores durable product state, DataStore stores preferences, and Android Keystore protects provider credentials.
- WorkManager schedules scans and transfers.
- One directly configured Google Photos destination is the only provider target in v0.1.
- The backup model remains content-plus-destination-task based, so later providers and a Hub can use the same queue boundary.
- The Google integration is an unofficial implementation informed by bclone's `gotohp` behaviour. It is not a bclone binary integration and must not use remote listing as the backup state authority.

## Consequences

The prototype can be tested and used without operating a service. It deliberately does not include Hub pairing, multi-device sync, iCloud Photos uploads, remote browsing, downloads, or deletion.

The app must be candid in setup and support text that its Google Photos integration is unofficial and may stop working.
