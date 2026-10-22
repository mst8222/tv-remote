# SPEC-019 应用快捷方式显示真实图标 — 手工验收清单

对应 spec：`spec/developing/SPEC-019-app-icons.md`
执行环境：手机端 TVRemote 直连 H96 Max V56（192.168.3.125），盒子端 TVRemoteServer。

| 编号 | 操作 | 预期 | 结果 |
| --- | --- | --- | --- |
| AC-1 | 手机端直连 H96，进入遥控器页等待应用卡加载 | 各应用格显示盒子端真实图标，与盒子桌面图标一致；加载过程中先显示首字占位 | 通过：模拟器 AVD 内盒端 + 手机端实测（Settings / 英雄无敌 5.5 Frax / 英雄无敌合集 / TV Remote / TV Remote Service）；H96 真机实测 设置 / 当贝播放器 / 当贝市场 / 当贝桌面 / 电视文件管理器 / 飞牛TV / 乐播投屏 / 央视频TV / 银河奇异果 / 云视听极光 / 云视听小电视 / Kodi / Netflix / OK影视 / TV遥控服务 均显示盒端真实图标 |
| AC-2 | PC 端发送 `ICON <token> com.not.exist` | 返回 `ERR arg` | 通过（模拟器盒端实测） |
| AC-3 | PC 端发送 `ICON deadbeef com.android.settings` | 返回 `ERR auth` | 通过（自动用例 icon_wrong_token，模拟器实测同语义） |
| AC-4 | 停掉盒子端服务后重进遥控器页 | 应用卡按缓存渲染，图标为首字占位，点击应用仍按原逻辑提示 | 待执行 |
| AC-5 | 切换到另一台盒子 | 应用卡图标全部为该盒子的，不出现上一台盒子的图标 | 待执行（代码按「IP|包名」缓存，需真实双盒子确认） |
| AC-6 | 运行 `powershell -File tests\protocol\run.ps1` | 全部通过（含新增 `icon_*` 用例） | 通过：64/64 |

## 实测备注

- 2026-10-22 模拟器 AVD（Medium_Phone_API_36.1）：盒端 `ICON` 返回 144x144 PNG，
  Settings 图标 14376 字节、TVRemote 图标 19145 字节；未知包返回 `ERR arg`；
  手机端应用卡实际显示真实图标，图标为逐格异步替换（先只有「设置」首字占位）。
- 模拟器首次运行被系统「Try out your stylus」全屏引导挡住，导致点击/按键注入异常，
  关闭该引导后 UI 自动化恢复正常。
- 2026-10-22 H96 真机（192.168.3.125）升级到 v1.5.1（versionCode 7）后，手机端直连
  进遥控器页，应用快捷全部 15 项真实图标正常渲染；升级后无障碍绑定保持有效。
