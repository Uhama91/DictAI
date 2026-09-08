#!/usr/bin/env python3
"""Verify the selected CI APK before exposing an installable download."""
import hashlib, os, shutil, zipfile
from pathlib import Path

apk = Path('app/build/outputs/apk/debug/app-debug.apk')
prototype = os.environ.get('PROTOTYPE') == 'true'
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    models = [name for name in names if name.endswith('.gguf')]
    if prototype:
        model = 'assets/local-format/LFM2.5-350M-Q4_K_M.gguf'
        assert models == [model], 'Expected exactly the pinned 350M model'
        assert archive.getinfo(model).file_size == 229312224
        assert archive.getinfo(model).compress_type == zipfile.ZIP_STORED
        with archive.open(model) as stream:
            assert hashlib.file_digest(stream, 'sha256').hexdigest() == '7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4'
        for name in ['LICENSE.txt', 'NOTICE.txt']:
            assert 'assets/local-format/' + name in names
    else:
        assert not models, 'Normal builds must not contain experimental weights'
    for name in ['layout-list.prompt', 'layout-email.prompt', 'llama-LICENSE.txt']:
        assert 'assets/local-format/' + name in names
    for name in ['libdictai_llm.so', 'libdictai_llm_arm82.so']:
        assert 'lib/arm64-v8a/' + name in names
output = Path('dist')
output.mkdir(exist_ok=True)
name = 'dictai-local-layout-test.apk' if prototype else 'whisperpin-debug.apk'
destination = output / name
shutil.copyfile(apk, destination)
with destination.open('rb') as stream:
    checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
(output / 'SHA256SUMS').write_text(f'{checksum}  {name}\n')
print(f'Verified {name}: {destination.stat().st_size} bytes, SHA256={checksum}')
