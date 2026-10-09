# 验证记录

验证日期：2026-10-09。版本 1.3.0，versionCode 为 5，保留 Ping、文件传输及必要的连接功能。

本次将桌面端的滑动窗口发送策略同步到 Android：文件块顺序发送，最多 N 块等待 ACK，N 可在界面选择 1–5（默认 2）。收到确认后补发下一块，进度只计已确认字节，所有块确认后完成。使用每块独立的 3 秒 ACK 期限及通知到达时间；支持累计确认、FIFO 确认，忽略重复确认和无关消息。底层沿用 Android 的顺序 GATT 分片，窗口内的块不交叉分片。

执行命令：

```powershell
./build-apk.ps1
./.android-tools/gradle-8.14.3/bin/gradle.bat -p android assembleDebugAndroidTest
./.android-tools/sdk/build-tools/35.0.0/aapt.exe dump badging ./dist/ble-tool-1.3.0-debug.apk
./.android-tools/sdk/build-tools/35.0.0/apksigner.bat verify --print-certs ./dist/ble-tool-1.3.0-debug.apk
```

| 检查 | 结果 |
| --- | --- |
| `assembleDebug` | 通过，生成可安装 debug APK |
| `lintDebug` | 0 errors，2 warnings（旧 Android 忽略新权限属性、backup 配置建议） |
| `protocolTest` | 通过，1613 个检查；包含 4 组桌面报文向量及实际上传发送器的 N=1–5 场景 |
| `assembleDebugAndroidTest` | 界面冒烟测试 APK 编译通过，新增 N 默认值及 1–5 选项检查 |
| `apksigner verify --verbose --print-certs` | 通过，APK Signature Scheme v2 |
| `aapt dump badging` | 包名 `com.bletools.app`，versionName 1.3.0，versionCode 5，minSdk 26，targetSdk 35 |
| APK 签名证书比较 | 与 `dist/ble-tool-1.1.0-debug.apk` 的签名证书 SHA-256 一致 |
| Android 界面运行 | 未完成；本机缺少模拟器硬件加速驱动，尝试软件启动后未出现可用 ADB 设备 |
| 真机安装、BLE 与传输速率 | 未执行；未连接测试手机或 BLE 外设 |

安装包：[ble-tool-1.3.0-debug.apk](../dist/ble-tool-1.3.0-debug.apk)，45,199 字节。

SHA-256：`77073d4c07775c3d402c7c8abc539c78da027ff3beccd9c83b72e21cf59fe2af`

上传测试覆盖：无 ACK 时严格限制窗口、每次 ACK 补发、块偏移及数据内容、首块覆盖与短尾块、累计及无字节数 ACK、重复或无关 ACK、部分块及越界偏移、设备 Failure、缺失最终 ACK、独立超时、后续慢写期间的提前 ACK、迟到 ACK、中止、写入失败、断线、短文件和重复运行。

versionCode 高于旧 1.1.0（2）及本机历史 1.2.1（4），同签名安装包可覆盖升级。旧 1.1.0 APK 保留在 `dist/`。真机流程见 [README](README.md#验证)，编码及模拟传输测试不代表真实 BLE 速率或设备端并行处理能力已验证。
