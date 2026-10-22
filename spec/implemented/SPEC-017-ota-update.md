# SPEC-017 基于 GitHub Release 的 OTA 更新

## 元信息

- 状态：implemented
- 创建：2026-10-21
- 更新：2026-10-21 验收通过
- 优先级：high

## 变更记录

- 2026-10-21 创建：两端应用目前只能手动下载 APK 覆盖安装，用户要求依据 GitHub
  公开仓库 `mst8222/tv-remote` 的 Release 做应用内 OTA 升级。
- 2026-10-21 开工：spec 移入 developing，开始实现 Updater、安装接收器与两端入口。
- 2026-10-21 实现完成：两端代码、文案、manifest 与 README 完成，构建产出 v1.4.0，
  发布 GitHub Release v1.4.0。
- 2026-10-21 验收修复（发布 v1.4.1）：
  1. 安装确认界面不弹出：安装状态 PendingIntent 使用 `FLAG_IMMUTABLE`，系统无法回填
     `EXTRA_STATUS`/`EXTRA_INTENT`，改为 `FLAG_MUTABLE`。
  2. 下载长时间卡住：`browser_download_url` 所在域名在部分网络不返回响应头，
     改为 GitHub API 资产端点 `/releases/assets/<id>`（`Accept: application/octet-stream`），
     超时由 10 秒放宽到 30 秒。
  3. 盒子端 OK 键不触发：H96 固件对可聚焦 TextView 不派发默认点击，
     更新按钮显式处理 OK/ENTER。
- 2026-10-21 验收通过：手机端（模拟器）与盒子端（三星 Fold3）完成
  v1.4.0 → v1.4.1 真实自升级；转 implemented。

## 背景与目标

项目已发布 v1.3.0 到 GitHub 公开仓库，Release 中同时附带 `TVRemote.apk` 与
`TVRemoteServer.apk`。当前升级方式是用户自行下载 APK 再安装，盒子端尤其麻烦
（需要 U 盘或 adb）。目标：两端 App 内置「检查更新」，直接读取公开仓库最新
Release，下载对应 APK 并交给系统安装器完成升级。

## 功能需求（FR）

- FR-17-1 两端各提供「检查更新」入口，读取 `https://api.github.com/repos/mst8222/tv-remote/releases/latest`，
  不读取其他仓库、不做仓库地址配置。
- FR-17-2 版本判断：取 Release 的 `tag_name`（去掉前缀 `v`）与应用当前
  `versionName` 逐段按数字比较，仅当 Release 更高时提示更新；相同或更低提示已是最新。
- FR-17-3 手机端在界面底部新增「关于」卡：显示当前版本，并提供「检查更新」按钮。
  发现新版本时用现有自绘 Sheet 展示版本号、当前版本与 Release 说明，
  主操作为「下载并安装」，次操作为「稍后」。
- FR-17-4 盒子端新增「版本更新」卡：显示当前版本、检查更新按钮与状态文字；
  按钮可聚焦，物理遥控焦点导航后按 OK/ENTER 与触屏点击都能触发。
- FR-17-5 按应用选择 Release 资产：手机端下载 `TVRemote.apk`，盒子端下载
  `TVRemoteServer.apk`；通过 API 资产端点 `/releases/assets/<id>` 下载到应用私有
  缓存目录 `cacheDir/update/`，跟随 HTTP 重定向。
- FR-17-6 下载完成后校验收到的字节数与 Release asset 的 `size` 一致（size 缺失时跳过校验），
  不一致视为下载失败并删除残缺文件。
- FR-17-7 安装使用 `PackageInstaller` 会话（`MODE_FULL_INSTALL`）。普通应用无静默安装权限，
  必须由系统安装确认界面完成；收到 `STATUS_PENDING_USER_ACTION` 时启动该确认界面。
- FR-17-8 未授予「安装未知应用」权限时，提示用户并跳转
  `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`（包名限定本应用），不执行安装。
- FR-17-9 盒子端安装自身更新后进程会被系统终止；`BootReceiver` 额外监听
  `android.intent.action.MY_PACKAGE_REPLACED`，更新完成后自动重启 `ServerService`。
- FR-17-10 检查与下载失败（无网络、HTTP 非 200、JSON 异常、资产缺失）时，
  两端都以可读文案提示，不改变现有直连/蓝牙遥控行为。

## 非功能需求（NFR）

- 检查与下载在后台线程执行，不阻塞界面；连接与读取超时各 30 秒。
- 不引入第三方依赖，两端仍为纯 Java 手工编译；不新增运行时库。
- 每次点击实时请求 GitHub API，不做本地缓存；匿名接口限流错误直接提示用户稍后重试。
- 只使用公开仓库的公开 Release，协议与既有配对/token 机制不变，不影响协议回归。
- 两端 APK 体积增量控制在 ~10 KB 以内（新增两个类与少量文案）。

## 验收标准

- [x] AC-1 `python build.py app` 与 `python build.py server` 均构建通过，版本号为 1.4.1 / versionCode 5。 【自动】
- [x] AC-2 手机端旧版本点「检查更新」，识别 GitHub 最新 Release 并弹窗展示版本号与更新说明。
      （实测：模拟器 v1.4.0 → 识别 v1.4.1，自绘 Sheet 展示版本号与 Release 说明） 【手工】
- [x] AC-3 手机端在更新弹窗点「下载并安装」，下载完成后出现系统安装确认，
      确认后升级成功。（实测：模拟器 v1.4.0 → v1.4.1，安装后 `versionCode=5` / `versionName=1.4.1`） 【手工】
- [x] AC-4 盒子端点「检查更新」→「下载并安装」，下载完成后出现系统安装界面，
      确认后升级成功。（实测：三星 Fold3 v1.4.0 → v1.4.1；
      H96 因系统层吞掉注入的点击/OK，仅验证到卡片显示与焦点导航，详见边界与已知限制） 【手工】
- [x] AC-5 当前已是最新版本时，两端提示「已是最新版本」，不出现下载入口。
      （实测：Fold3 升级后再次检查显示「已是最新版本 v1.4.1」） 【手工】
- [x] AC-6 网络不可用时检查更新，两端给出失败提示且原有遥控功能不受影响。
      （实测：代理指向失效地址后检查失败，手机端弹出失败提示，盒子端状态文字显示
      「检查更新失败：网络请求失败，请检查网络后重试」） 【手工】
- [x] AC-7 盒子端完成自身更新后，`ServerService` 自动恢复运行，手机可继续直连。
      （实测：`dumpsys activity services` 显示服务以 `reason=PACKAGE_REPLACED` 重启） 【手工】
- [x] AC-8 `powershell -File tests\protocol\run.ps1` 回归全绿（52/52）。 【自动】

## 边界与已知限制

- GitHub API 在部分网络环境不可达或被限流（匿名 60 次/小时），此时更新不可用，
  不影响遥控；不做镜像源与代理配置。
- 浏览器下载域名（`github.com/.../releases/download/...`）在部分网络不返回响应头，
  因此统一走 API 资产端点；若网络完全无法访问 GitHub，仍需自备代理。
- 安装过程必须经过系统确认界面，无法静默安装；盒子端安装界面需要物理遥控导航。
- 只升级与 Release 资产同名的 APK；Release 缺少对应资产时提示失败。
- 不处理降级安装；系统会拒绝 versionCode 更低的 APK。
- 盒子端更新期间服务会短暂中断，安装完成后由 `MY_PACKAGE_REPLACED` 恢复。
- 更新说明来自 Release body，过长时手机端截断展示，不做富文本渲染。
- 实测 H96（Android 12）的系统层会把 ADB 注入的 DPAD_CENTER/ENTER 与触摸吞掉
  （系统设置界面同样无响应），因此无法在 H96 上自动化验证更新按钮；
  这属于设备环境限制，盒子端链路已在三星 Fold3（Android 13）上完整验证。
- v1.4.0 的 OTA 构建带安装回调缺陷（IMMUTABLE），需手动安装一次 v1.4.1 才能使用应用内更新。

## 实现映射

- 手机端
  - `app/src/com/koco/tvremote/Updater.java`：读取最新 Release、版本比较、API 资产下载、
    字节数校验、PackageInstaller 安装。
  - `app/src/com/koco/tvremote/UpdateInstallReceiver.java`：接收安装状态，
    `STATUS_PENDING_USER_ACTION` 时拉起系统确认界面。
  - `app/src/com/koco/tvremote/MainActivity.java`：`buildAbout()` / `checkUpdate()` /
    `showUpdateSheet()` / `downloadUpdate()`。
  - `app/AndroidManifest.xml`：`REQUEST_INSTALL_PACKAGES`、`UpdateInstallReceiver`。
  - `app/res/values{,-en}/strings.xml`：关于与更新文案。
- 盒子端
  - `server/src/com/koco/tvremoteserver/Updater.java`（手机端 copy 后改写包名与资产名）。
  - `server/src/com/koco/tvremoteserver/UpdateInstallReceiver.java`。
  - `server/src/com/koco/tvremoteserver/MainActivity.java`：`roundButton()`、
    `onUpdateAction()` / `checkUpdate()` / `downloadUpdate()` / `setUpdateEnabled()`、
    更新按钮 OK/ENTER 处理。
  - `server/src/com/koco/tvremoteserver/BootReceiver.java`：响应 `MY_PACKAGE_REPLACED`。
  - `server/AndroidManifest.xml`：`REQUEST_INSTALL_PACKAGES`、`UpdateInstallReceiver`、
    `MY_PACKAGE_REPLACED`。
  - `server/res/values{,-en}/strings.xml`：版本更新文案。
- 构建与发布：`build.py` 版本号 1.4.1 / 5；README 与 README.en.md 增加检查更新说明与版本记录。

## 测试映射

- 手工：tests/manual/SPEC-017-ota-update.md（已执行）
- 自动：AC-1（构建）、AC-8（协议回归 52/52）
