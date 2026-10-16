ESP32-S3 + OV5640 通过手机热点上传画面，Android App 本地监听 Hey Jarvis，将耳机麦克风音频通过 WebSocket 发给 MiniCPM-o 4.5，再把模型语音交给蓝牙耳机播放。

这是首个可构建的原型发布。**云端和公网合成输入测试已通过，ESP32 实际烧录、OPPO / Enco R4 实物联调仍待完成。**

安装时下载 `jarvis-android-v0.1.0.apk`；烧录时下载并完整解压 `jarvis-esp32s3-firmware.zip`，其中包含合并固件、Windows 烧录脚本和说明。仅需要 BIN 时可下载 `jarvis-esp32s3-merged.bin`，写入地址 **0x0**。固件针对项目资料中 ESP32S3_EYE GPIO、8 MB Flash / OPI PSRAM 配置，不适用于所有 S3 摄像头板。

连接服务器需要私下提供的连接 JSON 或一次性配对码。APK 不内嵌访问密钥。预填的临时测试域名可能随隧道重启变化，长期使用建议固定域名。

验证：

- Android 正式签名构建、单元测试、Lint、APK 签名验证通过。
- ESP32 固件实际编译、合并镜像通过。
- 本机与云端 Python 3.10 下，9 项服务协议测试通过。
- L40S 48 GB 上完成 110 秒公网 HTTPS / WSS 测试：110 / 110 音频块返回，全部使用有效画面；中文语音问图正确回答红色正方形并生成音频。
- 本次单块服务处理平均约 456 ms、P95 约 1.36 秒；不是完整端到端延迟。

已知限制：每轮最长 10 分钟；当前没有可靠语音打断与自动跨会话重连；ColorOS 后台和蓝牙收音待实测；模型可能主动补充描述。110 秒结果不代表数小时稳定性。16 KB 页大小安卓设备兼容性未验证。

附带的 validation 图片、WAV、JSON 均为合成测试数据。校验值见 `SHA256SUMS.txt`；构建方法、接口和部署说明在仓库 docs 目录。

APK 签名证书 SHA-256：`68b99e67cefbb826cb98a8aabf3f3a63b750cd7a0d81dcf7281fd8c71e23cf79`。
