import asyncio
import base64
import binascii
import hmac
import io
import logging
import os
import re
import time
import hashlib
from pathlib import Path
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager, suppress
from dataclasses import dataclass
from functools import partial

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, Field

from .backend import MiniCPMBackend, MockBackend

load_dotenv()
log = logging.getLogger('jarvis')
SESSION = re.compile(r'^[a-zA-Z0-9_-]{1,48}$')
MAX_JPEG = 1024 * 1024
Image.MAX_IMAGE_PIXELS = 16_777_216


@dataclass
class Frame:
    image: Image.Image
    received: float
    sequence: int


def valid_session(value):
    if not SESSION.fullmatch(value):
        raise HTTPException(400, '会话 ID 只能包含 1–48 个英文字母、数字、下划线和短横线')
    return value


def create_app(backend=None, token=None):
    expected = token if token is not None else os.getenv('JARVIS_TOKEN', '')
    if len(expected) < 24 or expected.startswith('replace-'):
        raise RuntimeError('Set JARVIS_TOKEN to a random secret of at least 24 characters')
    model = backend or (MockBackend() if os.getenv('JARVIS_MOCK') == '1' else MiniCPMBackend())
    executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix='jarvis-gpu')
    frames = {}
    gpu = asyncio.Lock()
    frame_ttl = float(os.getenv('JARVIS_FRAME_TTL', '4'))
    pair_code = os.getenv('JARVIS_PAIR_CODE', '')
    pair_until = time.monotonic() + float(os.getenv('JARVIS_PAIR_TTL', '86400'))
    pair_attempts = deque()
    pair_used_path = Path(os.getenv('JARVIS_PAIR_USED_PATH', '.pair-used'))
    pair_hash = hashlib.sha256(pair_code.encode()).hexdigest()
    paired = pair_used_path.exists() and pair_used_path.read_text().strip() == pair_hash

    async def inference(fn, *args):
        return await asyncio.get_running_loop().run_in_executor(executor, partial(fn, *args))

    @asynccontextmanager
    async def lifespan(app):
        await inference(model.load)
        app.state.ready = True
        log.info('Model ready: %s', model.name)
        try:
            yield
        finally:
            executor.shutdown(wait=True, cancel_futures=True)

    app = FastAPI(title='ESP32 Jarvis', version='0.1.0', lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)

    def authorized(header):
        return hmac.compare_digest((header or '').encode('utf-8'), ('Bearer ' + expected).encode('utf-8'))

    def auth(request):
        if not authorized(request.headers.get('authorization')):
            raise HTTPException(401, 'Invalid access token')

    def latest(session):
        f = frames.get(session)
        age = time.monotonic() - f.received if f else None
        return (f.image.copy() if f and age <= frame_ttl else None), age, f

    @app.get('/health')
    async def health():
        return {'ready': True, 'model': model.name, 'protocol': 1, 'busy': gpu.locked()}

    class Pair(BaseModel):
        code: str = Field(min_length=8, max_length=64)

    @app.post('/v1/pair')
    async def pair(body: Pair):
        nonlocal paired
        now = time.monotonic()
        while pair_attempts and now - pair_attempts[0] > 60:
            pair_attempts.popleft()
        if len(pair_attempts) >= 8:
            raise HTTPException(429, '尝试次数过多，请一分钟后再试')
        pair_attempts.append(now)
        if not pair_code or paired or now > pair_until or not hmac.compare_digest(body.code.encode(), pair_code.encode()):
            raise HTTPException(401, '配对码错误、已使用或已过期，请导入连接配置文件')
        pair_used_path.write_text(pair_hash)
        os.chmod(pair_used_path, 0o600)
        paired = True
        return {'token': expected, 'session': 'jarvis'}

    @app.post('/v1/frame/{session}')
    async def frame_upload(session: str, request: Request):
        auth(request); valid_session(session)
        if request.headers.get('content-type', '').split(';')[0] != 'image/jpeg':
            raise HTTPException(415, 'Send image/jpeg')
        data = bytearray()
        async for chunk in request.stream():
            data.extend(chunk)
            if len(data) > MAX_JPEG:
                raise HTTPException(413, 'JPEG exceeds 1 MiB')
        try:
            with Image.open(io.BytesIO(data)) as im:
                if im.format != 'JPEG' or im.width > 4096 or im.height > 4096:
                    raise ValueError('Unsupported image dimensions or type')
                im.thumbnail((1280, 960))
                image = im.convert('RGB')
        except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError):
            raise HTTPException(400, 'Invalid JPEG')
        now = time.monotonic()
        for key in list(frames):
            if now - frames[key].received > 120:
                frames.pop(key)
        if session not in frames and len(frames) >= 32:
            raise HTTPException(429, 'Too many camera sessions')
        previous = frames.get(session)
        sequence = previous.sequence + 1 if previous else 1
        frames[session] = Frame(image, now, sequence)
        return {'ok': True, 'sequence': sequence}

    @app.get('/v1/status/{session}')
    async def status(session: str, request: Request):
        auth(request); valid_session(session)
        image, age, f = latest(session)
        return {'camera_online': image is not None, 'frame_age_seconds': age,
                'sequence': f.sequence if f else 0, 'busy': gpu.locked(), 'model': model.name}

    class Ask(BaseModel):
        session: str = Field(min_length=1, max_length=48)
        question: str = Field(min_length=1, max_length=1000)

    @app.post('/v1/ask')
    async def ask(body: Ask, request: Request):
        auth(request); valid_session(body.session)
        try:
            await asyncio.wait_for(gpu.acquire(), 0.1)
        except asyncio.TimeoutError:
            raise HTTPException(409, '语音会话正在运行，请先结束会话')
        try:
            image, age, _ = latest(body.session)
            # Shield GPU work: an HTTP disconnect must not release the lease early.
            work = asyncio.create_task(inference(model.ask, body.question, image))
            try:
                response = await asyncio.shield(work)
            except asyncio.CancelledError:
                await work
                raise
            return dict(response, camera_online=image is not None, frame_age_seconds=age)
        except Exception:
            log.exception('Text inference failed')
            raise HTTPException(500, '模型推理失败，请检查服务器日志')
        finally:
            gpu.release()

    @app.websocket('/v1/live')
    async def live(ws: WebSocket, session: str = 'jarvis'):
        if not authorized(ws.headers.get('authorization')) or not SESSION.fullmatch(session):
            await ws.close(code=1008); return
        await ws.accept()
        send_lock = asyncio.Lock()

        async def send(data):
            async with send_lock:
                await ws.send_json(data)

        try:
            await asyncio.wait_for(gpu.acquire(), 0.1)
        except asyncio.TimeoutError:
            await send({'type': 'error', 'message': 'GPU 正被另一个会话使用'})
            await ws.close(code=1013); return
        queue = asyncio.Queue(maxsize=3)
        receiver = None
        try:
            hello = await asyncio.wait_for(ws.receive_json(), 10)
            if hello.get('type') != 'start':
                raise ValueError('First message must be start')
            await inference(model.prepare, bool(hello.get('proactive', False)))
            await send({'type': 'ready', 'input_rate': 16000, 'output_rate': 24000,
                        'chunk_samples': 16000, 'model': model.name})

            async def receive():
                previous = -1
                while True:
                    message = await asyncio.wait_for(ws.receive_json(), 15)
                    if message.get('type') == 'stop':
                        await queue.put(None); return
                    if message.get('type') != 'audio':
                        raise ValueError('Expected audio')
                    encoded = message.get('pcm', '')
                    if not isinstance(encoded, str) or len(encoded) > 43000:
                        raise ValueError('Audio chunk too large')
                    try:
                        pcm = base64.b64decode(encoded, validate=True)
                    except (ValueError, binascii.Error):
                        raise ValueError('Invalid PCM encoding')
                    seq = message.get('seq')
                    if not isinstance(seq, int) or seq <= previous or len(pcm) != 32000:
                        raise ValueError('Expected ordered 1 second 16 kHz PCM16 mono chunks')
                    previous = seq
                    if queue.full():
                        raise ValueError('音频处理积压，请重新唤醒或检查网络/GPU')
                    queue.put_nowait((seq, pcm, time.monotonic()))

            receiver = asyncio.create_task(receive())
            started = time.monotonic()
            while time.monotonic() - started < 600:
                pending = asyncio.create_task(queue.get())
                done, _ = await asyncio.wait([pending, receiver], return_when=asyncio.FIRST_COMPLETED)
                if receiver in done:
                    error = receiver.exception()
                    if error:
                        pending.cancel()
                        with suppress(asyncio.CancelledError): await pending
                        raise error
                    if pending not in done:
                        # stop was queued; drain through the sentinel.
                        item = await pending
                    else:
                        item = pending.result()
                else:
                    item = pending.result()
                if item is None:
                    break
                seq, pcm, received = item
                image, age, _ = latest(session)
                work = asyncio.create_task(inference(model.push, pcm, image))
                try:
                    result = await asyncio.shield(work)
                except asyncio.CancelledError:
                    await work
                    raise
                await send({'type': 'output', 'seq': seq, 'text': result['text'],
                    'listen': result['listen'], 'end_of_turn': result['end_of_turn'],
                    'pcm': base64.b64encode(result['audio']).decode(), 'sample_rate': 24000,
                    'camera_online': image is not None, 'frame_age_seconds': age,
                    'processing_ms': round((time.monotonic() - received) * 1000)})
        except WebSocketDisconnect:
            pass
        except (ValueError, asyncio.TimeoutError) as ex:
            with suppress(Exception): await send({'type': 'error', 'message': str(ex)})
        except Exception:
            log.exception('Realtime session failed')
            with suppress(Exception): await send({'type': 'error', 'message': '服务端模型会话失败，请重新开始'})
        finally:
            if receiver:
                receiver.cancel()
                with suppress(asyncio.CancelledError, Exception): await receiver
            try:
                await inference(model.reset)
            finally:
                gpu.release()
                with suppress(Exception): await ws.close()
    return app
