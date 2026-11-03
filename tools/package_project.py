"""Create a portable source archive without local SDKs, caches or signing keys."""
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT/'dist'
DIST.mkdir(exist_ok=True)
apk = DIST/'GoroTaro-debug.apk'
shutil.copy2(ROOT/'app/build/outputs/apk/debug/app-debug.apk', apk)
archive = DIST/'GoroTaro-source.zip'
root_files = ['.gitignore','settings.gradle.kts','build.gradle.kts','gradle.properties','gradlew','gradlew.bat','build.ps1','README.md','asset_derivatives.json']
folders = ['app/src','app/schemas','domain/src','gradle','tools','content','reference']
files = [ROOT/name for name in root_files]
files += [ROOT/'app/build.gradle.kts', ROOT/'domain/build.gradle.kts']
for folder in folders:
    files.extend(p for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
for name in ['VERIFICATION.md','final-build.log','android-tests-final.log','ui-large-font.log','day-draft-coverage.json','domain-tests.xml','release-startup.json','performance.json','contrast.json']:
    path = ROOT/'reports'/name
    if path.exists(): files.append(path)
files.extend(p for p in (ROOT/'reports/screenshots').glob('*.png'))
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
    for path in sorted(set(files)):
        z.write(path, 'GoroTaro/' + path.relative_to(ROOT).as_posix())
index = []
for path in [apk, archive]:
    index.append({'file':path.name,'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
(DIST/'manifest.json').write_text(json.dumps(index, indent=2), encoding='utf-8')
print(json.dumps(index, indent=2))
