"""Record artifact sizes, palette contrast and optional local-emulator startup."""
import argparse
import json
import statistics
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT/'reports'
def luminance(color):
    rgb = [int(color[i:i+2], 16)/255 for i in (1,3,5)]
    linear = [v/12.92 if v <= .04045 else ((v+.055)/1.055)**2.4 for v in rgb]
    return sum(v*w for v,w in zip(linear, [.2126,.7152,.0722]))
def contrast(a,b):
    x,y = sorted([luminance(a), luminance(b)])
    return round((y+.05)/(x+.05),3)
def main():
    p = argparse.ArgumentParser(); p.add_argument('--startup', action='store_true'); args = p.parse_args()
    colors = json.loads((ROOT/'reference/design_tokens.json').read_text())
    pairs = []
    for theme in ['light','dark']:
        c = colors[theme]
        for foreground, background in [('text','background'),('text','surface'),('secondary','background'),('secondary','surface')]:
            ratio = contrast(c[foreground],c[background]); pairs.append({'theme':theme,'pair':f'{foreground}/{background}','ratio':ratio,'meetsNormalText':ratio>=4.5})
        ratio = contrast('#FFFFFF' if theme=='light' else '#271E31',c['primary']); pairs.append({'theme':theme,'pair':'button label/primary','ratio':ratio,'meetsNormalText':ratio>=4.5})
    (REPORT/'contrast.json').write_text(json.dumps(pairs, indent=2))
    sizes = {'originalCardsBytes':sum(p.stat().st_size for p in (ROOT/'reference/assets/cards').glob('*.png')), 'androidCardsBytes':sum(p.stat().st_size for p in (ROOT/'app/src/main/assets/cards').glob('*.webp')), 'thumbnailsBytes':sum(p.stat().st_size for p in (ROOT/'app/src/main/assets/thumbs').glob('*.webp'))}
    for mode,name in [('debug','app-debug.apk'),('release','app-release-unsigned.apk')]:
        sizes[mode+'ApkBytes'] = (ROOT/'app/build/outputs/apk'/mode/name).stat().st_size
    (REPORT/'performance.json').write_text(json.dumps(sizes, indent=2))
    if args.startup:
        adb = ROOT/'.tools/android-sdk/platform-tools/adb.exe'
        def shell(*cmd): return subprocess.run([str(adb),'shell',*cmd], capture_output=True, text=True, check=True).stdout
        measurements = []
        for _ in range(5):
            shell('am','force-stop','ru.gorotaro.app')
            output = shell('am','start','-W','-n','ru.gorotaro.app/.MainActivity')
            measurements.append(int(next(line.split(':')[1].strip() for line in output.splitlines() if line.startswith('TotalTime:'))))
        report = {'build':'release, signed with test key for local QA', 'device':'Pixel 2 AVD, Android 15, x86_64, 2048 MiB RAM, WHPX, SwiftShader', 'metric':'am start -W TotalTime (TTID), process force-stopped before each run; filesystem caches remain warm', 'measurementsMs':measurements,'medianMs':statistics.median(measurements),'isReferencePhoneAcceptance':False}
        (REPORT/'release-startup.json').write_text(json.dumps(report, indent=2))
        print(json.dumps(report, indent=2))
    print(json.dumps(sizes, indent=2))
if __name__ == '__main__': main()
