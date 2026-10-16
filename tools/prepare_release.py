"""Collect built artifacts and sanitized synthetic validation; never read secrets."""
import argparse,hashlib,json,shutil,zipfile
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--out',default='artifacts/release');parser.add_argument('--android-only',action='store_true');args=parser.parse_args()
root=Path(__file__).resolve().parents[1];out=root/args.out;out.mkdir(parents=True,exist_ok=True)
apkdir=root/'android/app/build/outputs/apk/release'
metadata=json.loads((apkdir/'output-metadata.json').read_text())['elements'][0]
version=metadata['versionName']
shutil.copy2(apkdir/metadata['outputFile'],out/f'jarvis-android-v{version}.apk')
if not args.android_only:
 for src,dst in [('report.json','validation-report.json'),('input.jpg','validation-input.jpg'),('live-reply.wav','validation-live-reply.wav')]:
  shutil.copy2(root/'artifacts/smoke'/src,out/dst)
with zipfile.ZipFile(out/'third-party-notices.zip','w',zipfile.ZIP_DEFLATED) as z:
 z.write(root/'THIRD_PARTY_NOTICES.md','THIRD_PARTY_NOTICES.md')
 for f in (root/'licenses').iterdir():
  if f.is_file():z.write(f,'licenses/'+f.name)
assets=sorted(f for f in out.iterdir() if f.is_file() and f.name!='SHA256SUMS.txt')
(out/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name+'\n' for f in assets),encoding='ascii')
for f in assets:print(f.name,f.stat().st_size)
