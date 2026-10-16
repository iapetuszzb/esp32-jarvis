"""Download the Apache-2.0 Vosk small English model with a pinned checksum."""
import hashlib,shutil,tempfile,urllib.request,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
NAME='vosk-model-small-en-us-0.15'
SHA256='30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498'
target=ROOT/'android/app/src/main/assets'/NAME
if (target/'am/final.mdl').exists():
 print('Wake model already exists:',target)
else:
 with tempfile.TemporaryDirectory() as tmp:
  archive=Path(tmp)/'model.zip'
  urllib.request.urlretrieve('https://alphacephei.com/vosk/models/'+NAME+'.zip',archive)
  if hashlib.sha256(archive.read_bytes()).hexdigest()!=SHA256:raise RuntimeError('Wake model checksum mismatch')
  with zipfile.ZipFile(archive) as z:
   for n in z.namelist():
    if not n.startswith(NAME+'/') or '..' in Path(n).parts:raise RuntimeError('Invalid archive entry')
   z.extractall(tmp)
  target.parent.mkdir(parents=True,exist_ok=True);shutil.copytree(Path(tmp)/NAME,target)
 print('Verified wake model installed:',target)
