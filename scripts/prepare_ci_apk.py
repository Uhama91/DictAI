#!/usr/bin/env python3
"""Verify the selected CI APK before exposing an installable download."""
import hashlib, os, shutil, zipfile
from pathlib import Path

apk = Path('app/build/outputs/apk/debug/app-debug.apk')
prototype = os.environ.get('PROTOTYPE') == 'true'
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    models = [name for name in names if name.endswith(('.gguf', '.litertlm'))]
    assert not models, 'Gemma is installed in-app; no old or partial model may be bundled'
    for name in ['gemma-NOTICE.txt', 'Apache-2.0.txt']:
        assert 'assets/local-format/' + name in names
    assert 'lib/arm64-v8a/liblitertlm_jni.so' in names, 'Missing LiteRT-LM Android runtime'
    for name in ['layout-list.prompt', 'layout-email.prompt', 'llama-LICENSE.txt']:
        assert 'assets/local-format/' + name in names
    for name in ['libdictai_llm.so', 'libdictai_llm_arm82.so']:
        assert ('lib/arm64-v8a/' + name in names) != prototype, 'Prototype must use Gemma, normal build keeps legacy test runtime'
output = Path('dist')
output.mkdir(exist_ok=True)
name = 'dictai-local-layout-test.apk' if prototype else 'whisperpin-debug.apk'
destination = output / name
shutil.copyfile(apk, destination)
with destination.open('rb') as stream:
    checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
(output / 'SHA256SUMS').write_text(f'{checksum}  {name}\n')
print(f'Verified {name}: {destination.stat().st_size} bytes, SHA256={checksum}')
