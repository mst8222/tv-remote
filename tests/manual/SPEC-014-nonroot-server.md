# SPEC-014 手工验收清单（盒端无 root 控制）

对应 spec：`spec/implemented/SPEC-014-nonroot-server.md`

环境：三星 Fold3（192.168.3.38，Android 13，未 root）、
H96 Max V56（192.168.3.125，Android 12）、Note20 手机端。

## 准备

1. `python build.py server` 生成 `dist/TVRemoteServer.apk`，分别安装到两台盒子。
2. 在盒子系统设置里开启无障碍服务「TV遥控服务」；或测试时用 adb：
   `settings put secure enabled_accessibility_services com.koco.tvremoteserver/com.koco.tvremoteserver.TvRemoteAccessibilityService`
   `settings put secure accessibility_enabled 1`
3. 手机端配对盒子（输入盒子界面显示的 6 位配对码）。

## 逐条验收（2026-10-21 已执行）

| 编号 | 步骤 | 预期 | 结果 |
|---|---|---|---|
| AC-1 | Fold3 开启无障碍，手机直连依次发送 返回/主页/方向键/OK/音量+/音量-/静音/电源 | 每一步都有实际系统反应；无 `ERR exec` | 通过：方向键移动焦点、返回回播放页、主页回当贝桌面、音量 0→11、静音切换 |
| AC-2 | H96 关闭 root 路径后重复 AC-1 | 同上 | 通过：方向键（焦点遍历）移动、返回/主页回桌面、音量与静音生效 |
| AC-3 | 关闭无障碍服务，发送方向键与文字 | 返回 `ERR a11y`，手机显示「盒子端未开启无障碍服务」及开启指引 | 通过：删除 `enabled_accessibility_services` 后 KEY 19 / TEXT 均返回 `ERR a11y`；手机文案已接入 |
| AC-4 | 保持无障碍关闭，点应用卡启动应用、刷新应用列表 | 仍可用（APP / LIST 不依赖无障碍） | 通过：LIST 返回第三方包名，APP com.android.settings 启动成功 |
| AC-5 | 打开盒子上的搜索框/输入框，手机发送文字 | 文字写入输入框 | 通过：Fold3 系统设置搜索框收到「Kodi 测试」 |
| AC-6 | 查看盒端界面无障碍状态行，点「打开无障碍设置」 | 状态正确；能跳到系统无障碍设置页 | 通过：状态行每 3 秒刷新，点击跳转系统无障碍设置 |
| AC-7 | `powershell -File tests\protocol\run.ps1`，`python build.py server/app` | 全绿、构建通过 | 通过：协议回归 51/51，双端构建通过 |

## 失败记录

- H96 定制固件对所有 `GLOBAL_ACTION_DPAD_*` 返回 false，方向键自动改走
  「焦点遍历 + 点击」路径后通过；边界无焦点时返回成功但无操作。
- 无障碍测试注意：`settings put secure accessibility_enabled 0` 不会解绑服务，
  必须删除 `enabled_accessibility_services` 才会真正断开。
