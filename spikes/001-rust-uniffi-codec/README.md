# 001: Rust core via UniFFI, full-pipeline spike

Throwaway. Not production code. Branch `spike/rust-uniffi-codec`.

## Question

Can the shared Google Photos protocol codec live in Rust and be called cleanly
from both Android Kotlin and iOS Swift, with the same bytes out, built and
published through the real nightly pipeline?

## Approach

Port the byte-level codec from `provider/google-core` (varints, hash check,
upload token, commit request, media-key decoding) into a Rust crate. Expose it
through UniFFI. CI builds the Rust library for the host and for iOS/Android,
generates Kotlin and Swift bindings, exercises them with the same test vectors
the Kotlin codec uses, and assembles installable APK/IPA nightlies.

## Verdict

Pending CI run. Fill in VALIDATED / PARTIAL / INVALIDATED with evidence.
