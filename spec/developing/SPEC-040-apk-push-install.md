# SPEC-040 APK 推送安装（文件传输 + 链接下载）

## 元信息
- 状态：developing
- 创建：2026-11-01
- 更新：2026-11-01
- 优先级：high

## 变更记录
- 2026-11-01 创建
- 2026-11-01 移入 developing，完成盒端/手机端/网页三端实现与协议测试用例

## 背景与目标

用户希望把手机上的 APK 装到盒子上，目前只能用 U 盘或 adb install，操作繁琐。
本 spec 提供两条路径：
1. 安卓手机直接选本地 APK 文件，推送到盒子并调起安装；
2. 任意系统手机（安卓/iOS/鸿蒙/浏览器）发送一个 APK 下载链接，盒子下载完成后自动调起安装。

安装仍走系统 PackageInstaller，需用户在盒子上确认（无法静默安装，与 OTA 一致）。

## 功能需求（FR）

- FR-40-1：安卓手机端新增「安装 APK」入口，可选择本地 APK 文件（`application/vnd.android.package-archive`）。
- FR-40-2：选中的 APK 通过 HTTP 流式上传到盒子（`POST /api/apk-upload`），不经过 TCP 行式协议。
- FR-40-3：盒子收到上传的 APK 后，校验 ZIP 魔数（`PK\x03\x04`），通过则调起系统安装界面。
- FR-40-4：安卓手机端可输入 APK 下载链接，通过直连命令 `APKURL` 发给盒子。
- FR-40-5：盒子收到 `APKURL` 后立即返回 `OK`，后台异步下载该链接到缓存目录。
- FR-40-6：下载完成后校验 ZIP 魔数，通过则自动调起系统安装界面。
- FR-40-7：网页遥控（iOS/鸿蒙/浏览器）提供「APK 链接」输入，调用 `POST /api/apkurl`。
- FR-40-8：网页遥控提供「选择 APK 文件」上传，调用 `POST /api/apk-upload`。
- FR-40-9：`APKURL` 仅接受 `http`/`https` scheme，其余返回 `ERR arg`，不发起下载。
- FR-40-10：上传的非 APK 文件（ZIP 魔数不符）返回错误，不调起安装。
- FR-40-11：下载与上传均有大小上限（500MB），超限拒绝。

## 非功能需求（NFR）

- NFR-40-1：文件上传必须流式写入磁盘，不得全部读入内存。
- NFR-40-2：盒端 minSdk 22 兼容，不得使用 `java.util.Base64`。
- NFR-40-3：`APKURL` 命令立即返回，下载在独立线程执行，不阻塞 TCP 连接。
- NFR-40-4：协议向后兼容：旧服务端收到 `APKURL` 返回 `ERR cmd`，不影响既有功能。
- NFR-40-5：下载连接超时 60 秒，防止恶意链接挂死线程。

## 验收标准

- AC-40-1：安卓手机选本地 APK 上传后，盒子弹出系统安装确认界面。
- AC-40-2：安卓手机输入有效 http(s) APK 链接，盒子下载完成后弹出安装确认界面。
- AC-40-3：iOS/鸿蒙浏览器填 APK 链接，盒子下载并弹出安装确认。
- AC-40-4：浏览器选择本地 APK 文件上传，盒子弹出安装确认。
- AC-40-5：`APKURL` 传 `ftp://` 链接返回 `ERR arg`，不下载。
- AC-40-6：上传非 APK 文件返回 `notapk` 错误，不安装。
- AC-40-7：`powershell -File tests\protocol\run.ps1` 全部通过。

## 边界与已知限制

- 不支持静默安装，必须用户在盒子系统安装界面确认。
- 首次安装需授予「未知来源」权限，盒子会跳系统设置页。
- 仅校验 ZIP 魔数，不做 APK 签名/包名校验，安全由系统安装器负责。
- 下载链接需盒子可直接访问（公网或同局域网），不支持需要登录的链接。

## 实现映射（implemented 后填写）

待实现。

## 测试映射

- 自动测试：`tests/protocol/ProtocolTest.java`（APKURL 协议语义）、`tests/protocol/WebApiTest.java`（/api/apkurl、/api/apk-upload）
- 手工清单：`tests/manual/SPEC-040-apk-push-install.md`
