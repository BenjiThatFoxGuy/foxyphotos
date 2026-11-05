import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "publish-release.py"


class PublishReleaseTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("publish_release", SCRIPT)
        assert spec is not None and spec.loader is not None
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.apk = Path(self.tmp.name) / "app-release.apk"
        self.apk.write_bytes(b"unit-test artifact, not an Android APK")
        self.digest = "sha256:" + hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.calls = []

    def invoke(self, draft_digest=None, published_digest=None, immutable=True,
               existing_pages=None, lookup_error=None, create_error=None,
               tag="v0.2", asset_count=1, post_create_pages=None):
        created = False
        published = False

        def release():
            return {
                "id": 42,
                "tag_name": tag,
                "draft": not published,
                "immutable": immutable if published else False,
                "html_url": "https://github.com/example/app/releases/tag/v0.2",
                "assets": [{"name": self.apk.name, "state": "uploaded", "digest":
                            (published_digest if published else draft_digest) or self.digest}] * asset_count,
            }

        def gh(*args):
            nonlocal created, published
            self.calls.append(args)
            if args[:2] == ("release", "create"):
                if create_error:
                    raise create_error
                created = True
                return "https://github.com/example/app/releases/tag/v0.2"
            if args[0] == "api":
                endpoint = args[1]
                if endpoint == "repos/example/app/releases?per_page=100":
                    self.assertIn("--paginate", args)
                    self.assertIn("--slurp", args)
                    if lookup_error:
                        raise lookup_error
                    if created:
                        return json.dumps(post_create_pages if post_create_pages is not None
                                          else [[release()]])
                    return json.dumps(existing_pages if existing_pages is not None else [[]])
                if endpoint == "repos/example/app/releases/42":
                    if "PATCH" in args:
                        self.assertTrue(created)
                        self.assertIn("draft=false", args)
                        published = True
                    return json.dumps(release())
                if "/releases/tags/" in endpoint:
                    # GitHub's tag endpoint does not expose an unpublished draft.
                    if not published:
                        raise subprocess.CalledProcessError(1, ["gh", *args], stderr="404")
                    return json.dumps(release())
            raise AssertionError(f"Unexpected command: {args}")
        return self.module.publish("example/app", "refs/tags/" + tag, self.apk, gh)

    def test_refuses_existing_release_on_later_page_before_any_writes(self):
        for draft in (True, False):
            with self.subTest(draft=draft):
                self.calls.clear()
                pages = [[{"id": 1, "tag_name": "v0.1", "draft": False}],
                         [{"id": 41, "tag_name": "v0.2", "draft": draft}]]
                with self.assertRaisesRegex(RuntimeError, "existing release"):
                    self.invoke(existing_pages=pages)
                self.assertEqual(self.calls, [
                    ("api", "repos/example/app/releases?per_page=100", "--paginate", "--slurp")])

    def test_lookup_failure_aborts_before_any_writes(self):
        error = subprocess.CalledProcessError(1, ["gh", "api"], stderr="403")
        with self.assertRaises(subprocess.CalledProcessError):
            self.invoke(lookup_error=error)
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.calls[0][0], "api")

    def test_create_failure_never_publishes(self):
        error = subprocess.CalledProcessError(1, ["gh", "release", "create"])
        with self.assertRaises(subprocess.CalledProcessError):
            self.invoke(create_error=error)
        self.assertEqual(len(self.calls), 2)
        self.assertEqual(self.calls[0][0], "api")
        self.assertEqual(self.calls[1][:2], ("release", "create"))

    def test_malformed_lookup_aborts_before_any_writes(self):
        def gh(*args):
            self.calls.append(args)
            return "{}"
        with self.assertRaisesRegex(RuntimeError, "release listing"):
            self.module.publish("example/app", "refs/tags/v0.2", self.apk, gh)
        self.assertEqual(len(self.calls), 1)

    def test_requires_exactly_one_apk_before_publication(self):
        for count in (0, 2):
            with self.subTest(count=count):
                self.calls.clear()
                with self.assertRaisesRegex(RuntimeError, "exactly"):
                    self.invoke(asset_count=count)
                self.assertFalse(any("PATCH" in c for c in self.calls))

    def test_ambiguous_post_create_lookup_never_publishes(self):
        matches = [{"id": 42, "tag_name": "v0.2"}, {"id": 43, "tag_name": "v0.2"}]
        with self.assertRaisesRegex(RuntimeError, "exactly one"):
            self.invoke(post_create_pages=[matches])
        self.assertFalse(any("PATCH" in c for c in self.calls))

    def test_tag_is_a_literal_argument_not_a_url_or_shell_fragment(self):
        tag = "v0.2/topic'$(false)&x"
        self.invoke(tag=tag)
        create = next(c for c in self.calls if c[:2] == ("release", "create"))
        self.assertEqual(create[2], tag)
        self.assertEqual(create[create.index("--title") + 1], tag)
        self.assertFalse(any(tag in c[1] for c in self.calls if c[0] == "api"))

    def test_gh_helper_uses_argv_without_shell(self):
        args = ("api", "repos/example/app/releases?per_page=100", "--paginate", "--slurp")
        with patch.object(self.module.subprocess, "run") as run:
            run.return_value.stdout = "[[]]"
            self.assertEqual(self.module.run_gh(*args), "[[]]")
        run.assert_called_once_with(["gh", *args], check=True, text=True,
                                    stdout=subprocess.PIPE)

    def test_attaches_and_verifies_draft_before_publishing(self):
        url = self.invoke()
        create = next(c for c in self.calls if c[:2] == ("release", "create"))
        self.assertEqual(create[:3], ("release", "create", "v0.2"))
        self.assertIn(str(self.apk), create)
        for flag in ("--draft", "--verify-tag", "--generate-notes"):
            self.assertIn(flag, create)
        id_calls = [c for c in self.calls if c[:2] ==
                    ("api", "repos/example/app/releases/42")]
        self.assertEqual(len(id_calls), 3)
        self.assertNotIn("PATCH", id_calls[0])
        self.assertIn("PATCH", id_calls[1])
        self.assertNotIn("PATCH", id_calls[2])
        self.assertFalse(any("/releases/tags/" in str(c) for c in self.calls))
        self.assertTrue(url.endswith("/v0.2"))

    def test_refuses_to_publish_if_uploaded_digest_differs(self):
        with self.assertRaisesRegex(RuntimeError, "digest"):
            self.invoke(draft_digest="sha256:wrong")
        self.assertFalse(any("PATCH" in c for c in self.calls))

    def test_checks_published_asset_again(self):
        with self.assertRaisesRegex(RuntimeError, "digest"):
            self.invoke(published_digest="sha256:wrong")

    def test_requires_immutable_published_release(self):
        with self.assertRaisesRegex(RuntimeError, "immutable"):
            self.invoke(immutable=False)

    def test_refuses_branch_build_without_any_api_calls(self):
        with self.assertRaisesRegex(ValueError, "tag"):
            self.module.publish("example/app", "refs/heads/main", self.apk,
                                lambda *args: self.calls.append(args))
        self.assertEqual(self.calls, [])

    def test_refuses_missing_apk_without_any_api_calls(self):
        self.apk.unlink()
        with self.assertRaises(FileNotFoundError):
            self.invoke()
        self.assertEqual(self.calls, [])


if __name__ == "__main__":
    unittest.main()
