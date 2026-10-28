# SPEC-038 手工验收清单：盒端双版本构建

状态：构建与 API 22 实机项已执行；OTA 资产选择项需发布后验证

| 编号 | 步骤 | 预期 | 结果 |
|---|---|---|---|
| 1 | `python build.py server` | 成功产出 `dist/TVRemoteServer.apk` 与 `dist/TVRemoteServer-android5.apk` | 通过（2026-10-24） |
| 2 | `aapt2 dump badging dist\TVRemoteServer.apk` | `minSdkVersion:'26'` | 通过（2026-10-24） |
| 3 | `aapt2 dump badging dist\TVRemoteServer-android5.apk` | `minSdkVersion:'22'` | 通过（2026-10-24） |
| 4 | `apksigner verify --print-certs` 两个 APK | 均通过，证书与版本号一致（versionCode 15 / 1.7.1） | 通过（2026-10-24） |
| 5 | `python build.py app` | 产出 `dist/TVRemote.apk`，`minSdkVersion:'26'` | 通过（2026-10-24） |
| 6 | `adb install -r dist\TVRemoteServer-android5.apk` 到 192.168.3.39 | 安装成功 | 通过（2026-10-24） |
| 7 | 在 192.168.3.39 打开服务，PC 直连 | `PING` 返回 `PONG`，`PAIR` 返回 token，`LIST` 返回应用列表 | 通过（2026-10-24） |
| 8 | 发布 Release 后，在 API 22 盒子点「检查更新」 | 下载并安装 `TVRemoteServer-android5.apk` | 待执行（发布后） |
| 9 | 发布 Release 后，在 Android 8+ 盒子点「检查更新」 | 下载并安装 `TVRemoteServer.apk` | 待执行（发布后） |

备注：两个 APK 同包名同 versionCode，可在同一设备上互相覆盖安装（`adb install -r`）。
API 22 实机验证时盒端界面显示「当前版本 v1.7.1」，配对码 567316，`PAIR` 成功返回 token。
