import unittest

from update_download_table import validate_release, choose_downloads, render_table, replace_table, refresh_readme


def fixture(tag="dictai-test", android_code=46, mac_build=17, preview=True):
    base = f"https://github.com/Uhama91/DictAI/releases/download/{tag}/"
    manifest = {
        "schemaVersion": 1,
        "android": {"applicationId": "com.uhama.whisperpin", "versionCode": android_code,
                    "versionName": "0.9.17-dictai", "minSdk": 30, "abis": ["arm64-v8a"],
                    "sha256": "a" * 64, "downloadUrl": base + "DictAI-android.apk"},
        "macos": {"bundleIdentifier": "com.uhama.dictai.desktop", "buildNumber": mac_build,
                  "versionName": "0.4.7", "minSystemVersion": "14.0", "architectures": ["arm64", "x86_64"],
                  "sha256": "b" * 64, "downloadUrl": base + "DictAI-macOS.pkg"}}
    if preview:
        manifest["testOnly"] = True
    release = {"tag_name": tag, "draft": False, "prerelease": preview,
               "published_at": "2026-10-11T15:12:55Z",
               "html_url": f"https://github.com/Uhama91/DictAI/releases/tag/{tag}",
               "assets": [{"name": name, "state": "uploaded", "size": 100,
                           "digest": "sha256:" + digest, "browser_download_url": base + name}
                          for name, digest in [("DictAI-android.apk", "a" * 64), ("DictAI-macOS.pkg", "b" * 64)]]}
    return release, manifest


class DownloadTableTests(unittest.TestCase):
    def test_phone_and_tablet_share_apk_and_mac_has_its_own_version(self):
        release, manifest = fixture()
        offer = validate_release(release, manifest)
        table = render_table(choose_downloads([offer]))
        self.assertEqual(2, table.count(manifest["android"]["downloadUrl"]))
        self.assertEqual(1, table.count(manifest["macos"]["downloadUrl"]))
        self.assertIn(f"Téléphone Android | [Télécharger]({manifest['android']['downloadUrl']}) | 0.9.17-dictai (test)", table)
        self.assertIn("Android 11+ · ARM64", table)
        self.assertIn(f"Mac | [Télécharger]({manifest['macos']['downloadUrl']}) | 0.4.7 (test)", table)
        self.assertIn("Apple silicon et Intel", table)
        self.assertIn(release["html_url"], table)

    def test_refuses_missing_assets_bad_hashes_other_apps_and_external_links(self):
        for change in [
            lambda r, m: r["assets"].pop(),
            lambda r, m: r["assets"][0].update(digest="sha256:" + "c" * 64),
            lambda r, m: m["android"].update(applicationId="another.app"),
            lambda r, m: m["macos"].update(downloadUrl="https://example.com/DictAI.pkg"),
            lambda r, m: r.update(draft=True),
            lambda r, m: m.pop("testOnly"),
            lambda r, m: m["android"].update(abis=[]),
            lambda r, m: m["macos"].update(architectures=["invented"]),
            lambda r, m: m["android"].update(versionName="fake | [link](url)"),
            lambda r, m: m["android"].update(versionCode=True),
        ]:
            release, manifest = fixture()
            change(release, manifest)
            with self.assertRaises(ValueError):
                validate_release(release, manifest)

    def test_stable_manifest_cannot_advertise_test_installers(self):
        release, manifest = fixture(preview=False)
        validate_release(release, manifest)
        manifest["testOnly"] = True
        with self.assertRaises(ValueError):
            validate_release(release, manifest)

    def test_older_release_published_later_cannot_downgrade_either_platform(self):
        offers = []
        for tag, android, mac in [("older", 45, 16), ("new-android", 47, 17), ("new-mac", 46, 18)]:
            release, manifest = fixture(tag, android, mac)
            offers.append(validate_release(release, manifest))
        selected = choose_downloads(list(reversed(offers)))
        self.assertEqual(47, selected["android"]["manifest"]["android"]["versionCode"])
        self.assertEqual(18, selected["macos"]["manifest"]["macos"]["buildNumber"])

    def test_only_marked_block_changes_and_repeated_update_is_identical(self):
        original = "Intro\n<!-- dictai-downloads:start -->\nold\n<!-- dictai-downloads:end -->\nLicense\n"
        updated = replace_table(original, "new\n")
        self.assertEqual("Intro\n<!-- dictai-downloads:start -->\nnew\n<!-- dictai-downloads:end -->\nLicense\n", updated)
        self.assertEqual(updated, replace_table(updated, "new\n"))
        for bad in ["No markers", original + original]:
            with self.assertRaises(ValueError):
                replace_table(bad, "new")

    def test_no_valid_release_fails_without_producing_empty_download_table(self):
        with self.assertRaises(ValueError):
            choose_downloads([])

    def test_withdrawn_last_release_removes_its_download_links(self):
        original = "Intro\n<!-- dictai-downloads:start -->\n[old](https://github.com/old.apk)\n<!-- dictai-downloads:end -->\nLicense\n"
        updated = refresh_readme(original, [])
        self.assertNotIn("old.apk", updated)
        self.assertIn("Aucun installateur vérifié", updated)
        self.assertTrue(updated.endswith("License\n"))


if __name__ == "__main__":
    unittest.main()
