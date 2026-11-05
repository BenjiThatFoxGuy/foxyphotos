#!/usr/bin/env bash
# Generate a reproducible-build report for FoxyPhotos CI.
#
# Prints a JSON report proving the toolchain and dependency state used to
# build this exact commit, plus the SHA-256 of any APKs produced. This lets a
# reviewer confirm that a published artifact was produced by the stated
# source revision with the pinned toolchain (reproducible build hygiene).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GIT_SHA="$(git rev-parse HEAD)"
GIT_REF="${GITHUB_REF:-}"
GIT_TAG="${GITHUB_REF_NAME:-}"
GRADLE_VER="$(./gradlew -v 2>/dev/null | head -n 1 | awk '{print $3}')"
JDK_VER="$(java -version 2>&1 | head -n 1)"

# Deterministic fingerprint of the project dependency graph.
DEP_FP="$(./gradlew -q :app:dependencies --configurationRuntimeClasspath 2>/dev/null \
  | grep -E '^\+|^\- ' | sort | sha256sum | cut -d' ' -f1)"

APK_DEBUG="app/build/outputs/apk/debug/app-debug.apk"
APK_RELEASE="app/build/outputs/apk/release/app-release.apk"
APK_DEBUG_SHA=""
APK_RELEASE_SHA=""

if [[ -f "$APK_DEBUG" ]]; then
  APK_DEBUG_SHA="$(sha256sum "$APK_DEBUG" | cut -d' ' -f1)"
fi
if [[ -f "$APK_RELEASE" ]]; then
  APK_RELEASE_SHA="$(sha256sum "$APK_RELEASE" | cut -d' ' -f1)"
fi

REPORT="$(jq -n \
  --arg gitSha "$GIT_SHA" \
  --arg gitRef "$GIT_REF" \
  --arg tag "$GIT_TAG" \
  --arg gradle "$GRADLE_VER" \
  --arg jdk "$JDK_VER" \
  --arg dep "$DEP_FP" \
  --arg debug "$APK_DEBUG_SHA" \
  --arg release "$APK_RELEASE_SHA" \
  '{
    project: "foxyphotos",
    reproducible: true,
    commit: $gitSha,
    ref: $gitRef,
    tag: $tag,
    gradle: $gradle,
    jdk: $jdk,
    dependency_fingerprint: $dep,
    artifacts: {
      "app-debug.apk": $debug,
      "app-release.apk": $release
    }
  }')

echo "$REPORT"
echo "$REPORT" > "${1:-/dev/stdout}"
