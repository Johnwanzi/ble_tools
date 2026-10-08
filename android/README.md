# BLE Tool Android

原生 Java Android 应用，与根目录 Python 桌面版共用同一套报文格式。1.1.0 版仅保留 **Ping 和文件传输（手机上传到设备）**，以及这两项功能所需的扫描、连接、通道选择和状态提示。

## 安装

构建后的安装包位于 `../dist/ble-tool-1.1.0-debug.apk`。将 APK 传到手机，允许文件管理器安装未知来源应用后安装。最低 Android 8.0 (API 26)，手机需支持 BLE。包名和签名沿用原版，versionCode 恢复为 2。若手机已安装 1.2.x，普通安装器无法直接降级覆盖，需先卸载再安装；卸载会清除应用数据和临时缓存。

这是使用本机构建环境 debug 证书签名的测试安装包，可直接安装。正式分发时请改用自己的 release 签名并妥善备份密钥；不同签名的 APK 不能直接覆盖安装。应用不需要联网，无 Internet 权限。

## 使用

1. 在「连接」页点击「开始扫描」，允许所需权限。Android 12 及以上请求附近设备权限；Android 8–11 请求位置权限，且需要开启系统定位服务。
2. 根据名称 / MAC / 最低 RSSI 筛选设备，点击设备连接。扫描 30 秒后自动停止。
3. 连接成功后，在「连接」页可选择写入特征及同服务中的响应特征。候选通道只显示同时支持写入和响应的服务；有多个通道时请确认选择正确。
4. 在「Ping」页输入消息，点击「发送 Ping」，查看设备响应与耗时。
5. 在「文件传输」页选择手机文件，输入设备路径和分块大小（默认 1800，范围 16–2048），点击「开始上传」。支持重复上传，每块等待设备 ACK 后更新进度，显示字节数、速度和平均 RTT。
6. 取消上传时点击「取消并断开」；普通断开可在「连接」页操作。

协议功能针对原工具所使用的设备协议，不适用于任意 BLE 外设。未硬编码设备 UUID。底层按 `min(MTU - 3, 512)` 自动分片发送协议帧，使用逐片写入，不依赖 Windows 的 ATT Long Write。

## 边界与行为

- 使用前台交互模式：离开应用、锁屏或退出会取消操作并释放连接；上传时保持前台和亮屏。系统文件选择器暂时保留连接。
- 「取消并断开」立即释放连接，设备上可能留下部分文件；重新上传从 offset=0 开始，首包 overwrite=true。
- 超时、断线或部分写入失败后不自动重发，避免迟到 ACK 被当成下一条命令的响应。需重新连接后操作。
- 文件先流式复制到应用临时缓存，最大 512 MiB，不申请全盘存储权限。需要相应的可用缓存空间。
- 自动订阅协议响应，操作结束后恢复订阅状态。底层 GATT 操作串行执行。
- `neverForLocation` 避免新版本 Android 申请位置权限，但系统可能过滤部分 BLE beacon；扫描位置相关信标不是本版目标。
- 本版已移除 GATT 调试、设备信息、固件更新与状态、重启、亮度设置、手动配对和日志导出入口及相关命令实现。上传文件不会自动执行固件更新。
- 未实现 USB / WebUSB 功能；根目录的 `webusb_upgrade.html` 仍作为独立 USB 工具使用。

## 构建

需要 JDK 17–24、Android SDK Platform 35、Build Tools 35.0.0。Gradle Wrapper 固定为 8.14.3，Android Gradle Plugin 为 8.9.2；不依赖第三方 Android UI 或 BLE 库。

Windows 在仓库根目录执行：

```powershell
# SDK 已安装时设置为自己的 SDK 目录：
$env:ANDROID_HOME = 'C:\Android\Sdk'
./build-apk.ps1
```

脚本会执行构建、Lint、协议测试，并输出 APK 和 SHA-256。当前机器已准备 `.android-tools/` 本地工具链，脚本也会自动识别它。1.1.0 APK 及 SHA-256 校验文件随源码提交到 Git；本地工具链、构建缓存、`local.properties` 和私钥不提交。

也可用 Android Studio 打开 `android/`，或在此目录执行：

```powershell
./gradlew.bat assembleDebug lintDebug protocolTest
```

## 验证

本次执行结果见 [VALIDATION.md](VALIDATION.md)，其中明确区分已通过检查和未完成的模拟器 / 真机验证。

`protocolTest` 是不依赖 Android 设备的 Java 测试，覆盖：

- 从现有 `ble_tool.py` 生成的 4 组 Ping / 文件上传报文向量逐字节比对（含 UTF-8 路径和消息）。
- 每个可能的通知分割点、合并帧、噪声、CRC 错误及序号回绕。
- MTU 为 23 / 185 / 247 / 515 时对应的 20 / 182 / 244 / 512 字节分片。
- Protobuf 截断 / 溢出 / 未知字段、错误响应、上传 ACK 的 field 6 和进度边界。

模拟器 / 测试手机上的界面冒烟检查（不向 BLE 设备写数据）：

```powershell
./gradlew.bat assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.bletools.app.test/com.bletools.app.SmokeInstrumentation
```

真机验收：覆盖安装 → 授权 → 扫描 → 连接 / 确认通道 → Ping → 小文件上传并检查设备文件 → 取消 / 断线 / 重连 → 重复上传。模拟器和编码测试不能代替真实 BLE 外设验证。

Android 平台参考：[蓝牙权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)、[BluetoothGatt 与 MTU / 写入 API](https://developer.android.com/reference/android/bluetooth/BluetoothGatt)。
