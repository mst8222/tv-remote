# SPEC-032 手工验收清单：无障碍根节点为空时的 OK 键回退

环境：荐片TV RK3566（192.168.3.125），盒端 TV遥控服务 1.6.8，手机端直连。

## 前置

- 盒子端无障碍服务已开启（盒端界面显示「无障碍服务：已开启」）。
- 手机端与盒子完成配对，直连可用。
- 复现无节点窗口的两条路径：
  - 云视听小电视（`com.xiaodianshi.tv.yst`）播放中按返回，弹出「立即退出 / 再逛逛」。
  - `adb shell cmd statusbar expand-notifications` 打开通知面板。

## 验收项

- [ ] AC-1 无节点窗口按 OK：手机端不出现「盒子执行失败：当前界面没有可操作的焦点或输入框」，
      盒子执行系统 OK（通知面板关闭 / 弹窗按钮被触发）。
      2026-10-22：本机 RK3566 固件 `GLOBAL_ACTION_DPAD_CENTER` 返回 false，
      通知面板与云视听小电视退出弹窗仍为 `ERR exec`；待支持该全局动作的固件再验。
- [x] AC-2 当贝桌面选中应用图标按 OK 能打开应用；系统设置列表按 OK 能进入对应条目。
      2026-10-22：当贝桌面聚焦应用卡后按 OK 打开了 Kodi（`org.xbmc.kodi19DV`）。
- [x] AC-3 当贝桌面长按 OK 行为与改动前一致；无节点窗口长按 OK 不报错。
      2026-10-22：当贝桌面 `KEYLONG 23` 返回 `OK`，无失败提示。
- [x] AC-4 `powershell -File tests\protocol\run.ps1` 全绿（235 项，2026-10-22）。

## 验证记录

- 2026-10-22 复现：通知面板处于活动窗口时 `KEY <token> 23` 返回 `ERR exec`（对应手机端失败提示）；
  `uiautomator dump` 报 `null root node`；`adb input keyevent 23` 可正常触发云视听小电视退出弹窗按钮。
- 2026-10-22 实现后复测：`KEY` / `/api/key` 在通知面板与退出弹窗仍回 `ERR exec`，
  设备固件对 `GLOBAL_ACTION_DPAD_CENTER` 返回 false；有节点界面（当贝桌面 Kodi）短按与长按正常。
  替代路径：触控板「触摸」模式 `MTAPN` 可操作退出弹窗。
