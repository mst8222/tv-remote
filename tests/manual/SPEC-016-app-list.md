# SPEC-016 手工验收清单（应用快捷动态列表）

对应 spec：`spec/implemented/SPEC-016-app-list-dynamic.md`

环境：手机端模拟器 Medium_Phone_API_36.1（1080x2400，en-US）+ H96 Max V56 直连
（192.168.3.125:5555，配对码 119297，14 个第三方应用）；Note20 当时处于锁屏状态，
界面验证在模拟器完成（同一 APK）。

## 逐条验收

| 编号 | 步骤 | 预期 | 结果 |
|---|---|---|---|
| AC-1 | 直连盒子后执行 `LIST`（可用调试日志或 PC 端 LanClient） | 每项 `包名\|base64`，解码为应用名 | 通过：H96 返回 14 项，`com.dangbei.tvlauncher -> 当贝桌面` 等中文解码正确 |
| AC-2 | 进入应用卡，对比盒子 `pm list packages -3` 数量 | 应用卡条目数一致，可滚动 | 通过：应用卡显示 15 项（设置 + 14 个第三方），上滑后可见到底部「TV遥控服务」 |
| AC-3 | 点击一个原候选表外的应用 | 盒子启动该应用，手机显示已发送 | 通过：点「飞牛TV」，H96 `topResumedActivity=com.trim.tv/.ui.main.MainActivity` |
| AC-4 | 查看应用卡第一项并点击 | 首项为「设置」，点击打开系统设置 | 通过：首项「Settings」；点击后 H96 `topResumedActivity=com.android.settings/.homepage.SettingsHomepageActivity` |
| AC-5 | 断开盒子后重进界面；再清除 App 数据且不连接 | 先显示缓存列表；无缓存时只剩「设置」 | 通过：停掉盒端服务后重启 App，应用卡仍显示缓存条目；`pm clear` 后重启只剩「Settings」 |
| AC-6 | `powershell -File tests\protocol\run.ps1` | 0 失败 | 通过：52/52 |
| AC-7 | `python build.py app` / `server` | 构建通过 | 通过：两端构建成功 |

## 失败记录

- 无。
- 环境注意：首次 `list` 只返回 1 项（盒子自身）是 Android 11+ 包可见性导致，
  盒端 manifest 增加 `<queries>`（LAUNCHER / LEANBACK_LAUNCHER）后恢复 14 项；
  协议回归首跑 `discover_no_broadcast_null` 被真实 H96 广播干扰，重跑通过。
