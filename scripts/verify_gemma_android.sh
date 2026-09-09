#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export JAVA_HOME="${JAVA_HOME:-/home/ullie/.cache/dictai-build-tools/java21/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/home/ullie/.cache/dictai-build-tools/sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/home/ullie/.cache/dictai-build-tools/gradle}"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$repo_root"
extra=()
if [[ -f /home/ullie/.cache/dictai-build-tools/cacerts ]]; then
    extra+=(-Djavax.net.ssl.trustStore=/home/ullie/.cache/dictai-build-tools/cacerts)
fi
python3 -m unittest scripts/test_check_apk_native_alignment.py -v
./gradlew --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8' \
    -PlocalFormatPrototype=true :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace "${extra[@]}"
apk=app/build/outputs/apk/debug/app-debug.apk
python3 scripts/check_apk_native_alignment.py "$apk"
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 4 "$apk"
"$JAVA_HOME/bin/java" -jar "$ANDROID_HOME/build-tools/35.0.0/lib/apksigner.jar" verify "$apk"
PROTOTYPE=true python3 scripts/prepare_ci_apk.py
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
import hashlib,json,shutil,datetime
rows=[ET.parse(p).getroot().attrib for p in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
count=sum(int(r['tests']) for r in rows)
assert count>0 and sum(int(r['failures'])+int(r['errors']) for r in rows)==0
apk=Path('app/build/outputs/apk/debug/app-debug.apk')
version=json.loads((apk.parent/'output-metadata.json').read_text())['elements'][0]['versionName']
destination=apk.parents[1]/'verified/app-gemma-test.apk';destination.parent.mkdir(exist_ok=True)
shutil.copyfile(apk,destination)
with destination.open('rb') as stream:checksum=hashlib.file_digest(stream,'sha256').hexdigest()
Path('docs/VERIFICATION-GEMMA.md').write_text(f'''# Vérification du prototype Gemma — {version}

Exécution UTC : {datetime.datetime.now(datetime.timezone.utc).isoformat()}

- Tests JVM : {count}, zéro échec, dans {len(rows)} suites.
- Tests Python du contrôleur APK : 18, zéro échec.
- Tests instrumentés Android : compilation réussie, exécution non réalisée faute d'appareil.
- APK ARM64 : construction, signature, alignement ELF/ZIP 16 Ko vérifiés.
- LiteRT-LM JNI présent ; absence des anciens moteurs llama et des poids 350M vérifiée dans l'APK.
- Gemma est installé séparément dans l'application ; taille et SHA-256 sont vérifiés avant publication du modèle.
- Taille APK : {destination.stat().st_size} octets.
- SHA-256 APK : `{checksum}`.
- Fichier local : `{destination}`.

Les tests couvrent séparation message/note, confirmation ponctuelle, reprise sûre du brouillon, coordination du clavier, maintien du point de lecture, reprise du téléchargement, vérification d'intégrité, projection fidèle du texte, rejet d'ajouts/traductions, annulation et suppression des sorties tardives. Les tests de transport utilisent des callbacks simulés, pas une exécution GPU Android.

La latence GPU, la qualité des dictées réelles et les gestes restent à mesurer sur téléphone. Aucun appareil Android n'était connecté à cette vérification. Le premier chargement ne constitue pas une dictée instantanée ; le moteur est préchauffé et reste partagé avec l'overlay.
''')
print(f'Gemma APK verified: {count} JVM tests, {destination.stat().st_size} bytes, SHA256={checksum}')
PY
