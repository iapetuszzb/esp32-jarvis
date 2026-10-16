# 系统与协议 v1

## 画面路径

v0.2.0 默认 ESP32 → 热点内 AES-GCM TCP / UDP → Android 中转 → HTTPS API，详见 [UDP_RELAY.md](UDP_RELAY.md)。ESP32 优先连接热点 DHCP 网关 TCP 19422，连接失败时回退 UDP 19421。TCP 每帧等待手机重组确认，超时关闭连接并丢帧。手机只保留最新完整帧，不补发旧图；App 前台中转服务独立于麦克风。保留下面的 HTTPS 直传模式供选择。

手机热点负责 NAT 和公网出口，ESP32 主动向服务器发 HTTPS，无需服务器回连 ESP32，也不用手机 App 持续拉取 MJPEG。手机 App 可以只管理语音、唤醒、配置和状态，ColorOS 的后台负担较小。默认不是把 OV5640 的 5 MP 画面全部上传：从 640×480 JPEG、约 1 fps 起步。带宽取决于实际 JPEG 大小，例如每帧 40 KB、每秒一帧约为 144 MB/小时，另加 TLS 和音频流量。

模型输入是采样画面与连续音频，不是 30 fps 视频播放器。适合询问面前物体、读少量文字和观察慢变化；快速运动、细小文字和遮挡需要更高分辨率/采样率，延迟需重新测量。

## HTTP

除 `/health` 和 `/v1/pair` 外，均需 `Authorization: Bearer <token>`。公网必须 HTTPS。默认 session 为 `jarvis`，只允许 1–48 个字母、数字、下划线、短横线。

| 方法与路径 | 内容 |
|---|---|
| GET `/health` | 模型、协议号、GPU busy；无私密配置 |
| POST `/v1/frame/{session}` | `Content-Type: image/jpeg`，原始 JPEG ≤1 MiB、尺寸 ≤4096，返回 sequence |
| GET `/v1/status/{session}` | camera_online、frame_age_seconds、sequence、busy |
| POST `/v1/ask` | JSON `{ "session":"jarvis", "question":"你看到了什么？" }`；返回 text、base64 WAV、画面状态；实时会话占 GPU 时 409 |
| POST `/v1/pair` | JSON `{ "code":"私下提供的一次性码" }`；返回 token 和 session，成功一次即作废，每分钟全局最多 8 次尝试 |

帧到达后转换为 RGB，最大缩到 1280×960，保留最新一帧。模型取帧时超过 4 秒就传入空画面，并返回 `camera_online=false`。未经认证的数据在图像解析前拒绝。

中转通过 `X-Frame-Captured-At-Ms` 传递原始捕获时间；服务端拒绝超时旧帧，迟到的旧捕获不会覆盖新图。缓存替换时立即关闭旧 PIL 图像，每 250 ms 后台扫描并关闭超过 TTL 的像素，即使没有后续请求也会清理。会话计数/时间元数据最多 32 条。状态查询不复制图像，模型需要的副本在对应推理结束后关闭。

## WebSocket

`wss://<server>/v1/live?session=jarvis`，握手使用同一 Authorization header。

1. 手机发 `{"type":"start","proactive":false}`。
2. 服务完成模型准备后发 `ready`，包含 `input_rate:16000`、`output_rate:24000`、`chunk_samples:16000`。
3. 手机每秒发 `{"type":"audio","seq":0,"pcm":"<base64>"}`。PCM 必须为 **16 kHz、单声道、有符号小端 16 bit，32000 字节**，seq 严格递增。
4. 服务每块发 `output`：seq、text、listen、end_of_turn、pcm、sample_rate、camera_online、frame_age_seconds、processing_ms。输出 pcm 为 **24 kHz 单声道 PCM16LE**；listen=true 时不播放音频。
5. 手机发 `{"type":"stop"}` 结束。模型状态清除，释放 GPU；最长会话 600 秒，接收空闲 15 秒超时。

processing_ms 从服务收到该音频块开始，包含服务端排队与推理，不包括手机录音分块、蓝牙、上行/下行网络或实际播放缓冲。不能将此数值直接解释为端到端对话延迟。

## 并发与资源

一个 GPU 执行线程串行处理模型所有状态。实时会话持有全局 GPU 租约，文字请求和第二个实时会话会被拒绝。音频队列最多 3 块，积压时终止会话；Android 另外限制 WebSocket 和播放队列。客户端取消时仍等待已开始的 GPU 工作结束再释放租约，避免模型被并发改写。

每轮调用 `as_duplex(sliding_window_mode="basic", basic_window_high_tokens=8000, basic_window_low_tokens=6000)`，启用模型支持的有限上下文窗口。会话结束后 reset。此配置降低 KV 缓存无限增长的风险，仍需进行实际场景长时间压测；当前完成的公网验证约 110 秒，不代表数小时运行稳定性。

模型可以边听边生成，但当前 Android 播放队列没有完整实现用户打断后撤销全部待播音频。Vosk 是受限词表语音识别，不是训练好的专用唤醒网络；噪声、口音、耳机降噪影响误唤醒和漏唤醒，需实物测试。
