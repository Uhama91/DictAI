#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ndk_root="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to NDK 28.2.13676358}"
bundle_dir="${DICTAI_LLM_BUNDLE_DIR:-$repo_root/app/src/main/jniLibs/arm64-v8a}"
readelf="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
libraries=(libdictai_llm.so libdictai_llm_arm82.so)
if [[ -n "${DICTAI_LLM_LIBRARY_NAME:-}" ]]; then libraries=("$DICTAI_LLM_LIBRARY_NAME"); fi
python3 - "$readelf" "$bundle_dir" "${libraries[@]}" <<'PY'
import re,subprocess,sys
from pathlib import Path
readelf,bundle,*names=sys.argv[1:]
assert names
for name in names:
 assert name in ('libdictai_llm.so','libdictai_llm_arm82.so'),name
 filename=str(Path(bundle)/name)
 assert Path(filename).is_file(),filename
 
 def inspect(*args):
  return subprocess.check_output([readelf,*args,filename],text=True)
 header=inspect('-h')
 assert re.search(r'Class:\s+ELF64',header)
 assert re.search(r'Machine:\s+AArch64',header)
 loads=[int(line.split()[-1],16) for line in inspect('-lW').splitlines() if line.lstrip().startswith('LOAD ')]
 assert loads and all(x>=16384 for x in loads),loads
 assert '.debug_' not in inspect('-SW')
 dynamic=inspect('-d')
 needed=re.findall(r'Shared library: \[([^]]+)\]',dynamic)
 assert not any('ggml' in x.lower() or 'llama' in x.lower() or x=='libc++_shared.so' or x.startswith('libdictai_llm') for x in needed),needed
 assert f'Library soname: [{name}]' in dynamic,dynamic
 exports=[]
 for line in inspect('--dyn-syms','--wide').splitlines():
  fields=line.split()
  if len(fields)>=8 and fields[4] in ('GLOBAL','WEAK') and fields[5] in ('DEFAULT','PROTECTED') and fields[6]!='UND':
   exports.append(fields[7].split('@')[0])
 assert exports==['JNI_OnLoad'],exports
 print('Local formatter Android ARM64: 16 KB, isolated static GGML, JNI-only exports: PASS')

PY
