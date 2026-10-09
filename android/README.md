# BLE Tool Android

原生 Java Android 应用，与根目录 Python 桌面版共用同一套报文格式。1.3.0 版同步了桌面端的滑动窗口发送策略及 N 值设置，功能包括 **Ping 和文件传输（手机上传到设备）**，以及所需的扫描、连接、通道选择和状态提示。

## 安装

安装包：[ble-tool-1.3.0-debug.apk](../dist/ble-tool-1.3.0-debug.apk)。将 APK 传到手机，允许文件管理器安装未知来源应用后安装。最低 Android 8.0 (API 26)，手机需支持 BLE。包名和签名沿用原版，versionCode 提高为 5，可覆盖同签名的旧版（包括 1.1.0 和此前的 1.2.x），无需卸载。原 1.1.0 安装包保留在 `dist/`。

这是使用本机构建环境 debug 证书签名的测试安装包，可直接安装。正式分发时请改用自己的 release 签名并妥善备份密钥；不同签名的 APK 不能直接覆盖安装。应用不需要联网，无 Internet 权限。

## 使用

1. 在「连接」页点击「开始扫描」，允许所需权限。Android 12 及以上请求附近设备权限；Android 8–11 请求位置权限，且需要开启系统定位服务。
2. 根据名称 / MAC / 最低 RSSI 筛选设备，点击设备连接。扫描 30 秒后自动停止。
3. 连接成功后，在「连接」页可选择写入特征及同服务中的响应特征。候选通道只显示同时支持写入和响应的服务；有多个通道时请确认选择正确。
4. 在「Ping」页输入消息，点击「发送 Ping」，查看设备响应与耗时。
5. 在「文件传输」页选择手机文件，输入设备路径和分块大小（默认 1800，范围 16–2048），选择「发送窗口 N」（默认 2，范围 1–5），点击「开始上传」。窗口有空位即可按顺序补发下一块，最多 N 块等待 ACK；进度按已确认字节更新。支持重复上传，显示字节数、速度和平均 RTT。操作期间锁定 N 值，结束后恢复可选。
6. 取消上传时点击「取消并断开」；普通断开可在「连接」页操作。

协议功能针对原工具所使用的设备协议，不适用于任意 BLE 外设。未硬编码设备 UUID。底层按 `min(MTU - 3, 512)` 自动分片发送协议帧，使用逐片写入，不依赖 Windows 的 ATT Long Write。

## 边界与行为

- 使用前台交互模式：离开应用、锁屏或退出会取消操作并释放连接；上传时保持前台和亮屏。系统文件选择器暂时保留连接。
- 「取消并断开」立即释放连接，设备上可能留下部分文件；重新上传从 offset=0 开始，首包 overwrite=true。
- 文件块按顺序发送，块间的 BLE 分片不交叉。N=1 时逐块等 ACK，N>1 时 ACK 释放窗口位置后补发；所有块确认后才显示完成。重复上传的每轮都从 offset=0 开始，轮间隔 1 秒。
- `File.processed_byte` 按累计已处理字节数确认完整块，可一次释放多个位置；重复或落后的值忽略。`Success` 或不带该字段的 `File` 按发送顺序确认一块。部分块或超出已发送范围的确认偏移报错；无关消息不释放窗口。
- 每块在 BLE 写入完成后开始计算 3 秒 ACK 期限。保留通知到达时间，避免发送后续块时延迟处理 ACK 导致误判超时。发送后续块或收到无关通知不会重置期限。
- 文件 ACK 超时、设备失败响应、非法偏移、断线或写入失败后不自动重发，并释放连接，避免窗口内剩余请求的迟到 ACK 干扰下一次操作。需重新连接后操作。
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

脚本会执行构建、Lint、协议测试，并输出 APK 和 SHA-256。当前机器已准备 `.android-tools/` 本地工具链，脚本也会自动识别它。`dist/` 中保存可安装 APK 及 SHA-256 校验文件；本地工具链、构建缓存、`local.properties` 和私钥不提交。

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
- Protobuf 截断 / 溢出 / 未知字段、错误响应。
- 实际上传发送器在 N=1–5 下的窗口上限、按 ACK 补发、数据顺序与尾块、FIFO / 累计 ACK、重复与无关消息、ACK 超时、慢写入期间提前到达的 ACK、中止、写入失败和最终确认。

模拟器 / 测试手机上的界面冒烟检查（不向 BLE 设备写数据）：

```powershell
./gradlew.bat assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.bletools.app.test/com.bletools.app.SmokeInstrumentation
```

真机验收：覆盖安装 → 授权 → 扫描 → 连接 / 确认通道 → Ping → 小文件上传并检查设备文件 → 取消 / 断线 / 重连 → 重复上传。模拟器和编码测试不能代替真实 BLE 外设验证。

Android 平台参考：[蓝牙权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)、[BluetoothGatt 与 MTU / 写入 API](https://developer.android.com/reference/android/bluetooth/BluetoothGatt)。
