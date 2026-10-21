# SPEC-014 盒端无 root 控制（无障碍服务注入）

## 元信息
- 状态：implemented
- 创建：2026-10-21
- 更新：2026-10-21
- 优先级：high
- 验收：2026-10-21（H96 Max V56 / Android 12 与三星 Fold3 / Android 13 真机）

## 变更记录
- 2026-10-21 创建：用户明确要求盒端方案必须在未 root 的电视盒子上可用；
  大部分盒子默认未 root，依赖 su 的 `input keyevent` 方案没有实用价值。
- 2026-10-21 实现：新增 `TvRemoteAccessibilityService`，`ServerService` 删除全部 su 调用；
  `MockBoxServer` 增加 `a11yEnabled` 与 `ERR a11y`，协议回归 51 用例全绿。
- 2026-10-21 实测补充：H96（Android 12）的定制固件对
  `GLOBAL_ACTION_DPAD_*` 一律返回 false，方向键改用「焦点遍历 + 点击」路径
  （`focusSearch` / `ACTION_FOCUS` / `ACTION_CLICK`），H96 与 Fold3 均验证通过。
  边界无目标时返回成功但无操作，避免正常导航边界被当成错误。
- 2026-10-21 验收：AC-1～AC-7 全部通过，转 implemented。

## 背景与目标

盒端原有实现通过 `/system/xbin/su root sh -c "input keyevent ..."` 注入按键，
只适用于已 root 的盒子。三星 Fold3 等官方固件没有可执行的 su，
直连返回 `ERR exec`，按键完全无效。

目标：盒端全部控制能力改为**不依赖 root**的公开 API：
- 系统级动作（返回/主页/最近任务/锁屏/方向键）→ `AccessibilityService.performGlobalAction`；
- 音量/静音 → `AudioManager.adjustStreamVolume`；
- 文字输入 → 无障碍节点 `ACTION_SET_TEXT`；
- 应用启动与列表 → `PackageManager` + `startActivity`。

无障碍服务需要用户在盒子系统设置里一次性开启，之后常驻并随开机自启。
这是 Android 提供给普通应用的唯一系统级按键注入入口，不需要 root、
不需要开发者选项、不需要 adb。

## 功能需求（FR）

- FR-14-1 删除 `ServerService` 中所有 su 调用（`input keyevent` / `input text` /
  `pm list packages` / `monkey` / `am start`），盒端源码不再出现 `/system/xbin/su`。
- FR-14-2 新增 `TvRemoteAccessibilityService`，在 `AndroidManifest.xml` 中以
  `BIND_ACCESSIBILITY_SERVICE` 权限声明，并由 `res/xml/accessibility_service.xml`
  提供配置；服务连接后在进程内以静态实例供 `ServerService` 调用。
- FR-14-3 `KEY <token> <code>` 按键映射（非 root）：
  - 4 → `GLOBAL_ACTION_BACK`
  - 3 → `GLOBAL_ACTION_HOME`
  - 82 → `GLOBAL_ACTION_RECENTS`（Android 无全局「菜单」动作，见已知限制）
  - 26 → `GLOBAL_ACTION_LOCK_SCREEN`
  - 19/20/21/22/23 → `GLOBAL_ACTION_DPAD_UP/DOWN/LEFT/RIGHT/CENTER`；
    全局动作返回 false 时改走焦点遍历（`focusSearch` 移动焦点、OK 点击焦点节点）
  - 24/25/164 → `AudioManager` 音量加/减/静音
  - 其余 keycode → `ERR cmd`（不再假装成功）
- FR-14-4 无障碍服务未连接时，需要无障碍的按键与文字命令返回 `ERR a11y`；
  音量/静音、APP、LIST 不依赖无障碍，仍正常返回。
- FR-14-5 `TEXT <token> <base64>`：取当前输入焦点节点（`FOCUS_INPUT`），
  无焦点时在活动窗口内查找可编辑节点，执行 `ACTION_SET_TEXT`；
  无可用输入框返回 `ERR exec`，无障碍未开启返回 `ERR a11y`。
- FR-14-6 `APP <token> <包名>`：`PackageManager.getLaunchIntentForPackage` 取
  LAUNCHER 意图并 `startActivity`；`com.android.settings` 特判为
  `android.settings.SETTINGS`；包不存在或无入口返回 `ERR exec`。
- FR-14-7 `LIST <token>`：`PackageManager.getInstalledApplications` 过滤系统应用，
  返回第三方包名（逗号分隔），语义与旧 `pm list packages -3` 一致。
- FR-14-8 盒端界面显示无障碍服务状态（已开启/未开启），未开启时给出
  「设置 → 无障碍 → TV遥控服务」的开启指引，并提供跳转系统无障碍设置的入口。
- FR-14-9 协议参照实现 `MockBoxServer` 同步新增 `a11yEnabled` 开关与
  `ERR a11y` 语义，`tests/protocol/run.ps1` 全绿。
- FR-14-10 手机端把 `ERR a11y` 翻译为「盒子端未开启无障碍服务 + 怎么开启」的
  中英文文案，不显示原始协议错误。

## 非功能需求（NFR）

- NFR-14-1 不申请 `INJECT_EVENTS`、`WRITE_SECURE_SETTINGS` 等系统权限；
  不引入第三方依赖，APK 体积不因本需求明显增加。
- NFR-14-2 按键从收到命令到系统响应的延迟 < 300ms。
- NFR-14-3 无障碍服务与 TCP 服务同进程；进程被系统回收后，TCP 服务按
  START_STICKY 恢复，无障碍服务由系统重新绑定，两者不互相阻塞。
- NFR-14-4 无障碍未开启时不得崩溃，不得出现假成功（返回错误码）。

## 验收标准

- [x] AC-1 Fold3（Android 13，未 root）开启无障碍后，直连发送返回/主页/方向键/
  OK/音量/静音/电源均有实际系统反应。 【手工】
  结果：方向键移动焦点、BACK 回到播放页、HOME 回当贝桌面、音量 0→11、静音切换。
- [x] AC-2 H96 Max V56（Android 12，本次不使用 root）同上。 【手工】
  结果：返回/主页/方向键/OK/音量/静音/锁屏均生效；方向键走焦点遍历路径。
- [x] AC-3 关闭无障碍后发送 KEY/TEXT，返回 `ERR a11y`，手机端显示
  「盒子端未开启无障碍服务」及开启指引，不显示英文协议码。 【手工 + 自动】
  结果：协议层自动用例 `tc_a11y_off` 通过；真机删 `enabled_accessibility_services`
  后 KEY 19 / TEXT 均返回 `ERR a11y`。
- [x] AC-4 无障碍关闭时 APP / LIST 仍可用。 【自动 + 手工】
  结果：关无障碍后 LIST 返回第三方包名、APP 启动 Settings 成功。
- [x] AC-5 在可编辑输入框内发送文字，文字被写入输入框。 【手工】
  结果：Fold3 系统设置搜索框收到「Kodi 测试」。
- [x] AC-6 盒端界面显示无障碍状态，点击入口能打开系统无障碍设置页。 【手工】
  结果：盒端首页状态行 3 秒刷新；点击跳转 `ACTION_ACCESSIBILITY_SETTINGS`。
- [x] AC-7 `python build.py server` / `python build.py app` 构建通过，
  `powershell -File tests\protocol\run.ps1` 全绿。 【自动】
  结果：两端构建通过，协议回归 51/51 通过。

## 边界与已知限制

- Android 12（API 31）起才有 `GLOBAL_ACTION_DPAD_*`；更低版本方向键会返回
  `ERR exec`。当前实测的 H96（Android 12）与 Fold3（Android 13）均满足。
- 部分定制固件（实测 H96 Max V56）声明了平台版本却对所有 `GLOBAL_ACTION_DPAD_*`
  返回 false，此时方向键走焦点遍历：只能移动/点击既有焦点节点，
  不支持遥控器式的焦点环形滚动（个别列表滚到底后需要目标界面自身处理）。
- Android 没有非 root 的「菜单键」全局动作，82 映射为「最近任务」；
  需要原生菜单键的应用只能改用系统遥控器或盒子蓝牙遥控器。
- 电源键映射为 `GLOBAL_ACTION_LOCK_SCREEN`（锁屏/息屏），不等同于关机；
  部分电视固件锁屏后需按盒子物理遥控器唤醒。
- 无障碍服务必须由用户在盒子系统设置中手动开启一次；部分定制固件
  可能隐藏或限制无障碍入口，此时该盒子仍只能用蓝牙 HID 或原厂遥控器。
- 文字输入依赖目标输入框暴露无障碍节点；自绘输入控件（如部分视频 App
  的搜索框）可能不支持 `ACTION_SET_TEXT`。
- 无障碍服务能读取窗口内容（`canRetrieveWindowContent`），只在本机处理，
  不上传任何数据。

## 实现映射

- 盒端无障碍服务：`server/src/com/koco/tvremoteserver/TvRemoteAccessibilityService.java`
  - 结果码与单例：`RES_OK/RES_FAIL/RES_NO_A11Y/RES_UNSUPPORTED`、`isReady()`（36-45 行）
  - 按键映射：`key()`（68 行起），方向键 `dpad()`、焦点遍历 `moveFocus()` / `clickFocused()`（101-158 行）
  - 文字输入：`text()` / `findEditable()`（160-186 行）
- 盒端协议：`server/src/com/koco/tvremoteserver/ServerService.java`
  - `KEY` 解析与错误码、`TEXT`、`APP`（`launch`）、`LIST`（`listPackages`）
- 盒端声明与配置：`server/AndroidManifest.xml`（`BIND_ACCESSIBILITY_SERVICE`）、
  `server/res/xml/accessibility_service.xml`
- 盒端界面：`server/src/com/koco/tvremoteserver/MainActivity.java`（无障碍状态行 + 跳转设置）
- 协议参照实现：`tests/protocol/MockBoxServer.java`（`a11yEnabled`、`ERR a11y`）
- 手机端文案：`app/src/com/koco/tvremote/Errors.java`（`ERR a11y`）+ 双端 `strings.xml`
- 文档：`README.md`「无障碍服务」说明

## 测试映射

- 自动：`tests/protocol/run.ps1`（新增 `ERR a11y` 用例）
- 手工：`tests/manual/SPEC-014-nonroot-server.md`
