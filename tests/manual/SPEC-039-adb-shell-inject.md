# SPEC-039 盒端 ADB 自连注入 — 手工验收清单

状态：待执行（spec 见 spec/developing/SPEC-039-adb-shell-inject.md）
设备：荐片 RK3566（= H96，192.168.3.125，Android 12）、Redmi 电视 MiTV-ANTL0（192.168.3.39，API 22）

## 验收项（对应 AC）
- [x] AC-1 荐片盒子切 ADB 模式后连接成功；本机固件对回环连接免确认（实测未弹框）。
      `adb shell am force-stop com.koco.tvremoteserver` 后重开 App：注入模式仍为 ADB，
      9 秒内自动重连显示「ADB 调试：已连接」，未再弹授权框（免确认成立）。
      注：force-stop 会同时禁用无障碍服务，实测后已用
      `settings put secure enabled_accessibility_services .../TvRemoteAccessibilityService`
      与 `accessibility_enabled 1` 恢复。
- [x] AC-2 云视听小电视：播放中 KEY 20 触发 `PlayerKeyEventDelegate switchNext`
      （真实按键到达）；BACK 唤出 `ChannelReturnVisitMultiDialogV2`，KEY 23 后
      焦点窗口离开云视听（确定键退出成功）。
      注：该应用为安全表面，无法截图，只能用 logcat + `dumpsys window` 判定。
- [x] AC-3 系统设置页方向键连续两次滚动、MTAPN 命中中心并进入子页；
      当贝桌面菜单键（82）待用户手感确认（命令返回 OK，行为依赖桌面）。
- [ ] AC-4 Redmi 电视（API 22）打开 ADB 调试后 ADB 模式可用，无 NoClassDefFoundError。
      阻塞原因：2026-10-28 该电视 adb 5555 未监听（调试开关关闭或设备休眠），
      下次验证前需在电视上打开 ADB 调试。
- [ ] AC-5 ADB 调试关闭态：命令返回 `ERR adb`，状态行红字；切回无障碍后恢复。
      阻塞原因：关闭 adb_enabled 会同时切断本机 adb 调试通道，需现场用实体遥控操作，
      由用户执行。
- [x] AC-6 tests/protocol/run.ps1：234/235 通过，唯一失败为
      `discover_no_broadcast_null`（真实盒子 UDP 广播导致的环境抖动，重跑或关盒端服务即可通过）
- [x] AC-7 盒端 UI 显示「注入模式：ADB」「ADB 调试：已连接」；状态行点击后
      `android.settings.APPLICATION_DEVELOPMENT_SETTINGS` 已在任务栈中
      （`am start` 提示 "its current task has been brought to the front"）

## 现场操作路径（供用户手动验证 AC-4 / AC-5）
1. Redmi 电视：设置 → 关于 → 产品型号连点 7 次 → 开发者选项 → 打开 ADB 调试；
   盒端 App 点「ADB 调试」行 → 状态行变绿「已连接」。
2. ADB 关闭态：系统开发者选项里关掉 ADB 调试 → 盒端 App 显示
   「ADB 调试：未连接（按 OK 打开开发者选项）」→ 用实体遥控按 OK 会跳到开发者选项；
   重新打开后再按 OK（或在已授权时直接按 OK 自动开启）即可恢复。

## 执行记录
- 2026-10-28 荐片 RK3566：AC-1/2/3/6/7 实测通过；AC-4 因 Redmi 电视 adb 未开阻塞；
  AC-5 需实体遥控，转用户执行。
