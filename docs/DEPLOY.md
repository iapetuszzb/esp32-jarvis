# 云端 MiniCPM 服务

## 已验证配置

L40S 48 GB、Python 3.10.16、PyTorch 2.7.1+cu126、transformers 4.51.0、MiniCPM-o-4_5 BF16、SDPA、不启用 torch.compile。先安装系统 ffmpeg、libsndfile；云平台的 CUDA 驱动需与 PyTorch 相容。

```sh
python3.10 -m venv /data/jarvis-minicpm/venv
. /data/jarvis-minicpm/venv/bin/activate
pip install torch==2.7.1 torchvision==0.22.1 torchaudio==2.7.1 --index-url https://download.pytorch.org/whl/cu126
pip install -r server/requirements.txt -r server/requirements-model.txt
```

`minicpmo-utils==1.0.5` 是本次模型资产验证过的版本；不要无条件更新到 1.0.7。完整复制官方 [openbmb/MiniCPM-o-4_5](https://huggingface.co/openbmb/MiniCPM-o-4_5) 到 `/data/jarvis-minicpm/models/MiniCPM-o-4_5`，包括模型代码、tokenizer、`assets/HT_ref_audio.wav` 和语音合成依赖资产。遵守模型许可；`trust_remote_code=True` 会执行该模型目录中的 Python，部署前审阅并固定所用版本。

若 TTS 资产未随仓库提供，按该模型版本官方部署说明准备；单有语言模型 safetensors 不足以启动全双工。运行时设定离线 Hugging Face 模式，避免现场缺文件时悄悄下载其他版本。`requirements-model.txt` 固定核心兼容版本，不是全部传递依赖的锁文件；新的服务器应重新完成模型验收。

## 启动

```sh
mkdir -p /data/jarvis-server
cp -r server/jarvis /data/jarvis-server/
cp server/.env.example /data/jarvis-server/.env
chmod 600 /data/jarvis-server/.env
```

编辑 `.env`：生成独立的随机 `JARVIS_TOKEN`（至少 24 字符），设置模型路径；可选生成随机 `JARVIS_PAIR_CODE`（建议 12 位或更长随机码）。服务默认只监听 127.0.0.1:8000。不要把 SSH 密码填作 API token。

```sh
cd /data/jarvis-server
/data/jarvis-minicpm/venv/bin/python -u -m jarvis
```

另一个终端检查 `curl http://127.0.0.1:8000/health`。只运行一个 worker；多 worker 会重复加载模型且无法共享 GPU 租约。生产环境应使用平台提供的固定 HTTPS 域名/WebSocket 入口，或自有域名的反向代理与有效证书。

## 临时公网入口

本次功能验收使用 Cloudflare Quick Tunnel，因此不依赖租用平台开放 HTTP 端口。将 [cloudflared](https://github.com/cloudflare/cloudflared/releases) 官方 Linux amd64 文件放到 `/data/jarvis-server/cloudflared` 并设可执行；下载后根据官方 Release 校验 SHA256。

```sh
cloudflared tunnel --protocol http2 --url http://127.0.0.1:8000
```

日志给出随机 `https://....trycloudflare.com`。将该地址填入手机和 ESP32。Quick Tunnel 无长期可用性承诺，重建会换地址，国内移动网络连通性与境外节点延迟需要实测。它适合原型验收，长期使用推荐固定域名入口。

可把 `deploy/run_supervisor.py` 复制到 `/data/jarvis-server/run_supervisor.py`，与 `.env` 同目录运行。它同时守护模型服务和可选隧道，日志在 `logs/`，PID 在 `supervisor.pid`：

```sh
cd /data/jarvis-server
nohup /data/jarvis-minicpm/venv/bin/python -u run_supervisor.py >supervisor.log 2>&1 </dev/null &
```

要停服务，向 supervisor.pid 中的 PID 发送 SIGTERM，它会关闭两个子进程。仅重启 API 子进程可保留现有隧道 URL。整机重启后目前需重新启动 supervisor；该脚本不是系统开机服务。

## 手机连接 JSON

通过私密渠道发到手机，不提交 Git：

```json
{
  "server": "https://your-domain.example",
  "token": "replace-with-a-new-random-secret-at-least-24-characters",
  "session": "jarvis"
}
```

App 导入后将连接参数写入应用私有存储。改变域名需要同时重新给 ESP32 配网。已使用的配对码哈希记录在 `.pair-used`，更新 `.env` 中的码才能重新配对；导入 JSON 不依赖配对码。

默认不记录媒体到磁盘，推理日志保留在服务端。当前部署是单持有人原型，不提供账户隔离、计费和生产级运维。实例即使空闲也可能按 GPU 租用时间计费；停止进程不等于云平台停止计费，需要在平台控制台释放/关机。
