# SPEC-032 无障碍根节点为空时的 OK 键回退

## 元信息

- 状态：developing
- 创建：2026-10-22
- 更新：2026-10-22（开工）
- 优先级：high

## 变更记录

- 2026-10-22 创建并开工：用户反馈在「云视听小电视」退出确认弹窗（立即退出 / 再逛逛）
  上按 OK，手机端报「盒子执行失败：当前界面没有可操作的焦点或输入框」。
  实测该弹窗 Activity 为 `com.xiaodianshi.tv.yst/.ui.channelReturn.ChannelReturnVisitMultiDialogV2`，
  `uiautomator dump` 返回 `null root node`，确认窗口不向无障碍暴露节点；
  用 `adb input keyevent 23` 可直接触发「立即退出」，说明系统按键事件通道可用。
  另在 Android TV 通知面板复现同样现象：`KEY <token> 23` 返回 `ERR exec`。

- 2026-10-22 实现完成：`pressFocused()` 在根节点为 null 时改注入
  `GLOBAL_ACTION_DPAD_CENTER`，版本升至 1.6.8（versionCode 12），盒端已装机。
  验证：有节点界面（当贝桌面、Kodi）短按/长按 OK 正常；协议回归 235 项全绿。
  复测通知面板与云视听小电视退出弹窗仍报 `ERR exec`：该 RK3566 固件对
  `GLOBAL_ACTION_DPAD_CENTER` 返回 false，回退路径在此设备不生效（见边界），
  需在支持该全局动作的固件上手工验收 AC-1。

## 背景与目标

盒端 OK 键走 `pressFocused()`：先取 `getRootInActiveWindow()`，再按焦点节点点击 / 合成手势 /
系统 OK 动作逐级兜底。部分电视应用与系统窗口不提供无障碍节点树（自定义渲染或安全窗口），
`getRootInActiveWindow()` 返回 null，当前实现直接返回 `RES_FAIL`，协议层回 `ERR exec`，
手机端弹出与真实原因不符的「没有可操作的焦点或输入框」。

目标：这类窗口下改用系统 OK 全局动作（真实 KeyEvent 注入），不再误报失败；
有节点窗口的既有行为保持不变。

## 功能需求（FR）

- FR-32-1 活动窗口根节点为 null 时，OK 短按改为 `performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)`，
  按其返回值回 `OK` / `ERR exec`。
- FR-32-2 活动窗口根节点为 null 时，长按 OK 同样发送系统 OK 动作（长按退化为短按），不得报错。
- FR-32-3 根节点存在时，OK 的现有顺序不变：焦点节点动作 → 焦点中心合成手势 → 系统 OK 动作。
- FR-32-4 方向键、返回、主页、音量、应用启动、文字输入的行为不变（方向键本就走全局动作优先）。

## 非功能需求（NFR）

- NFR-32-1 不引入延时、重试轮询或额外权限；单次命令响应时间不变。
- NFR-32-2 不依赖 root，不改协议报文格式。

## 验收标准

- [ ] AC-1 无节点窗口（云视听小电视退出弹窗、Android TV 通知面板）按 OK：手机端不再显示执行失败提示，盒子执行系统 OK 动作。
  （2026-10-22 实测：本机 RK3566 固件 `GLOBAL_ACTION_DPAD_CENTER` 返回 false，
  通知面板与退出弹窗仍为 `ERR exec`；待支持该全局动作的固件手工验收）
- [x] AC-2 有节点界面（当贝桌面图标、系统设置列表）短按 OK 仍能正常打开。
  （2026-10-22 已验：当贝桌面按 OK 打开 Kodi）
- [x] AC-3 长按 OK 在有节点界面行为不变；在无节点窗口不报错。
  （2026-10-22 已验：`KEYLONG 23` 返回 `OK`）
- [x] AC-4 `tests/protocol/run.ps1` 回归全绿（235 项，2026-10-22）。

## 边界与已知限制

- 无节点窗口无法判定长按语义，统一按短按处理。
- 部分定制固件（实测 RK3566 荐片TV）对 `GLOBAL_ACTION_DPAD_CENTER` 返回 false，
  无节点窗口下仍回 `ERR exec`，属于平台限制；此类设备可用触控板「触摸」模式
  （`MTAPN`，实测可操作退出弹窗）或蓝牙 HID 替代。
- 无节点窗口下的焦点位置不可知，无法做节点级命中或合成手势。

## 实现映射（implemented 后填写）

- `server/src/com/koco/tvremoteserver/TvRemoteAccessibilityService.java`：
  `pressFocused()` 在 `getRootInActiveWindow()` 为 null 时改走 `GLOBAL_ACTION_DPAD_CENTER`。

## 测试映射

- 自动：`tests/protocol/run.ps1`（协议语义回归，无节点场景由真机验证）。
- 手工：`tests/manual/SPEC-032-a11y-null-root-key-fallback.md`。
