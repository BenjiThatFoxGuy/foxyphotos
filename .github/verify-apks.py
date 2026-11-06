"""Check the metadata and signatures of the actual APKs before upload."""

import hashlib
import json
from urllib.parse import quote
import os


def release_manifest(repository, tag, filename, size, digest, badging, signatures):
    identity = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", badging, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    certs = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$", signatures, re.M)
    if not identity or not sdk or len(certs) != 1:
        raise ValueError('Expected APK identity, minimum SDK and exactly one signing certificate')
    package, code, name = identity.groups()
    return {'schemaVersion': 1, 'package': package, 'versionName': name,
            'versionCode': int(code), 'minSdk': int(sdk.group(1)), 'tag': tag,
            'releaseUrl': f'https://github.com/{repository}/releases/tag/{quote(tag, safe="")}',
            'releaseNotesUrl': f'https://github.com/{repository}/releases/tag/{quote(tag, safe="")}',
            'publication': {'provider': 'github', 'channel': 'stable', 'timestampSource': 'GitHub Release published_at'},
            'apk': {'filename': filename, 'url': f'https://github.com/{repository}/releases/download/{quote(tag, safe="")}/{quote(filename, safe="")}',
                    'size': size, 'sha256': digest, 'signingCertificateSha256': certs[0].lower()}}

from pathlib import Path
import re
import subprocess


def verify_badging(badging, package, code, version):
    match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", badging, re.M)
    actual = match.groups() if match else None
    expected = (package, str(code), version)
    if actual != expected:
        raise RuntimeError(f"APK metadata mismatch: expected {expected}, got {actual}")


def main():
    code = int(os.environ["GITHUB_RUN_NUMBER"])
    ref = os.environ["GITHUB_REF"]
    tag_build = ref.startswith("refs/tags/")
    version = (ref.removeprefix("refs/tags/") if tag_build
               else Path("VERSION").read_text().strip()).removeprefix("v")
    variants = []
    if not tag_build:
        variants.append("debug")
    if os.environ["GITHUB_EVENT_NAME"] != "pull_request":
        variants.append("release")
    outputs = Path("app/build/outputs/apk")
    expected = {outputs / variant / f"app-{variant}.apk" for variant in variants}
    actual = set(outputs.glob("**/*.apk"))
    if actual != expected:
        raise RuntimeError(f"Unexpected APK set: expected {expected}, got {actual}")
    tools = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"]) / "build-tools/36.0.0"
    for variant in variants:
        apk = outputs / variant / f"app-{variant}.apk"
        package = "gay.benjifox.foxyphotos" + (".debug" if variant == "debug" else "")
        badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
        verify_badging(badging, package, code, version)
        signatures = subprocess.check_output([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], text=True)
        if tag_build:
            manifest = release_manifest(os.environ['GITHUB_REPOSITORY'], ref.removeprefix('refs/tags/'),
                apk.name, apk.stat().st_size, hashlib.sha256(apk.read_bytes()).hexdigest(), badging, signatures)
            manifest['sourceCommit'] = os.environ['GITHUB_SHA']
            (apk.parent / 'release-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        print(f"{apk.name}: versionCode={code}, versionName={version}, package={package}, signature verified")


if __name__ == "__main__":
    main()
