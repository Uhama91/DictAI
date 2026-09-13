#!/usr/bin/env python3
"""Fetch the pinned formatter and its license for offline installation with the APK."""
import argparse
import hashlib
from pathlib import Path
import urllib.request

REVISION = '9969000761ce34de907bf20017cbfc3d52d6eaf9'
MODEL = 'LFM2.5-350M-Q4_K_M.gguf'
SHA256 = '7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4'
SIZE = 229312224
BASE = f'https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF/resolve/{REVISION}'

def valid(path):
    if not path.is_file() or path.stat().st_size != SIZE:
        return False
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() == SHA256

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    directory = Path(__file__).resolve().parents[1] / 'app/src/localFormatPrototype/assets/local-format'
    destination = directory / MODEL
    if args.verify_only:
        if not valid(destination) or not (directory / 'LICENSE.txt').is_file():
            raise SystemExit('Bundled formatter missing or invalid. Run scripts/fetch_local_format_model.py')
        print('Bundled local formatter: checksum and license verified')
        return
    directory.mkdir(parents=True, exist_ok=True)
    if not valid(destination):
        partial = destination.with_suffix('.gguf.part')
        digest = hashlib.sha256()
        count = 0
        with urllib.request.urlopen(f'{BASE}/{MODEL}', timeout=90) as response, partial.open('wb') as output:
            while block := response.read(1024 * 1024):
                count += len(block)
                if count > SIZE:
                    raise RuntimeError('Unexpected model size')
                digest.update(block)
                output.write(block)
        if count != SIZE or digest.hexdigest() != SHA256:
            raise RuntimeError('Formatter checksum mismatch; existing installed asset preserved')
        partial.replace(destination)
    with urllib.request.urlopen(f'{BASE}/LICENSE', timeout=30) as response:
        license_text = response.read(100_000).decode('utf-8')
    if len(license_text) < 100:
        raise RuntimeError('Model license missing')
    (directory / 'LICENSE.txt').write_text(license_text)
    (directory / 'NOTICE.txt').write_text(
        f'Liquid AI LFM2.5-350M, official Q4_K_M quantization.\nSource: {BASE}/{MODEL}\n'
        f'SHA-256: {SHA256}\nWeights unmodified. See LICENSE.txt.\n')
    print(f'Bundled local formatter verified: {destination} ({SIZE} bytes)')

if __name__ == '__main__':
    main()
