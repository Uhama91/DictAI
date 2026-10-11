import unittest
import tempfile
from pathlib import Path
from prepare_update_manifest import make_manifest, parse_android_abis, validate_android_signer, validate_destination


class UpdateManifestTests(unittest.TestCase):
    android = {"applicationId": "com.uhama.whisperpin", "versionCode": 46,
               "versionName": "0.9.17-dictai", "minSdk": 30}
    mac = {"CFBundleIdentifier": "com.uhama.dictai.desktop", "CFBundleVersion": "17",
           "CFBundleShortVersionString": "0.4.7", "LSMinimumSystemVersion": "14.0"}

    def test_independent_versions_point_to_their_own_installers(self):
        result = make_manifest(self.android, self.mac, "dictai-20261011")
        self.assertEqual(46, result["android"]["versionCode"])
        self.assertEqual(17, result["macos"]["buildNumber"])
        self.assertEqual("https://github.com/Uhama91/DictAI/releases/download/dictai-20261011/DictAI-android.apk", result["android"]["downloadUrl"])
        self.assertEqual("https://github.com/Uhama91/DictAI/releases/download/dictai-20261011/DictAI-macOS.pkg", result["macos"]["downloadUrl"])

    def test_architectures_are_taken_from_artifact_metadata(self):
        abis = parse_android_abis("sdkVersion:'30'\nnative-code: 'arm64-v8a'\n")
        self.assertEqual(["arm64-v8a"], abis)
        result = make_manifest({**self.android, "abis": abis},
                               {**self.mac, "architectures": ["arm64", "x86_64"]}, "test")
        self.assertEqual(["arm64-v8a"], result["android"]["abis"])
        self.assertEqual(["arm64", "x86_64"], result["macos"]["architectures"])
        with self.assertRaises(ValueError):
            parse_android_abis("sdkVersion:'30'")

    def test_refuses_other_app_or_invalid_release_metadata(self):
        for tag in ["../bad", "v1?other", "v1/new", ""]:
            with self.assertRaises(ValueError):
                make_manifest(self.android, self.mac, tag)
        with self.assertRaises(ValueError):
            make_manifest({**self.android, "applicationId": "other.app"}, self.mac, "v1")
        with self.assertRaises(ValueError):
            make_manifest(self.android, {**self.mac, "CFBundleIdentifier": "other.app"}, "v1")
        for value in [0, 1.5, True]:
            with self.assertRaises(ValueError):
                make_manifest({**self.android, "versionCode": value}, self.mac, "v1")
        with self.assertRaises(ValueError):
            make_manifest(self.android, {**self.mac, "LSMinimumSystemVersion": "bad"}, "v1")

    def test_public_signer_must_be_private_and_explicitly_approved(self):
        public_test_cert = "6b37c02704d31553b275a9a5f23c8eb650df04cd59f7b28074e6f2dcadbf9539"
        approved = "a" * 64
        validate_android_signer("package: name='com.uhama.whisperpin'", approved, approved.upper())
        for badging, actual, expected in [
            ("application-debuggable", approved, approved),
            ("", public_test_cert, public_test_cert),
            ("", approved, "b" * 64),
            ("", approved, None),
        ]:
            with self.assertRaises(ValueError):
                validate_android_signer(badging, actual, expected)

    def test_preview_and_public_manifests_cannot_share_the_output_folder(self):
        for preview, existing in [(True, "updates.json"), (False, "updates-preview.json")]:
            # TemporaryDirectory cleans test-only data, not repository or user files.
            with tempfile.TemporaryDirectory() as folder:
                destination = Path(folder)
                validate_destination(destination, preview)
                (destination / existing).write_text("old manifest", encoding="utf-8")
                with self.assertRaises(ValueError):
                    validate_destination(destination, preview)
                self.assertEqual("old manifest", (destination / existing).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
