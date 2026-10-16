"""One model, one GPU thread. All model/session mutations happen on that thread."""
import base64
import io
import os
import tempfile
from pathlib import Path
import numpy as np


def pcm_bytes(wave):
    if wave is None:
        return b''
    if hasattr(wave, 'detach'):
        wave = wave.detach().float().cpu().numpy()
    a = np.asarray(wave).reshape(-1)
    return (np.clip(a, -1, 1) * 32767).astype('<i2').tobytes()


class MiniCPMBackend:
    name = 'MiniCPM-o-4.5 BF16 SDPA'

    def load(self):
        os.environ.setdefault('HF_HUB_OFFLINE', '1')
        os.environ.setdefault('TRANSFORMERS_OFFLINE', '1')
        import torch
        import librosa
        from transformers import AutoModel
        torch.set_num_threads(8)
        self.path = Path(os.environ['JARVIS_MODEL_PATH'])
        self.model = AutoModel.from_pretrained(
            str(self.path), trust_remote_code=True, torch_dtype=torch.bfloat16,
            attn_implementation='sdpa', init_vision=True, init_audio=True, init_tts=True
        ).eval().cuda()
        self.model.init_tts()
        self.ref_path = str(self.path / 'assets/HT_ref_audio.wav')
        self.ref, _ = librosa.load(self.ref_path, sr=16000, mono=True)
        self.duplex = None

    def prepare(self, proactive=False):
        self.model.reset_session(reset_token2wav_cache=True)
        self.duplex = self.model.as_duplex(sliding_window_mode='basic',
            basic_window_high_tokens=8000, basic_window_low_tokens=6000)
        instruction = ('你叫贾维斯，是用户的视觉语音助手。用简短自然的中文回答。'
                       '根据实际看见和听见的内容回答；不确定时明确说明。')
        instruction += ('画面出现值得关注的新变化时，可以主动简短提醒。' if proactive else
                        '主要在用户说话或提问后回答，不要反复播报没有变化的画面。')
        self.duplex.prepare(prefix_system_prompt='Streaming Omni Conversation.',
                            suffix_system_prompt=instruction, ref_audio=self.ref,
                            prompt_wav_path=self.ref_path)

    def push(self, pcm, frame):
        import torch
        audio = np.frombuffer(pcm, dtype='<i2').astype(np.float32) / 32768.0
        self.duplex.streaming_prefill(audio_waveform=audio,
            frame_list=[frame] if frame is not None else [], max_slice_nums=1,
            batch_vision_feed=False)
        r = self.duplex.streaming_generate(prompt_wav_path=self.ref_path,
            max_new_speak_tokens_per_chunk=20, decode_mode='sampling')
        torch.cuda.synchronize()
        return {'text': r.get('text', ''), 'listen': bool(r['is_listen']),
                'end_of_turn': bool(r.get('end_of_turn')),
                'audio': b'' if r['is_listen'] else pcm_bytes(r.get('audio_waveform'))}

    def reset(self):
        self.duplex = None
        self.model.reset_session(reset_token2wav_cache=True)

    def ask(self, question, frame):
        import soundfile as sf
        self.model.reset_session(reset_token2wav_cache=True)
        msg = self.model.get_sys_prompt(ref_audio=self.ref, mode='omni', language='zh')
        msg['content'].append('你叫贾维斯。请用一两句中文回答；无法看到的内容不要猜测。')
        content = ([frame] if frame is not None else []) + [question]
        with tempfile.TemporaryDirectory() as td:
            output = str(Path(td) / 'reply.wav')
            text = self.model.chat(msgs=[msg, {'role': 'user', 'content': content}],
                max_new_tokens=160, use_tts_template=True, generate_audio=True,
                output_audio_path=output, omni_mode=frame is not None,
                enable_thinking=False, do_sample=True, temperature=0.3, max_slice_nums=1)
            if not Path(output).exists():
                raise RuntimeError('Model returned text without generated audio')
            data = Path(output).read_bytes()
        return {'text': text, 'wav': base64.b64encode(data).decode()}


class MockBackend:
    name = 'mock (no AI inference)'

    def load(self):
        pass

    def prepare(self, proactive=False):
        self.count = 0

    def push(self, pcm, frame):
        self.count += 1
        return {'text': '测试回复，有图像。' if frame is not None else '测试回复，没有图像。',
                'listen': False, 'end_of_turn': True, 'audio': b'\0\0' * 2400}

    def reset(self):
        pass

    def ask(self, question, frame):
        import wave
        f = io.BytesIO()
        with wave.open(f, 'wb') as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(24000)
            w.writeframes(b'\0\0' * 2400)
        return {'text': '测试回复：' + question, 'wav': base64.b64encode(f.getvalue()).decode()}
