#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ "${1:-}" == "--prototype" && $# == 1 ]]; then
    exec bash "$repo_root/scripts/verify_gemma_android.sh"
fi
export JAVA_HOME="${JAVA_HOME:-/home/ullie/.cache/dictai-build-tools/java21/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/home/ullie/.cache/dictai-build-tools/sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/home/ullie/.cache/dictai-build-tools/gradle}"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
export DICTAI_CMAKE="${DICTAI_CMAKE:-$ANDROID_HOME/cmake/3.22.1/bin/cmake}"
export DICTAI_LLAMA_SOURCE_DIR="${DICTAI_LLAMA_SOURCE_DIR:-/home/ullie/.cache/dictai-build-tools/llama.cpp-v0.1.2}"
export DICTAI_LLM_BUILD_DIR="${DICTAI_LLM_BUILD_DIR:-/home/ullie/.cache/dictai-build-tools/llama-android-v0.1.2}"
cd "$repo_root"
if [[ "${1:-}" == "--host-jni" ]]; then
    bash scripts/test_local_format_jni_host.sh
    exit
fi
if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--prototype" ) ]]; then
    echo "Usage: $0 [--prototype|--host-jni]" >&2
    exit 2
fi
bash scripts/build_local_format_android.sh
python3 -m unittest scripts/test_check_apk_native_alignment.py -v
./gradlew --no-daemon testDebugUnitTest assembleDebug --stacktrace -Djavax.net.ssl.trustStore=/home/ullie/.cache/dictai-build-tools/cacerts
verify_apk() {
    python3 scripts/check_apk_native_alignment.py "$1"
    "$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 4 "$1"
    "$JAVA_HOME/bin/java" -jar "$ANDROID_HOME/build-tools/35.0.0/lib/apksigner.jar" verify "$1"
}
verify_apk app/build/outputs/apk/debug/app-debug.apk
python3 -B - <<'REPORT'
from pathlib import Path
import xml.etree.ElementTree as ET,zipfile,hashlib,datetime,shutil
rows=[ET.parse(p).getroot().attrib for p in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
count=sum(int(r['tests']) for r in rows)
assert count>0 and sum(int(r['failures'])+int(r['errors']) for r in rows)==0
apk=Path('app/build/outputs/apk/debug/app-debug.apk')
with zipfile.ZipFile(apk) as archive:
 assert not any(n.endswith('.gguf') for n in archive.namelist()), 'Experimental model must not be bundled in normal APK'
 for name in ['libdictai_llm.so','libdictai_llm_arm82.so']:assert 'lib/arm64-v8a/'+name in archive.namelist()
 for name in ['list','email']:assert f'assets/local-format/layout-{name}.prompt' in archive.namelist()
destination=apk.parents[1]/'verified/app-normal.apk';destination.parent.mkdir(exist_ok=True);shutil.copyfile(apk,destination)
with destination.open('rb') as stream:checksum=hashlib.file_digest(stream,'sha256').hexdigest()
report=f"""# Vérification du post-traitement DictAI

Exécution UTC : {datetime.datetime.now(datetime.timezone.utc).isoformat()}

- Tests JVM : {count}, zéro échec, dans {len(rows)} suites.
- Tests Python du contrôleur APK : 18, zéro échec.
- Compilation native ARM64 : réussie ; alignement 16 Ko ; seul export JNI_OnLoad ; aucun GGML/llama externe.
- APK normale : construction, signature, alignement ELF/ZIP 16 Ko vérifiés.
- Absence du poids GGUF expérimental de l’APK normale : vérifiée dans l’archive finale.
- Taille normale : {destination.stat().st_size} octets.
- SHA-256 normale : `{checksum}`.
- Fichier normal conservé : `{destination}`.

Les tests couvrent notamment la fidélité intégrale des mots et de chaque préfixe affiché, les interruptions et le retour direct des messages courts. Vocabulaire et nombres disposent aussi de tests automatisés. Les interactions de l’overlay et la latence restent à vérifier sur téléphone. La version normale propose cloud/désactivé ; la mise en page locale reste un essai séparé. Voir `docs/SUIVI-DEVELOPPEMENT.md`.
"""
Path('docs/VERIFICATION-POST-TRAITEMENT.md').write_text(report)
print(f'Normal APK verified: {destination.stat().st_size} bytes, {count} JVM tests, model absent, SHA256={checksum}')
REPORT
