# 2. Kotlin Multiplatform and an iOS shell

Date: 2026-11-12

## Status

Accepted

## Context

FoxyPhotos started as an Android-only app. The backup domain - models, policies,
verified cleanup, provider contracts - is portable Kotlin with no Android imports.
An iOS app should share that core rather than reimplement it.

iOS builds need macOS and Xcode. There is no local Mac; hino is Linux. Kotlin/Native
also cannot link Apple targets on a non-macOS host, so iOS targets have to be
guarded or the Android build breaks.

## Decision

The portable modules become Kotlin Multiplatform modules.

- `core:platform` (new) - the only expect/actual seam left is wall-clock time.
  ISO-8601 rendering and SHA-256 are pure Kotlin in `commonMain`, so every target
  gets byte-identical output and the JVM can prove the digest against
  `java.security.MessageDigest`.
- `core:model` - depends on `core:platform`.
- `core:backup` - depends on `core:model` and `core:platform`.
- `provider:contract` and `provider:foundation` - converted. `CredentialReference`
  and `HttpStatus` became plain data classes: `@JvmInline` is absent from the
  common standard library, so value classes cannot be declared in shared code and
  still compile for every target.

Each declares `jvm()` unconditionally, plus `iosSimulatorArm64()` and
`iosArm64()` only when the host is macOS. There is deliberately no
`androidTarget()`: AGP 9 refuses to sit alongside the KMP plugin in the same
project, and these modules have no Android code anyway, so the Android app
consumes their JVM variant exactly as it did before.

`core:updater` (new) holds the release manifest models. They are updater-shaped
rather than backup-domain, so they stay JVM for now and the Android app depends on
the new module.

The iOS app is a SwiftUI shell in `iosApp/`, generated with XcodeGen, linking the
Kotlin frameworks Gradle produces. Android CI is unchanged; a macOS job builds the
frameworks and the shell.

## Consequences

- Android keeps building and behaving exactly as before.
- Providers stay JVM until a multiplatform HTTP stack is chosen.
- Device (arm64) frameworks are declared but not built in CI yet; CI builds the
  simulator slice.
- Hashing and diagnostic timestamps go through the shared seam, so iOS and
  Android produce identical values.
