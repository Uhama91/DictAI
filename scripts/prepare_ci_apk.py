#!/usr/bin/env python3
"""Verify the selected CI APK before exposing an installable download."""
import hashlib, shutil, zipfile
from pathlib import Path

apk = Path('app/build/outputs/apk/debug/app-debug.apk')
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    models = [name for name in names if name.endswith(('.gguf', '.litertlm'))]
    assert not models, 'Speech models are installed in-app; no LLM model may be bundled'
    for name in [
        'Apache-2.0.txt',
        'NOTICE.txt',
        'sherpa-onnx-1.13.4-LICENSE.txt',
        'onnxruntime-1.27.0-LICENSE.txt',
    ]:
        assert 'assets/licenses/' + name in names
    assert 'assets/THIRD_PARTY_NOTICES.txt' in names
    for name in ['libdictai_llm.so', 'libdictai_llm_arm82.so', 'liblitertlm_jni.so']:
        assert 'lib/arm64-v8a/' + name not in names, 'Retired local LLM runtime must not be packaged'
    for name in ['libtranscribe.so', 'libtranscribe_jni.so', 'libggml.so', 'libggml-base.so', 'libggml-cpu.so']:
        assert 'lib/arm64-v8a/' + name in names, 'Missing local speech recognition runtime'
    assert not any(name.startswith('assets/local-format/') for name in names), 'Retired local formatting prompts must not be packaged'
output = Path('dist')
output.mkdir(exist_ok=True)
name = 'dictai-debug.apk'
destination = output / name
shutil.copyfile(apk, destination)
with destination.open('rb') as stream:
    checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
(output / 'SHA256SUMS').write_text(f'{checksum}  {name}\n')
print(f'Verified {name}: {destination.stat().st_size} bytes, SHA256={checksum}')
