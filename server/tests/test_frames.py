import time
import pytest
from PIL import Image
from fastapi.testclient import TestClient
from jarvis.app import create_app
from jarvis.backend import MockBackend
from jarvis.frames import FrameStore
from test_protocol import TOKEN, AUTH, upload, client


def test_replace_expire_and_bounded_metadata():
    now = [100.0]
    store = FrameStore(ttl=4, limit=2, clock=lambda: now[0])
    a, b = Image.new('RGB', (10, 10)), Image.new('RGB', (10, 10))
    assert store.put('a', a) == 1
    assert store.put('a', b) == 2
    with pytest.raises(ValueError): a.getpixel((0, 0))
    now[0] += 0.1
    delayed = Image.new('RGB', (10, 10))
    assert store.put('a', delayed, age=1) == 2
    with pytest.raises(ValueError): delayed.getpixel((0, 0))
    now[0] += 4.9
    store.expire()
    with pytest.raises(ValueError): b.getpixel((0, 0))
    image, age, f = store.latest('a')
    assert image is None and age == 5 and f.sequence == 2
    for i in range(20):
        store.put(str(i), Image.new('RGB', (10, 10)))
        now[0] += 5
    assert len(store.frames) <= 2
    store.close()


def test_background_expiry_without_requests(monkeypatch):
    monkeypatch.setenv('JARVIS_FRAME_TTL', '0.01')
    retained = []
    original = FrameStore.put
    def put(self, session, image, age=0):
        retained.append(image)
        return original(self, session, image, age)
    monkeypatch.setattr(FrameStore, 'put', put)
    with TestClient(create_app(MockBackend(), TOKEN)) as c:
        upload(c)
        time.sleep(0.4)  # no subsequent upload/status call may be needed for cleanup
        with pytest.raises(ValueError): retained[0].getpixel((0, 0))
        status = c.get('/v1/status/jarvis', headers=AUTH).json()
        assert not status['camera_online'] and status['sequence'] == 1


def test_capture_timestamp_rejects_delayed_relay_frame(client):
    import io
    output = io.BytesIO(); Image.new('RGB', (10, 10)).save(output, 'JPEG')
    headers = dict(AUTH, **{'Content-Type': 'image/jpeg',
        'X-Frame-Captured-At-Ms': str(int((time.time()-10)*1000))})
    assert client.post('/v1/frame/jarvis', content=output.getvalue(), headers=headers).status_code == 408
