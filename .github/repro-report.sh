#!/usr/bin/env bash
# Generate a reproducible-build report for FoxyPhotos CI.
#
# Emits a JSON report capturing the exact source revision, toolchain and
# artifact hashes for a build. Origin is verified separately by attestations;
# this report alone is not proof of origin or bit-for-bit reproducibility.
#
# Every gradle invocation is best-effort: the report must never fail a build.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GIT_SHA="$(git rev-parse HEAD)"
GIT_REF="${GITHUB_REF:-}"
GIT_TAG="${GITHUB_REF_NAME:-}"

# Gradle version from the wrapper distribution URL (no daemon spin-up needed).
GRADLE_VER="$(sed -n 's/.*gradle-\([0-9][0-9.]*\)-bin\.zip.*/\1/p' \
  gradle/wrapper/gradle-wrapper.properties 2>/dev/null || true)"
if [[ -z "$GRADLE_VER" ]]; then
  GRADLE_VER="unknown"
fi

JDK_VER="$(java -version 2>&1 | head -n 1)"

# Best-effort fingerprint of the resolved Android variant dependency graph.
DEP_CONFIG="releaseRuntimeClasspath"
if [[ "${GITHUB_EVENT_NAME:-}" == "pull_request" ]]; then
  DEP_CONFIG="debugRuntimeClasspath"
fi
DEP_FILE="$(mktemp)"
trap 'rm -f "$DEP_FILE"' EXIT
DEP_FP="unavailable"
if ./gradlew -q :app:dependencies --configuration "$DEP_CONFIG" > "$DEP_FILE" 2>/dev/null; then
  if ! grep -q 'FAILED' "$DEP_FILE"; then
    DEP_FP="$(sort "$DEP_FILE" | sha256sum | cut -d' ' -f1)"
  fi
fi

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

REPORT="$(python3 - "$GIT_SHA" "$GIT_REF" "$GIT_TAG" "$GRADLE_VER" "$JDK_VER" \
  "$DEP_FP" "$APK_DEBUG_SHA" "$APK_RELEASE_SHA" <<'PY'
import json
import os
import sys

git_sha, git_ref, git_tag, gradle, jdk, dep, debug_sha, release_sha = sys.argv[1:9]
print(json.dumps({
    "project": "foxyphotos",
    "reproducibility_verified": False,
    "version_code": os.environ.get("GITHUB_RUN_NUMBER"),
    "commit": git_sha,
    "ref": git_ref,
    "tag": git_tag,
    "gradle": gradle,
    "jdk": jdk,
    "dependency_fingerprint": dep,
    "artifacts": {
        "app-debug.apk": debug_sha,
        "app-release.apk": release_sha,
    },
}, indent=2))
PY
)"

echo "$REPORT"
if [[ -n "${1:-}" ]]; then
  echo "$REPORT" > "$1"
fi
