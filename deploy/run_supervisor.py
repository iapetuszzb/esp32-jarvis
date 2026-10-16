"""Run the single GPU service and optional temporary HTTPS tunnel on a rented host."""
import os,signal,subprocess,time
from pathlib import Path
from dotenv import load_dotenv

root=Path(__file__).resolve().parent
load_dotenv(root/'.env',override=True)
children=[]
running=True
def stop(*_):
    global running
    running=False
signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
logs=root/'logs';logs.mkdir(exist_ok=True)
commands=[('server',[os.environ.get('JARVIS_PYTHON','/data/jarvis-minicpm/venv/bin/python'),'-u','-m','jarvis'])]
if (root/'cloudflared').exists():
    commands.append(('tunnel',[str(root/'cloudflared'),'--no-autoupdate','tunnel','--protocol','http2','--url','http://127.0.0.1:8000']))
env=dict(os.environ,OPENBLAS_NUM_THREADS='8',OMP_NUM_THREADS='8',NUMBA_NUM_THREADS='8')
try:
    for name,cmd in commands:
        f=(logs/(name+'.log')).open('a')
        children.append((name,cmd,f,subprocess.Popen(cmd,cwd=root,env=env,stdout=f,stderr=subprocess.STDOUT)))
    (root/'supervisor.pid').write_text(str(os.getpid()))
    while running:
        time.sleep(2)
        for i,(name,cmd,f,p) in enumerate(children):
            if p.poll() is not None:
                print(name,'exited',p.returncode,'restarting in 3s',flush=True);time.sleep(3)
                if running:children[i]=(name,cmd,f,subprocess.Popen(cmd,cwd=root,env=env,stdout=f,stderr=subprocess.STDOUT))
finally:
    for _,_,_,p in children:
        if p.poll() is None:p.terminate()
    for _,_,f,p in children:
        try:p.wait(timeout=20)
        except subprocess.TimeoutExpired:p.kill();p.wait()
        f.close()
    (root/'supervisor.pid').unlink(missing_ok=True)
