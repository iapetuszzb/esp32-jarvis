import base64
import io
import time
import pytest
from PIL import Image
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect
from jarvis.app import create_app
from jarvis.backend import MockBackend, pcm_bytes
import numpy as np

TOKEN = 'integration-test-secret-not-a-real-credential'
AUTH = {'Authorization': 'Bearer ' + TOKEN}


@pytest.fixture
def client():
    with TestClient(create_app(MockBackend(), TOKEN)) as c:
        yield c


def jpeg():
    f = io.BytesIO(); Image.new('RGB', (640, 480), 'red').save(f, 'JPEG'); return f.getvalue()


def upload(c):
    return c.post('/v1/frame/jarvis', content=jpeg(), headers=dict(AUTH, **{'Content-Type':'image/jpeg'}))


def test_camera_auth_validation_and_status(client):
    assert client.get('/health').json()['protocol'] == 1
    assert client.get('/v1/status/jarvis').status_code == 401
    assert client.get('/v1/status/jarvis', headers=AUTH).json()['camera_online'] is False
    assert upload(client).json()['sequence'] == 1
    assert upload(client).json()['sequence'] == 2
    assert client.get('/v1/status/jarvis', headers=AUTH).json()['camera_online'] is True
    assert client.post('/v1/frame/jarvis', content=b'not jpeg', headers=dict(AUTH, **{'Content-Type':'image/jpeg'})).status_code == 400
    assert client.post('/v1/frame/jarvis', content=jpeg(), headers=AUTH).status_code == 415
    assert client.post('/v1/frame/bad.session', content=jpeg(), headers=dict(AUTH, **{'Content-Type':'image/jpeg'})).status_code == 400
    assert client.post('/v1/frame/jarvis', content=b'x'*(1024*1024+1), headers=dict(AUTH, **{'Content-Type':'image/jpeg'})).status_code == 413


def test_stale_frame_is_not_used(monkeypatch):
    monkeypatch.setenv('JARVIS_FRAME_TTL', '0.001')
    with TestClient(create_app(MockBackend(), TOKEN)) as c:
        upload(c); time.sleep(0.02)
        r = c.post('/v1/ask', json={'session':'jarvis','question':'what do you see?'}, headers=AUTH)
        assert r.status_code == 200
        assert r.json()['camera_online'] is False
        assert base64.b64decode(r.json()['wav']).startswith(b'RIFF')


def test_realtime_uses_frame_and_excludes_other_gpu_requests(client):
    upload(client)
    with client.websocket_connect('/v1/live?session=jarvis', headers=AUTH) as ws:
        ws.send_json({'type':'start','proactive':True})
        ready = ws.receive_json(); assert ready['input_rate'] == 16000
        r = client.post('/v1/ask', headers=AUTH, json={'session':'jarvis','question':'hello'})
        assert r.status_code == 409
        ws.send_json({'type':'audio','seq':0,'pcm':base64.b64encode(b'\0\0'*16000).decode()})
        output = ws.receive_json()
        assert output['type'] == 'output' and output['camera_online']
        assert '有图像' in output['text'] and output['sample_rate'] == 24000
        assert len(base64.b64decode(output['pcm'])) % 2 == 0
        ws.send_json({'type':'stop'})
        with pytest.raises(WebSocketDisconnect): ws.receive_json()
    assert client.post('/v1/ask', headers=AUTH, json={'session':'jarvis','question':'hello'}).status_code == 200


@pytest.mark.parametrize('message',[
    {'type':'audio','seq':0,'pcm':'invalid!'},
    {'type':'audio','seq':0,'pcm':base64.b64encode(b'\0\0'*8000).decode()},
    {'type':'audio','seq':'zero','pcm':base64.b64encode(b'\0\0'*16000).decode()},
])
def test_realtime_rejects_malformed_audio(client, message):
    with client.websocket_connect('/v1/live', headers=AUTH) as ws:
        ws.send_json({'type':'start'}); assert ws.receive_json()['type'] == 'ready'
        ws.send_json(message); assert ws.receive_json()['type'] == 'error'
        with pytest.raises(WebSocketDisconnect): ws.receive_json()


def test_websocket_requires_authorization(client):
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect('/v1/live'): pass


def test_pcm_output_endianness_clipping():
    data=pcm_bytes(np.array([-2.0,0.0,2.0],dtype=np.float32))
    assert np.frombuffer(data,dtype='<i2').tolist() == [-32767,0,32767]


def test_pair_code_one_use_persists_and_rate_limited(monkeypatch, tmp_path):
    monkeypatch.setenv('JARVIS_PAIR_CODE','384027159628')
    monkeypatch.setenv('JARVIS_PAIR_USED_PATH',str(tmp_path/'paired'))
    with TestClient(create_app(MockBackend(),TOKEN)) as c:
        assert c.post('/v1/pair',json={'code':'12345678'}).status_code==401
        assert c.post('/v1/pair',json={'code':'384027159628'}).json()['token']==TOKEN
        assert c.post('/v1/pair',json={'code':'384027159628'}).status_code==401
    with TestClient(create_app(MockBackend(),TOKEN)) as c:
        assert c.post('/v1/pair',json={'code':'384027159628'}).status_code==401
        for _ in range(7):c.post('/v1/pair',json={'code':'12345678'})
        assert c.post('/v1/pair',json={'code':'12345678'}).status_code==429
