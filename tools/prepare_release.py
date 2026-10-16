"""Collect built artifacts and sanitized synthetic validation; never read secrets."""
import hashlib,shutil,zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1];out=root/'artifacts/release';out.mkdir(parents=True,exist_ok=True)
shutil.copy2(root/'android/app/build/outputs/apk/release/app-release.apk',out/'jarvis-android-v0.1.0.apk')
for src,dst in [('report.json','validation-report.json'),('input.jpg','validation-input.jpg'),('live-reply.wav','validation-live-reply.wav')]:
 shutil.copy2(root/'artifacts/smoke'/src,out/dst)
with zipfile.ZipFile(out/'third-party-notices.zip','w',zipfile.ZIP_DEFLATED) as z:
 z.write(root/'THIRD_PARTY_NOTICES.md','THIRD_PARTY_NOTICES.md')
 for f in (root/'licenses').iterdir():
  if f.is_file():z.write(f,'licenses/'+f.name)
assets=sorted(f for f in out.iterdir() if f.is_file() and f.name!='SHA256SUMS.txt')
(out/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name+'\n' for f in assets),encoding='ascii')
for f in assets:print(f.name,f.stat().st_size)
