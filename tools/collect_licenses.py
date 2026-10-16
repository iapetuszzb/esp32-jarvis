"""Refresh upstream license texts for binary redistributions; review after updates."""
from pathlib import Path
import urllib.request,subprocess,json,base64
root=Path(__file__).resolve().parents[1]/'licenses';root.mkdir(exist_ok=True)
sources={
 'Vosk-Apache-2.0.txt':'https://raw.githubusercontent.com/alphacep/vosk-api/master/COPYING',
 'JNA-LICENSE.txt':'https://raw.githubusercontent.com/java-native-access/jna/5.13.0/LICENSE',
 'Arduino-ESP32-LGPL-2.1.txt':'https://raw.githubusercontent.com/espressif/arduino-esp32/2.0.17/LICENSE.md',
 'ArduinoJson-MIT.txt':'https://raw.githubusercontent.com/bblanchon/ArduinoJson/v6.21.5/LICENSE.txt',
 'certifi-MPL-2.0.txt':'https://raw.githubusercontent.com/certifi/python-certifi/master/LICENSE',
 'OkHttp-Apache-2.0.txt':'https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/LICENSE.txt',
}
for name,url in sources.items():
 try:data=urllib.request.urlopen(url,timeout=15).read()
 except Exception:
  owner,repo,ref,path=url.removeprefix('https://raw.githubusercontent.com/').split('/',3)
  result=subprocess.check_output(['gh','api',f'repos/{owner}/{repo}/contents/{path}?ref={ref}'])
  data=base64.b64decode(json.loads(result)['content'])
 if len(data)<100:raise RuntimeError('Unexpected license response: '+name)
 (root/name).write_bytes(data);print(name,len(data))
