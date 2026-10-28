# SPEC-038 盒端双版本构建（API 26 / API 22）

## 元信息
- 状态：developing
- 创建：2026-10-24
- 更新：2026-10-24（创建并进入开发）
- 优先级：high

## 变更记录
- 2026-10-24 创建：SPEC-035 把盒端最低版本统一降为 API 22，现要求每次编译同时产出
  正常版与低安卓版本支持版，Release 区分，OTA 按系统版本选资产。
- 2026-10-24 移入 developing：开始实现 build.py 双产物、清单 minSdk 覆盖与 Updater 资产选择。
- 2026-10-24 实现完成：`build.py` 按变体生成临时清单并校验 minSdk；盒端 Updater 按
  `Build.VERSION.SDK_INT < 26` 选择资产；双端版本升到 15 / 1.7.1；README 中英文同步。
  验证：两个 APK badging 分别为 26 / 22、签名校验通过；`android5` 版已装到 192.168.3.39
  并验证 PING/PAIR/LIST；协议回归 235 全绿。AC-5（发布后 OTA 资产下载）待发布验证。

## 背景与目标
盒端需要同时覆盖 Android 8.0+ 的正常设备与 Android 5.1（API 22）的老电视/盒子。
单一 minSdk 22 的产物虽然能装两端，但无法在 Release 中一眼区分适用系统，也不符合
「正常版 + 低版本支持版」的发布习惯。目标：`python build.py server` 一次产出两个 APK，
Release 同时上传，盒端应用内更新按系统版本选择对应资产。

## 功能需求（FR）
- FR-038-1 `python build.py server` 一次构建产出：
  - `dist/TVRemoteServer.apk`：正常版，minSdk 26；
  - `dist/TVRemoteServer-android5.apk`：低版本版，minSdk 22。
- FR-038-2 两个产物的包名、versionCode、versionName 与签名相同；仅 minSdk 与文件名不同。
- FR-038-3 `python build.py app` 行为不变，仅产出 `dist/TVRemote.apk`（minSdk 26）。
- FR-038-4 构建脚本校验产物实际 minSdk 与期望一致，不一致时构建失败。
- FR-038-5 盒端应用内更新（OTA）在 API 22~25 上查找并下载 `TVRemoteServer-android5.apk`，
  在 API 26+ 上查找 `TVRemoteServer.apk`。
- FR-038-6 README 中英文说明两个盒端 APK 的适用系统、构建产物与 Release 资产命名。

## 非功能需求（NFR）
- NFR-038-1 不改变协议、Web API 与盒端运行逻辑；本次只改构建与 OTA 资产选择。
- NFR-038-2 两个变体可重复构建，中间产物不得互相污染。
- NFR-038-3 不新增第三方依赖（ZXing 属于 SPEC-036 的手机端依赖）。

## 验收标准
- AC-1 `python build.py server` 成功产出两个 APK。
- AC-2 `aapt2 dump badging` 显示正常版 `minSdkVersion:'26'`、低版本版 `minSdkVersion:'22'`。
- AC-3 `apksigner verify` 两个产物均通过，versionCode/versionName 一致。
- AC-4 `dist/TVRemoteServer-android5.apk` 可安装到 API 22 设备（192.168.3.39）并正常提供服务。
- AC-5 API 22 设备上 OTA 查找的资产名为 `TVRemoteServer-android5.apk`（发布后可验），
  API 26+ 为 `TVRemoteServer.apk`。
- AC-6 `python build.py app` 正常，`dist/TVRemote.apk` 的 `minSdkVersion:'26'`。
- AC-7 `powershell -File tests\protocol\run.ps1` 0 失败。

## 边界与已知限制
- 两个 APK 同包名，同一设备只能安装其中一个；升级时须用同系列资产。
- API 22 低版本版仍受 SPEC-035 的限制：无合成手势（触控板绝对点按不可用）、
  部分固件不显示前台通知。
- 不在本次自动发布三仓库 Release；资产命名与选择规则在 README 中说明。

## 测试映射
- 手工：`tests/manual/SPEC-038-server-dual-apk.md`（构建与 API 22 实机项）
- 自动：`tests/protocol/run.ps1`（协议回归）
