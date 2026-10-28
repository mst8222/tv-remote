# SPEC-035 盒端 App 支持 Android 5.1（API 22）

## 元信息
- 状态：implemented
- 创建：2026-10-24
- 更新：2026-10-24（实现并验收通过，移入 implemented）
- 优先级：high

## 变更记录
- 2026-10-24 创建：目标设备 MiTV-ANTL0（Redmi 电视，Android 5.1.1，SDK 22）实测安装原版会报
  `INSTALL_FAILED_OLDER_SDK`，故规划把盒端 minSdk 由 26 降到 22 并做 API 兼容适配。
- 2026-10-24 移入 developing：已写好手工验收清单，开始改代码。
- 2026-10-24 实现：构建按模块设 minSdk；WebServer 去掉 `java.util.Base64`（API 26）
  改用内置标准实现（桌面 JVM 与 API 22 都能跑）；手势入口加 API 24 判定；
  Updater 的 `canRequestPackageInstalls` 加 API 26 判定。
- 2026-10-24 验收：在 192.168.3.39 实机安装成功、PING/PAIR/KEY/网页 API 全部通过，
  协议回归 0 失败，移入 implemented。

## 背景与目标
现场有一台 Redmi 电视（MiTV-ANTL0，Android 5.1.1 / SDK 22，固件 22.8.17.1977），
需要用手机 App 直控。盒端 APK 当前 `minSdkVersion=26`，装不上（`INSTALL_FAILED_OLDER_SDK`）。
目标是让盒端在 API 22 上可安装、可常驻、可被手机直连控制，同时不影响手机端（仍为 API 26+）
与现有一切协议行为。

## 功能需求（FR）
- FR-035-1 构建按模块区分最低版本：`build.py` 中对 `server` 模块使用 minSdk 22，
  对 `app` 模块保持 26；产物 `dist/TVRemoteServer.apk` 的 `sdkVersion` 为 22。
- FR-035-2 `server` 模块的 APK 可安装到 API 22 设备（现场设备 192.168.3.39）。
- FR-035-3 安装后启动「TV遥控服务」，服务前台常驻，监听 TCP 8899；`PING` 返回 `PONG`。
- FR-035-4 服务每 3 秒在 UDP 8898 广播 `TVREMOTE|8899|<型号>`，手机端可自动发现。
- FR-035-5 内置网页遥控 `http://<盒子IP>:17880` 可打开；`TEXT` 命令的 Base64 编解码在 API 22 上正常工作。
- FR-035-6 依赖 `GestureDescription`（API 24）的合成手势路径在 API 22 上不崩溃，
  且明确返回 `ERR exec`；节点动作（点击、长按、滚动、方向键）仍照常可用。
- FR-035-7 应用内更新在 API 26 以下跳过 `canRequestPackageInstalls` 检查，直接发起安装会话。
- FR-035-8 通知在 API 26 以下使用旧式 `Notification.Builder(Context)`，API 26+ 保持渠道不变。
- FR-035-9 在盒子上开启「TV遥控服务」无障碍后，`KEY` / `KEYLONG` / `TEXT` 可正常注入。

## 非功能需求（NFR）
- NFR-035-1 直连协议与网页 API 的命令、返回码与 README 完全一致，不做协议改动。
- NFR-035-2 手机端模块（`app`）代码与 minSdk 不变。
- NFR-035-3 不引入任何新的第三方依赖。
- NFR-035-4 API 22 运行期不得出现类缺失导致的崩溃（`NoClassDefFoundError` 等）。

## 验收标准
- AC-1 `python build.py server` 构建成功，`aapt2 dump badging` 显示 `sdkVersion:'22'`。
- AC-2 `adb install -r dist/TVRemoteServer.apk` 在 192.168.3.39 成功（不再 `INSTALL_FAILED_OLDER_SDK`）。
- AC-3 打开盒子端界面可见配对码与本机 IP；退回桌面后服务进程仍存活（该固件不展示前台通知）。
- AC-4 `PING` 返回 `PONG`；`PAIR <码>` 返回 `OK <token>`。
- AC-5 开启无障碍后 `KEY <token> 19/20/21/22/23` 有实际界面反应。
- AC-6 浏览器打开 `http://192.168.3.39:17880` 正常渲染，键入文字点发送返回协议结果
  （无输入框时允许 `ERR exec`，不得 500 或崩溃）。
- AC-7 在 API 22 上执行 `MTAPN` 等纯手势命令返回 `ERR exec`，App 不崩溃、服务不退出。
- AC-8 `powershell -File tests\protocol\run.ps1` 全绿（0 失败）。

## 边界与已知限制
- API 22 无 `GestureDescription`：触控板「触摸」模式的合成点击/滑动、绝对坐标点按在
  无可命中节点时不可用，返回 `ERR exec`；节点滚动与节点点击仍可用。
- API 26 以下无通知渠道与前台服务类型语义，通知走旧式构造；实测小米 5.1 固件直接拒绝
  前台服务通知（日志 `not allow service ... become forceground`），通知栏不显示，但服务进程
  回桌面后仍存活（实测 PING PONG），不影响遥控。
- 不覆盖 API 21 以下设备（`PackageInstaller` 自 API 21 起才有）。
- 本次只改 `server` 模块，手机端最低版本不变。
- `WebServer` 需要同时被桌面 JVM（`tests/protocol`）编译，不能依赖任何一端独有的 Base64，
  故自带标准实现；后续若 WebServer 需要其它平台 API，同样按此约束处理。

## 实现映射
- `build.py`：新增 `MIN_SDK = {"app": 26, "server": 22}`，aapt2 link 的 `--min-sdk-version`
  与 d8 的 `--min-api` 均按模块取值。
- `server/AndroidManifest.xml`：`minSdkVersion` 26 → 22（targetSdk 仍 30）。
- `server/src/com/koco/tvremoteserver/WebServer.java`：移除 `java.util.Base64`，
  新增 `b64Encode` / `b64Decode`（标准 Base64、无换行），用于 TEXT 编码、应用名解析、ICON 解码。
- `server/src/com/koco/tvremoteserver/TvRemoteAccessibilityService.java`：
  新增 `canGesture()`（SDK_INT >= 24），`dispatchTap/Drag/LongPress/ScrollSwipe` 入口判定；
  `absoluteTap/absoluteLong/absoluteSwipe/clickCursor/tapCursor/longTapCursor/scrollCursor`
  在退回手势前判定，不支持时返回 `RES_FAIL`（协议 `ERR exec`）。
- `server/src/com/koco/tvremoteserver/Updater.java`：`install()` 的
  `canRequestPackageInstalls` 检查加 `SDK_INT >= 26` 前置条件。

## 验收结果
- AC-1 通过：`minSdkVersion:'22'`，产物 338 KB。
- AC-2 通过：实机 `Success`。
- AC-3 通过（通知受限）：界面显示配对码 567316 与 192.168.3.39:8899；该固件不展示前台通知，
  回桌面 10 秒后进程存活且 `PING` 返回 `PONG`。
- AC-4 通过：`PING -> PONG`，`PAIR -> OK <token>`。
- AC-5 通过：`KEY 19/20/21/22/23` 与 `KEYLONG 23` 全部 `OK`。
- AC-6 通过：首页 HTTP 200；`POST /api/text` 返回 `ERR exec`（当前界面无输入框）；
  `/api/apps` 200、`/api/key` `{"ok":true}`。
- AC-7 通过：`MTAPN` 返回 `ERR exec`，进程未崩溃，`PING` 仍为 `PONG`，logcat 无
  `FATAL EXCEPTION` / `NoClassDefFoundError`。
- AC-8 通过：`tests\protocol\run.ps1` 三个测试类 0 失败。
- 明细见 `tests/manual/SPEC-035-server-api22.md`。

## 测试映射
- 手工：`tests/manual/SPEC-035-server-api22.md`（已执行）
- 自动：`tests/protocol/run.ps1`（回归，确认协议未变）
