# 从源码构建

## Android

需要 Java 17、Android SDK platform 35 / build-tools 35.0.0、Python 3。将 SDK 路径放到 `ANDROID_HOME` 或 `android/local.properties`，不要提交本机路径。

```sh
python tools/fetch_wake_model.py
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Windows 用 `gradlew.bat`。输出 `android/app/build/outputs/apk/debug/app-debug.apk`。下载的 Vosk 模型不进入 Git，脚本校验官方 ZIP 的 SHA256。已经存在模型时脚本跳过下载，干净构建会执行校验。

正式签名构建设置 `JARVIS_KEYSTORE`、`JARVIS_STORE_PASSWORD`，PKCS12 key alias 必须为 `jarvis`。可选 `JARVIS_DEFAULT_SERVER` 只填写公共服务 URL，**不嵌入访问密钥**。

```sh
cd android
./gradlew assembleRelease testReleaseUnitTest lintRelease
```

未设置签名环境变量时 release APK 为未签名，不可直接当作正式下载包。CI 只产出 debug APK；它和 Release 签名不同，不能覆盖升级官方安装包。保护并备份私钥，后续版本升级必须使用同一个签名。

Android 单独发布时，在仓库根目录运行 `python tools/prepare_release.py --android-only --out artifacts/release-v0.1.1`。脚本从构建输出元数据读取实际版本，收集 APK、第三方许可与 SHA256 校验清单；独立输出目录可避免混入旧版固件资产。

## ESP32

```sh
python -m pip install platformio==6.2.0
python -m platformio run -d firmware
python tools/package_firmware.py
```

PlatformIO 固定 espressif32 6.10.0（Arduino ESP32 2.0.17）、ArduinoJson 6.21.5。合并包输出到 `artifacts/release/`。构建依赖首次下载约数百 MB；仓库不包含工具链。

## 服务端测试与外网验收

```sh
python -m pip install -r server/requirements-test.txt
cd server
python -m pytest -q
```

协议测试使用 MockBackend，不下载权重，不占 GPU。实际模型验收必须另行运行：

```sh
python -m pip install requests websocket-client soundfile pillow numpy
python tools/smoke_live.py --config /private/connection.json --audio /private/question-16k-mono.wav --out artifacts/smoke --seconds 110
```

工具每秒上传一张合成图，同时发送按真实时间节奏分块的中文 TTS 音频；先检查文字看图接口，再检查双向 WebSocket、PCM 输出、帧时效和会话释放。它不消费一次性配对码。结果中的 WAV 和 JSON 放在 gitignored artifacts 下。输入声音必须是 16 kHz 单声道，模型回答含正确颜色形状仍需人工或独立评估确认。
