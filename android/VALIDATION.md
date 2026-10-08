# 验证记录

验证日期：2026-10-08。代码已恢复至 1.1.0，versionCode 为 2，仅保留 Ping 和文件传输及必要的连接功能。

本次从清理后的构建目录重新生成 APK，与回退前保存的原始 1.1.0 APK 逐字节一致，所有 ZIP 条目及整个 APK 的 SHA-256 均一致。后续加入的传输计时、CRC 查表、3 秒刷新及相关测试已撤回，发送流程和界面恢复原版。

执行命令：

```powershell
./.android-tools/gradle-8.14.3/bin/gradle.bat -p android clean assembleDebug lintDebug protocolTest assembleDebugAndroidTest
```

| 检查 | 结果 |
| --- | --- |
| `assembleDebug` | 通过，生成可安装 debug APK |
| `lintDebug` | 0 errors，2 warnings（旧 Android 忽略新权限属性、backup 配置建议） |
| `protocolTest` | 通过，944 个检查；4 组桌面版 Ping / 文件上传报文向量 |
| `assembleDebugAndroidTest` | 界面冒烟测试 APK 编译通过 |
| `apksigner verify --verbose --print-certs` | 通过，APK Signature Scheme v2 |
| `aapt dump badging` | 包名 `com.bletools.app`，versionName 1.1.0，versionCode 2，minSdk 26，targetSdk 35 |
| 与原始 1.1.0 APK 比较 | 二进制完全一致，SHA-256 相同 |
| Android 界面运行、真机 BLE 与传输速率 | 本次未执行；构建和协议检查不代表真机速率已复测 |

安装包：`../dist/ble-tool-1.1.0-debug.apk`，41,567 字节。

SHA-256：`a31d377ff270940f1690efd052e73a561c808899cb983f58fca7045ce5ffd9d7`

回退前的代码备份位于 `../.android-tools/restore-1.1.0/before-rollback-1.2.1.zip`；原始安装包副本位于同目录的 `original-1.1.0.apk`。这些本地备份不提交到 Git。

若手机已安装 1.2.x，普通安装器无法直接降级覆盖，需先卸载再安装 1.1.0；卸载会清除应用数据和临时缓存。真机流程见 [README](README.md#验证)。
