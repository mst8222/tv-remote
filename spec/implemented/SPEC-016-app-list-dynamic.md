# SPEC-016 应用快捷动态列表

## 元信息

- 状态：implemented
- 创建：2026-10-21
- 更新：2026-10-21（实现完成，7 条验收标准全部通过，转入 implemented）
- 优先级：medium

## 变更记录

- 2026-10-21 创建：用户确认应用快捷版块应动态显示盒子全部第三方应用，
  而不是只显示内置候选表中的 6 项；ADB 移除后直连 LIST 已能取到全部包名，
  缺的是应用名与界面动态渲染。
- 2026-10-21 实现：LIST 载荷改为「包名|base64(应用名)」；手机端应用卡改为
  动态渲染全量条目并缓存在 `apps_<host>`；盒子端补 `<queries>` 解决 Android 11+
  包可见性导致 LIST 只返回自身的问题。协议回归 52/52，H96 实测 LIST 14 项。
- 2026-10-21 验收：AC-1~7 全部通过（手机端界面在模拟器 Medium_Phone_API_36.1
  上验证，直连 H96），转入 implemented；README / README.en / 手工清单同步更新。

## 背景与目标

SPEC-012 应用卡采用「内置候选表 ∩ 盒子已装包名」的固定展示方式，
SPEC-013 移除 ADB 后只保留直连 `LIST` 拉取包名。结果：盒子上候选表以外的
应用既看不到也点不到，用户明确要求改为动态显示全部第三方应用并可启动。

目标：直连 `LIST` 同时返回包名与应用名，手机端按实际安装情况动态生成应用卡，
点击任意条目用 `APP` 命令启动。

## 功能需求（FR）

- FR-16-1 `LIST` 返回 `OK <项>,<项>...`，每项为 `包名|base64(应用名)`；
  仅包含第三方应用（`ApplicationInfo.FLAG_SYSTEM` 排除），与旧语义保持一致。
- FR-16-2 应用名取盒子端 `PackageManager.getApplicationLabel`，UTF-8 编码后
  Base64（NO_WRAP），避免逗号、空格、中文在单行协议里转义。
- FR-16-3 手机端解析全部条目并动态渲染应用卡，不再与内置候选表求交集。
- FR-16-4 系统「设置」固定作为首项常显（`com.android.settings`），
  不依赖 `LIST`（该固件 Settings 无 LAUNCHER 入口，启动走 `ACTION_SETTINGS`）。
- FR-16-5 第三方应用按应用名排序（中文按拼音，`Collator`）。
- FR-16-6 拉取成功后把原始载荷写入 `apps_<host>`，下次进入界面先用缓存渲染，
  再异步刷新；拉取失败保留缓存；无缓存时应用卡只含「设置」。
- FR-16-7 点击条目仍走 `APP <token> <包名>`；蓝牙模式下应用卡不可用，
  点击提示需切到直连（与现状一致）。
- FR-16-8 应用图标不做跨设备传输，沿用「名称首字圆形图标」。
- FR-16-9 Android 11+ 包可见性：盒子端 manifest 用 `<queries>` 声明
  `MAIN/LAUNCHER` 与 `MAIN/LEANBACK_LAUNCHER` 两类入口，不申请
  `QUERY_ALL_PACKAGES`；因此 `LIST` 只返回有启动入口的应用。

## 非功能需求（NFR）

- 单条 `LIST` 响应在常见盒子（<200 个第三方应用）下不超过 ~20 KB，
  6000 ms 超时内可完成。
- 解析容错：兼容旧缓存中「只有包名、无 `|`」的条目，此时应用名即包名，
  首次刷新后自动被新载荷替换（不做迁移代码）。
- 不引入第三方依赖，两端仍纯 Java 手编。

## 验收标准

- [x] AC-1 真机直连执行 `LIST`，返回项形如 `包名|base64`，解码后为盒子上的应用名。 【手工】
  结果：H96 返回 14 项，样例 `com.dangbei.tvlauncher -> 当贝桌面`、
  `com.xiaodianshi.tv.yst -> 云视听小电视`，中文解码正确。
- [x] AC-2 盒子上第三方应用多于 6 个时，应用卡显示全部条目，可滚动查看。 【手工】
  结果：模拟器手机端 + H96 直连，应用卡 15 项（设置 + 14 个第三方），
  上滑可看到末项「TV遥控服务」。
- [x] AC-3 点击候选表以外的应用（如当贝市场外的任意第三方应用）能成功启动。 【手工】
  结果：协议层 `APP ... com.newtv.cboxtv` 返回 OK，H96 前台切到
  `com.newtv.cboxtv/tv.newtv.cboxtv.EntryActivity`；手机端点击「飞牛TV」
  后 H96 前台切到 `com.trim.tv/.ui.main.MainActivity`。
- [x] AC-4 「设置」始终位于应用卡第一项且可启动。 【手工】
  结果：首项确为「设置」，点击后 H96 前台切到
  `com.android.settings/.homepage.SettingsHomepageActivity`。
- [x] AC-5 断开盒子后重进界面，应用卡使用上次缓存渲染；清缓存且未连接时仅显示「设置」。 【手工】
  结果：停掉盒端服务并重启 App，应用卡仍显示缓存条目；`pm clear` 后重启
  （盒子不可达）应用卡只剩「设置」。
- [x] AC-6 `powershell -File tests\protocol\run.ps1` 全绿（LIST 新格式用例）。 【自动】
  结果：52/52 通过（新增 `list_label_decode`）。
- [x] AC-7 `python build.py app` / `python build.py server` 构建通过。 【自动】
  结果：两端构建通过（server 需带 `<queries>` 重新打包后生效）。

## 边界与已知限制

- 应用名取盒子系统语言（不是手机语言），盒子英文系统会显示英文名。
- 没有 LAUNCHER 入口的应用点击后返回 `ERR exec`，手机端显示「当前界面没有可操作焦点或输入框」之外的通用启动失败文案。
- 应用卡条目过多时依赖外层 ScrollView 滚动，不做分页/搜索。

## 实现映射

- FR-16-1/2：server/src/com/koco/tvremoteserver/ServerService.java `listPackages()`
  （`case "LIST"` 返回）；AndroidManifest.xml `<queries>`
  （MAIN/LAUNCHER + MAIN/LEANBACK_LAUNCHER，FR-16-9）。
- FR-16-3/4/5/6/8：app/src/com/koco/tvremote/MainActivity.java
  `parseApps()` 解析与排序、`rebuildApps()` 动态生成应用卡（首项「设置」）、
  `ensureApps()` 拉取并写缓存 `apps_<host>`、`onCreate` 先用缓存渲染。
- FR-16-7：MainActivity `doApp()` 走 `LanClient.app()`（`APP <token> <包名>`）。
- 协议参照与回归：tests/protocol/MockBoxServer.java `case "LIST"`、
  tests/protocol/ProtocolTest.java `tc_list_ok()`（含 `list_label_decode`）。

## 测试映射

- 自动：tests/protocol/ProtocolTest.java（LIST 载荷格式与解码）
- 手工：tests/manual/SPEC-016-app-list.md
