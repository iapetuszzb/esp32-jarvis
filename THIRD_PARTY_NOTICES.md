# Third-party notices

Project-authored source code is MIT licensed. This does not relicense dependencies or model weights. Release builds contain third-party runtime code and an offline speech model. Upstream licenses and applicable notices continue to apply.

| Component | Upstream / license |
|---|---|
| Arduino ESP32 core 2.0.17 and linked components | [espressif/arduino-esp32](https://github.com/espressif/arduino-esp32/tree/2.0.17), LGPL-2.1-or-later for core; bundled ESP-IDF components retain their own licenses |
| esp32-camera | [espressif/esp32-camera](https://github.com/espressif/esp32-camera), Apache-2.0 |
| ArduinoJson 6.21.5 | [bblanchon/ArduinoJson](https://github.com/bblanchon/ArduinoJson/tree/v6.21.5), MIT |
| AndroidX Core 1.13.1 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/), Apache-2.0 |
| OkHttp 4.12.0 / Okio / Kotlin runtime | [square/okhttp](https://github.com/square/okhttp), [square/okio](https://github.com/square/okio), [JetBrains/kotlin](https://github.com/JetBrains/kotlin), Apache-2.0 |
| Vosk Android 0.3.47 | [alphacep/vosk-api](https://github.com/alphacep/vosk-api), Apache-2.0; native dependencies retain upstream licenses |
| vosk-model-small-en-us-0.15 | [Vosk model catalog](https://alphacephei.com/vosk/models), Apache-2.0; Copyright 2020 Alpha Cephei Inc |
| JNA 5.13.0 | [java-native-access/jna](https://github.com/java-native-access/jna/tree/5.13.0), dual Apache-2.0 / LGPL-2.1-or-later; Apache option used here |
| TLS trust roots | Mozilla public CA certificates distributed through [certifi](https://github.com/certifi/python-certifi), MPL-2.0 |
| MiniCPM-o 4.5 | [OpenBMB/MiniCPM-V](https://github.com/OpenBMB/MiniCPM-V), model weights and remote model code follow their model-card / upstream licenses; weights are not distributed in this repository or APK |

Full commonly applicable license texts are included in `licenses/`. The ESP build scripts and pinned core version allow rebuilding the linked firmware. No vendor board example source, OPPO private protocol or private SDK was copied into this project. Public GPIO definitions were transcribed from the user's board configuration.
