"""Check the metadata and signatures of the actual APKs before upload."""

import os
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
        subprocess.run([str(tools / "apksigner"), "verify", str(apk)], check=True)
        print(f"{apk.name}: versionCode={code}, versionName={version}, package={package}, signature verified")


if __name__ == "__main__":
    main()
