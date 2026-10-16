# ESP32-S3 + OV5640 烧录

此固件按本项目开发板资料中的 `CAMERA_MODEL_ESP32S3_EYE` 接线、8 MB Flash 分区、OPI PSRAM、15 MHz XCLK 编译。实际板卡已读出 16 MB Flash、8 MB PSRAM，并完成实际烧录；其他 S3 摄像头板不能直接套用 GPIO。

1. 下载并**完整解压** `jarvis-esp32s3-firmware.zip`。
2. 使用数据 USB 线连接开发板。在设备管理器确认新增的 USB 串口；不要选择蓝牙虚拟 COM 口。
3. 安装 Python 3 和烧录工具：`py -m pip install esptool==4.8.1`。
4. PowerShell 进入解压目录，执行 `powershell -ExecutionPolicy Bypass -File .\flash_windows.ps1 -Port COM12`，把 COM12 换成你的 USB 串口。
5. 如无法进入下载模式：按住 BOOT，点按 RESET，再松开 BOOT，重新烧录。完成后点 RESET。
6. 手机连接 `Jarvis-Cam-xxxxxx`，密码 `jarvis-setup`。用 App 写入热点和服务器配置，或访问 `http://192.168.4.1` 配网。
7. 离开摄像头 Wi-Fi，开启 **2.4 GHz、WPA2 兼容模式**的手机热点。v0.2.0 默认手机中转，还需在新版 App 点击“开启摄像头中转”；固件优先 TCP，连接不可用时回退 UDP。接收与上传成功后摄像头状态才会在线。选择 HTTPS 模式时由 ESP32 直接上传。

已经使用本项目 v0.1.0、相同 8 MB 分区布局时，升级推荐 `powershell -ExecutionPolicy Bypass -File .\flash_windows.ps1 -Port COM11 -ApplicationOnly`。它只写 `parts/firmware.bin` 到 `0x10000`，保留 NVS 热点和服务器配置。端口改成实际 USB 串口；不适用于未知旧固件/分区。没有保存过传输方式时，新固件默认手机中转。为兼容联调版本，配网页面和 NVS 的 `udp` 选项实际包含 TCP 优先与 UDP 回退。

如果出现 `No module named esptool`，说明所选 Python 未安装烧录工具，尚未连接开发板。运行 `py -m pip install esptool==4.8.1`，安装成功后再执行脚本，不需要为此重复按 BOOT / RESET。存在多个 Python 时，必须在脚本使用的同一个解释器里安装；也可以执行 `powershell -ExecutionPolicy Bypass -File .\flash_windows.ps1 -Port COM11 -Python 'C:\路径\python.exe'` 明确指定解释器。

首次烧录的合并镜像从 **0x0** 写入，包含 bootloader、分区表、boot_app0、应用。它用于本项目分区布局，不是 OTA 文件。合并镜像覆盖范围包含 NVS，可能清除已有配网信息；升级保留配置用上面的 `-ApplicationOnly`。写入前备份需要保留的旧固件/数据。默认不执行整片擦除。

也可以在 Espressif 官方 Flash Download Tool 中选择 ESP32-S3，将 `jarvis-esp32s3-merged.bin` 放在地址 `0x0`，保留镜像头参数。推荐使用附带脚本：它保留编译器生成的 8 MB / 80 MHz 启动镜像头（启动读取为 DIO，应用配置 QIO），避免修改带校验摘要的镜像头。

串口波特率 115200。启动会输出实际 Flash / PSRAM 容量、摄像头 PID、热点连接和上传状态。按住 BOOT 3 秒重新打开配网，窗口持续 10 分钟。摄像头初始化失败时检查排线方向、供电和 GPIO；OV5640 PID 应为 `0x5640`。
