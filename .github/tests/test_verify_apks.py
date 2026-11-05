import importlib.util
from pathlib import Path
import unittest


class ApkMetadataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = Path(__file__).resolve().parents[1] / "verify-apks.py"
        spec = importlib.util.spec_from_file_location("verify_apks", path)
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def test_requires_exact_run_number_package_and_version(self):
        badging = "package: name='gay.benjifox.foxyphotos.debug' versionCode='52' versionName='0.1' platformBuildVersionName='16'\n"
        self.module.verify_badging(badging, "gay.benjifox.foxyphotos.debug", 52, "0.1")
        for package, code, version in [
            ("gay.benjifox.foxyphotos.debug", 51, "0.1"),
            ("gay.benjifox.foxyphotos", 52, "0.1"),
            ("gay.benjifox.foxyphotos.debug", 52, "0.2"),
        ]:
            with self.subTest(package=package, code=code, version=version):
                with self.assertRaisesRegex(RuntimeError, "metadata mismatch"):
                    self.module.verify_badging(badging, package, code, version)

    def test_rejects_missing_package_metadata(self):
        with self.assertRaisesRegex(RuntimeError, "metadata mismatch"):
            self.module.verify_badging("", "gay.benjifox.foxyphotos", 52, "0.1")


if __name__ == "__main__":
    unittest.main()
