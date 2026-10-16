"""Real network + GPU smoke check. Connection JSON is private and never printed.

pip install requests websocket-client pillow soundfile numpy
python tools/smoke_live.py --config /private/connection.json --audio question.wav --out artifacts/smoke
"""
import argparse,base64,io,json,statistics,threading,time,wave
from pathlib import Path
import numpy as np
import requests
import soundfile as sf
import websocket
from PIL import Image,ImageDraw

p=argparse.ArgumentParser()
p.add_argument('--config',required=True);p.add_argument('--audio',required=True)
p.add_argument('--out',default='artifacts/smoke');p.add_argument('--seconds',type=int,default=110)
a=p.parse_args();cfg=json.loads(Path(a.config).read_text());out=Path(a.out);out.mkdir(parents=True,exist_ok=True)
url=cfg['server'].rstrip('/');headers={'Authorization':'Bearer '+cfg['token']};session=cfg.get('session','jarvis')
image=Image.new('RGB',(640,480),'white');d=ImageDraw.Draw(image)
d.rectangle((40,90,210,260),fill='red');d.ellipse((280,100,430,250),fill='blue');d.polygon([(500,270),(610,270),(555,90)],fill='green')
image.save(out/'input.jpg');b=io.BytesIO();image.save(b,format='JPEG');jpeg=b.getvalue()
report={'input':'synthetic colored shapes + independent Chinese TTS, no physical devices','chunks':[],'frame_errors':[]}
http=requests.Session()
r=http.get(url+'/health',timeout=30);r.raise_for_status();report['health']=r.json()
assert http.get(url+'/v1/status/'+session,timeout=30).status_code==401
stop=threading.Event()
def frames():
 s=requests.Session()
 while not stop.is_set():
  try:
   r=s.post(url+'/v1/frame/'+session,data=jpeg,headers=dict(headers,**{'Content-Type':'image/jpeg'}),timeout=15);r.raise_for_status()
  except Exception as ex:report['frame_errors'].append(type(ex).__name__)
  stop.wait(0.9)
thread=threading.Thread(target=frames,daemon=True);thread.start()
try:
 time.sleep(2)
 t=time.monotonic()
 r=http.post(url+'/v1/ask',headers=headers,json={'session':session,'question':'最左边是什么颜色和什么形状？请用一句中文回答。'},timeout=150)
 r.raise_for_status();answer=r.json();wav=base64.b64decode(answer.pop('wav'))
 (out/'text-ask-reply.wav').write_bytes(wav)
 report['ask']=dict(answer,seconds=time.monotonic()-t,wav_bytes=len(wav))
 print('ASK',json.dumps(report['ask'],ensure_ascii=False),flush=True)
 samples,sr=sf.read(a.audio,dtype='float32');assert sr==16000 and samples.ndim==1,'Input must be mono 16kHz WAV'
 pcm=(np.clip(samples,-1,1)*32767).astype('<i2').tobytes()
 stream=websocket.create_connection(url.replace('https://','wss://').replace('http://','ws://')+'/v1/live?session='+session,header=headers,timeout=120)
 stream.send(json.dumps({'type':'start','proactive':False}))
 ready=json.loads(stream.recv());assert ready['type']=='ready',ready
 report['ready']=ready;started=time.monotonic();audio=[];errors=[];receive_done=threading.Event()
 def receive():
  try:
   while True:
    raw=stream.recv()
    if not raw:break
    m=json.loads(raw)
    if m['type']=='error':raise RuntimeError(m.get('message'))
    if m['type']!='output':continue
    chunk=base64.b64decode(m.pop('pcm'));m['audio_bytes']=len(chunk);m['elapsed_seconds']=time.monotonic()-started
    if chunk:audio.append(chunk)
    report['chunks'].append(m)
    if m['text'] or m['seq']%10==0:print('LIVE',json.dumps(m,ensure_ascii=False),flush=True)
  except Exception as ex:errors.append(str(ex))
  finally:receive_done.set()
 receiver=threading.Thread(target=receive,daemon=True);receiver.start()
 for seq in range(a.seconds):
  if receive_done.is_set():break
  offset=seq*32000
  # Repeat the question after 60 seconds to check a later turn in the same session.
  if seq>=60:offset=(seq-60)*32000
  chunk=pcm[offset:offset+32000].ljust(32000,b'\0')
  stream.send(json.dumps({'type':'audio','seq':seq,'pcm':base64.b64encode(chunk).decode()}))
  time.sleep(max(0,started+seq+1-time.monotonic()))
 stream.send(json.dumps({'type':'stop'}));receiver.join(30);stream.close()
 assert not receiver.is_alive(),'Receive loop did not finish'
 report['errors']=errors
 with wave.open(str(out/'live-reply.wav'),'wb') as w:
  w.setnchannels(1);w.setsampwidth(2);w.setframerate(24000);w.writeframes(b''.join(audio))
 rows=report['chunks'];times=[m['processing_ms'] for m in rows]
 report['summary']={'chunks':len(rows),'mean_processing_ms':statistics.mean(times) if times else None,
  'p95_processing_ms':sorted(times)[int((len(times)-1)*.95)] if times else None,
  'first_audio_seconds':next((m['elapsed_seconds'] for m in rows if m['audio_bytes']),None),
  'audio_seconds':sum(map(len,audio))/48000,'text':''.join(m['text'] for m in rows),
  'camera_online_chunks':sum(m['camera_online'] for m in rows)}
 time.sleep(2);r=http.get(url+'/v1/status/'+session,headers=headers,timeout=30);r.raise_for_status();report['final_status']=r.json()
 assert not errors,errors
 assert len(rows)==a.seconds and audio,'Missing inference chunks/audio'
 assert not report['final_status']['busy'],'GPU lease not released'
 assert all(m['camera_online'] for m in rows),'Camera frame became stale'
 print('SUMMARY',json.dumps(report['summary'],ensure_ascii=False),flush=True)
finally:
 stop.set();thread.join(20)
 (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
