# SPEC-027 启动自动探测更新与「有更新」标签

## 元信息

- 状态：developing
- 创建：2026-10-22
- 更新：2026-10-22 开工实现
- 优先级：medium

## 变更记录

- 2026-10-22 创建：用户要求 OTA 每次启动自动探测，有新版本时在界面上加「有更新」提示标签。
- 2026-10-22 开工：spec 移入 developing，开始实现启动探测与顶栏标签。
- 2026-10-22 实现完成：`MainActivity` 启动后台探测 + 顶栏标签；真机验证（临时把版本名降到 1.5.0
  触发 v1.5.1 的更新）标签出现、点击弹出更新说明；恢复 1.6.0 后标签不再出现；回归 196/196 全绿。

## 背景与目标

现有 OTA（SPEC-017）只有在用户主动点「关于」卡的「检查更新」时才请求 GitHub，
多数用户不会主动点，新版本长期不被发现。目标：App 每次启动（`onCreate`）在后台
静默探测一次最新 Release；发现更高版本时在固定顶栏给出「有更新」标签，
点击标签可直接进入已有的更新说明流程。

## 功能需求（FR）

- FR-27-1 每次 `MainActivity.onCreate` 在后台线程请求一次最新 Release（复用
  `Updater.fetchLatest`），不阻塞界面，不改变启动耗时体验。
- FR-27-2 探测结果仅当 Release 版本高于当前 `versionName` 时，在顶部固定栏显示
  「有更新」标签；已是最新或请求失败时不显示任何提示、不弹 Toast。
- FR-27-3 点击「有更新」标签，直接复用已获取的 Release 打开更新说明 Sheet
  （标题、当前版本、Release 说明、「稍后」/「下载并安装」），不再重复请求接口。
- FR-27-4 手动「检查更新」保留原有提示行为；其结果同步标签状态：
  有新版本时标签可见，已是最新时标签隐藏。
- FR-27-5 「有更新」标签提供中英文文案（中文「有更新」/ 英文「Update」）。

## 非功能需求（NFR）

- NFR-27-1 启动探测失败静默，不打扰遥控主流程；沿用现有 30 秒连接/读取超时。
- NFR-27-2 不新增权限、不引入依赖、不做本地缓存；仍为纯 Java 手工编译。
- NFR-27-3 标签位于不随卡片滚动的顶栏，保证任何滚动位置都能看到。

## 验收标准

- [x] AC-1 冷启动 App 后，后台发起一次 Release 请求（可用抓包或断网对照确认），界面无 Toast、无卡顿。
- [x] AC-2 当前版本低于最新 Release 时，顶栏出现「有更新」标签。
- [x] AC-3 点击「有更新」标签，弹出更新说明 Sheet，内容与手动检查一致。
- [x] AC-4 当前版本不低于最新 Release 时，无标签；手动点「检查更新」提示已是最新，标签保持隐藏。
  （真机确认无标签；手动检查走原 SPEC-017 提示路径）
- [ ] AC-5 中文界面显示「有更新」，英文界面显示「Update」。（文案已加，英文界面待手工确认）
- [x] AC-6 `python build.py app` 构建通过；协议未改动，`tests\protocol\run.ps1` 全绿（196/196）。

## 边界与已知限制

- 只在 Activity 创建时探测；App 长时间前台驻留不会定时轮询。
- 无网络、接口限流、JSON 异常时静默失败，用户仍可通过「关于」卡手动重试。
- 标签只表示「有新版本」，不显示版本号与下载进度；下载/安装仍走原有流程。
- 语言切换会 `recreate()`，会重新探测一次，属可接受的重复请求。

## 实现映射

- `app/src/com/koco/tvremote/MainActivity.java`
  - `autoCheckUpdate`：`onCreate` 末尾提交到 `worker`，复用 `Updater.fetchLatest` 与
    `Updater.isNewer`，只在新版本时回主线程点亮标签；异常静默。
  - `updateBadge`：`buildHeader` 中创建，位于设备信息与「中/EN」之间，默认 `GONE`。
  - `showUpdateBadge` / `hideUpdateBadge`：维护 `pendingUpdate` 与标签可见性；
    手动 `checkUpdate` 成功分支同步调用。
  - 点击标签：用缓存的 `pendingUpdate` 调 `showUpdateSheet`，不重复请求接口。
- `app/res/values/strings.xml`：`update_badge` = 「有更新」。
- `app/res/values-en/strings.xml`：`update_badge` = 「Update」。
- `Updater` 无改动（复用 SPEC-017 的请求与版本比较）。

## 测试映射

- 手工：`tests/manual/SPEC-027-startup-update-badge.md`
- 自动：`tests/protocol/run.ps1`（协议无改动，确认无回归）
