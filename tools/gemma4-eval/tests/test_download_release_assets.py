from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path
from urllib.request import Request


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("download_release_assets", ROOT / "scripts/download_release_assets.py")
assert SPEC is not None and SPEC.loader is not None
module = importlib.util.module_from_spec(SPEC)
sys.modules["download_release_assets"] = module
SPEC.loader.exec_module(module)


class ReleaseDownloadContractTests(unittest.TestCase):
    def test_api_redirect_drops_authorization_on_cdn(self):
        request = Request(
            "https://api.github.com/repos/example/repo/releases/1/assets/2",
            headers={"Authorization": "Bearer secret", "Accept": "application/octet-stream"},
        )
        redirected = module.SafeRedirectHandler().redirect_request(
            request,
            None,
            302,
            "found",
            {},
            "https://objects.githubusercontent.com/release-asset/signed",
        )
        self.assertIsNotNone(redirected)
        self.assertIsNone(redirected.get_header("Authorization"))

    def test_same_api_host_keeps_authorization_and_external_host_is_rejected(self):
        request = Request(
            "https://api.github.com/repos/example/repo/releases/1/assets/2",
            headers={"Authorization": "Bearer secret"},
        )
        redirected = module.SafeRedirectHandler().redirect_request(
            request,
            None,
            307,
            "temporary",
            {},
            "https://api.github.com/repos/example/repo/releases/1/assets/2?page=2",
        )
        self.assertEqual(redirected.get_header("Authorization"), "Bearer secret")
        with self.assertRaises(module.AssetDownloadError):
            module._safe_url("http://example.com/file", allow_cdn=True)

    def test_release_id_must_be_numeric(self):
        with self.assertRaises(module.AssetDownloadError):
            module._release_assets(None, "repo", "draft-tag", "secret")

    def test_server_digest_mismatch_is_rejected(self):
        with self.assertRaises(module.AssetDownloadError):
            module._check_server_digest(
                {"server_digest": "sha256:" + "0" * 64},
                "1" * 64,
                "asset",
            )


if __name__ == "__main__":
    unittest.main()
