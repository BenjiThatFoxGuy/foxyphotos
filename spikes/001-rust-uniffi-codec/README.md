# 001: Rust core via UniFFI, full-pipeline spike

Throwaway. Not production code. Branch `spike/rust-uniffi-codec`.

## Question

Can the shared Google Photos protocol codec live in Rust and be called cleanly
from both Android Kotlin and iOS Swift, with the same bytes out, built and
published through the real nightly pipeline?

## Approach

Port the byte-level codec from `provider/google-core` (varints, hash check,
upload token, commit request, media-key decoding) into a Rust crate. Expose it
through UniFFI. CI builds the Rust library for the host, generates Kotlin and
Swift bindings, and exercises them with the same test vectors the Kotlin codec
uses. The app workflows still produce the installable APK and IPA nightlies
separately.

## Verdict: VALIDATED

### What worked
- Rust codec compiled on Linux and macOS; four unit tests passed with the same vectors as the Kotlin codec (`google-core/GoogleNativeProtocolTest.kt`).
- UniFFI generated idiomatic Kotlin (`encodeUploadTokenRequest(fileSize: ULong)`) and Swift (`encodeUploadTokenRequest(fileSize: UInt64) throws -> Data`) surfaces.
- Kotlin/JVM test called the generated bindings against the Rust `.so` and passed. CI run `45660197564`.
- Swift caller compiled, linked the Rust static library, ran, and printed `SWIFT_SPIKE_OK 10 bytes; media key decoded; wrong envelope rejected`. Same CI run.

### What didn't
- Needed JNA 5.12+ for the Kotlin bindings and `-Djna.library.path` at test time; Swift needed the generated modulemap passed through `-Xcc`.
- No Android NDK `.so` or iOS XCFramework packaging was exercised, and no real device install was part of the spike.

### Surprises
- Generated binding names are clean enough to call directly; no hand-written JNI or ObjC shim was required.

### Packaging verdict: VALIDATED

- Android NDK arm64 `.so` builds (`cargo ndk`) and Kotlin bindings generate from it; CI run `45669297188`.
- iOS XCFramework builds for device and simulator and generates Swift bindings; same CI run.
- Both real apps build with the Rust library linked: Android `45670764342`, iOS `45670769745`.
- APK contains `lib/arm64-v8a/libfoxy_spike_codec.so`; IPA binary contains Rust/UniFFI symbols and the spike log string.
- Still untested: a real device launch of either nightly, and non-arm64 Android ABIs.

### Recommendation for the real build
- Proceed with a Rust core and UniFFI if the goal is maximum shared code. The remaining risk is device runtime behaviour, not binding generation or packaging.
