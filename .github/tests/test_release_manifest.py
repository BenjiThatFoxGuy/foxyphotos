import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('verify_apks', Path(__file__).resolve().parents[1] / 'verify-apks.py')
assert spec is not None and spec.loader is not None
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class ManifestTest(unittest.TestCase):
    def test_manifest_uses_verified_apk_identity(self):
        value = m.release_manifest('example/app', 'v1.2', 'app-release.apk', 123, 'a'*64,
            "package: name='gay.benjifox.foxyphotos' versionCode='67' versionName='1.2'\nsdkVersion:'29'",
            'Signer #1 certificate SHA-256 digest: ' + 'b'*64)
        self.assertEqual(value['versionCode'], 67)
        self.assertEqual(value['minSdk'], 29)
        self.assertEqual(value['apk']['size'], 123)
        self.assertEqual(value['apk']['signingCertificateSha256'], 'b'*64)
        self.assertEqual(value['apk']['url'], 'https://github.com/example/app/releases/download/v1.2/app-release.apk')

    def test_missing_signer_fails_closed(self):
        with self.assertRaises(ValueError):
            m.release_manifest('example/app', 'v1', 'app.apk', 1, 'a'*64, '', '')
