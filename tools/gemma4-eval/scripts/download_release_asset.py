#!/usr/bin/env python3
"""Download one non-model GitHub release asset with the Q6 auth guard."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from download_release_assets import (
    AssetDownloadError,
    _check_server_digest,
    _download_to_new_path,
    _opener,
    _release_assets,
)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--asset-name", required=True)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--bytes", type=int, required=True)
    args = parser.parse_args(argv)
    try:
        token = os.environ.get("GH_TOKEN", "")
        assets = _release_assets(_opener(), args.repo, args.release_id, token)
        asset = assets.get(args.asset_name)
        if asset is None:
            raise AssetDownloadError(f"missing release asset: {args.asset_name}")
        _check_server_digest(asset, args.sha256, f"release asset {args.asset_name}")
        result = _download_to_new_path(
            _opener(),
            str(asset["url"]),
            args.destination,
            token,
            expected_sha256=args.sha256,
            expected_bytes=args.bytes,
        )
    except (AssetDownloadError, OSError, ValueError) as error:
        print(f"Q6 asset download refused: {error}", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
