"""Publish a tag-built APK after checking the uploaded draft asset.

Fail closed on an existing release: never overwrite a published/immutable asset.
A failed upload leaves a draft for inspection, not a partial public release.
"""

import hashlib
import json
import os
from pathlib import Path
import subprocess


def publish(repository, ref, apk, gh, manifest=None):
    if not ref.startswith("refs/tags/v") or ref == "refs/tags/v":
        raise ValueError("Publishing requires a v* tag ref")
    tag = ref.removeprefix("refs/tags/")
    digest = "sha256:" + hashlib.sha256(apk.read_bytes()).hexdigest()
    expected = {apk.name: digest}
    if manifest is not None:
        metadata = json.loads(manifest.read_text())
        if (metadata['tag'] != tag or metadata['apk']['sha256'] != digest.removeprefix('sha256:')
                or metadata['apk']['size'] != apk.stat().st_size):
            raise RuntimeError('Manifest does not describe the exact APK')
        expected[manifest.name] = 'sha256:' + hashlib.sha256(manifest.read_bytes()).hexdigest()
    releases_endpoint = f"repos/{repository}/releases"

    def matching_releases():
        # Authenticated listing includes drafts, unlike GET releases/tags/{tag}.
        pages = json.loads(gh("api", releases_endpoint + "?per_page=100",
                              "--paginate", "--slurp"))
        if not isinstance(pages, list) or not all(isinstance(page, list) for page in pages):
            raise RuntimeError("Unexpected release listing response")
        return [release for page in pages for release in page
                if release["tag_name"] == tag]

    if matching_releases():
        raise RuntimeError("Refusing to overwrite an existing release (draft or published)")

    files = [str(apk)] + ([str(manifest)] if manifest is not None else [])
    gh("release", "create", tag, *files, "--repo", repository,
       "--draft", "--verify-tag", "--title", tag, "--generate-notes")
    matches = matching_releases()
    if len(matches) != 1:
        raise RuntimeError("Expected exactly one newly created draft release")
    release_id = matches[0]["id"]
    endpoint = f"{releases_endpoint}/{release_id}"

    def verify(draft):
        release = json.loads(gh("api", endpoint))
        if (release["id"] != release_id or release["tag_name"] != tag
                or release["draft"] != draft):
            raise RuntimeError("Unexpected release tag or draft state")
        assets = release["assets"]
        if len(assets) != len(expected) or {a['name'] for a in assets} != set(expected):
            raise RuntimeError("Expected exactly the tag-built release APK")
        if any(a['state'] != 'uploaded' or a.get('digest') != expected[a['name']] for a in assets):
            raise RuntimeError("Uploaded APK digest does not match the built APK")
        if not draft and not release.get("immutable"):
            raise RuntimeError("Published release is not immutable")
        return release["html_url"]

    verify(draft=True)
    gh("api", endpoint, "--method", "PATCH", "--field", "draft=false")
    return verify(draft=False)


def run_gh(*args):
    return subprocess.run(["gh", *args], check=True, text=True,
                          stdout=subprocess.PIPE).stdout


if __name__ == "__main__":
    # Checkout must be the exact workflow source, never the tip of main.
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if head != os.environ["GITHUB_SHA"]:
        raise RuntimeError("Checkout does not match the workflow commit")
    print(publish(os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_REF"],
                  Path("app/build/outputs/apk/release/app-release.apk"), run_gh,
                  Path("app/build/outputs/apk/release/release-manifest.json")))
