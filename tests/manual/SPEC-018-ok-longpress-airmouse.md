# SPEC-018 手工验收清单（OK 键长按 + 陀螺仪飞鼠）

对应 spec：`spec/developing/SPEC-018-ok-longpress-airmouse.md`

环境：手机端建议用 Note20（有陀螺仪）或任意带陀螺仪的真机；被控端 H96 Max V56
（直连 192.168.3.125 / 蓝牙已配对）验证长按与飞鼠，Fold3 补验直连长按。
模拟器 emulator-5554 无陀螺仪，用于验证「不支持飞鼠」提示与直连提示。

## 逐条验收

| 编号 | 步骤 | 预期 | 结果 |
|---|---|---|---|
| AC-1 | 蓝牙模式连接 H96，在某应用内短按 OK | 应用触发一次确认，无连续触发 | 待执行 |
| AC-2 | 蓝牙模式长按 OK 约 1 秒后抬起（选支持长按菜单的界面） | 应用触发长按功能 | 待执行 |
| AC-3 | 直连模式连接 H96/Fold3，长按 OK 约 1 秒后抬起 | 盒端执行 `ACTION_LONG_CLICK`，界面出现长按效果；协议返回 OK | 待执行 |
| AC-4 | 蓝牙模式点遥控器卡右上角箭头键（↖）开启飞鼠，转动手机；按 OK 点击目标 | 光标随手机转动移动，OK 可点击；箭头键蓝底白字并显示飞鼠提示 | 通过：H96、Fold3 真机均可用（Fold3 需先删除旧配对让盒子重读含鼠标的描述符） |
| AC-5 | 模拟器（无陀螺仪）蓝牙模式点右上角箭头键 | 提示这台手机没有陀螺仪，按键不进入激活态 | 待执行（模拟器 API 36 自带陀螺仪，需无陀螺仪真机） |
| AC-6 | 直连模式点右上角箭头键 | 提示飞鼠需要蓝牙模式，不开启 | 通过（模拟器：Air mouse needs BT mode） |
| AC-7 | 依次验证：再点箭头键关闭、切直连、切设备、盒子关蓝牙、退出 App | 每种情况后转动手机光标不动；重新开启飞鼠前左键无卡住现象 | 部分通过：再次点击关闭后状态为 Air mouse off、传感器已注销；其余切换场景待真机 |
| AC-8 | `powershell -File tests\protocol\run.ps1` | 0 失败（含 KEYLONG 用例） | 通过：59/59 |
| AC-9 | `python build.py app` / `python build.py server` | 两端构建通过 | 通过 |

## 实测备注

- 2026-10-21 H96 Max V56（真实盒端 v1.5.0）：`KEYLONG <token> 23` -> `OK`、
  `KEYLONG <token> 19` -> `ERR cmd`、错误 token -> `ERR auth`、`KEY <token> 23` -> `OK`；
  当贝桌面焦点节点未响应无障碍长按（截图无变化），AC-3 的界面效果需在
  支持长按的 TV 应用内确认。
- 2026-10-21 H96 飞鼠：盒子首次配对时把 93 字节旧描述符写入
  `/data/misc/bluedroid/bt_config.conf` 的 `HidDescriptor`，重连不重读；root 下改写为
  142 字节新描述符并重启蓝牙后，`dumpsys input` 出现 `Cursor Input Mapper: Mode: pointer`
  与 X/Y 移动范围，飞鼠可用。
- 2026-10-21 Fold3 飞鼠：无 root，前沿用旧配对时盒端日志
  `bta_hh_co_send_hid_info dscp_len = 90`（无鼠标集合），光标不动。在 Fold3 蓝牙设置里
  取消与 Note20 的配对后重连，日志变为 `dscp_len = 142`，`dumpsys input` 的 Note20
  设备合并为带鼠标轴的 Device 26（X/Y `source=0x00002002`、`Mode: pointer`），
  用户实测飞鼠可用。结论：Fold3 这类固件在重连时会重新读取描述符，删配对重连即可；
  H96 这类固件必须改缓存或删配对重配。

## 失败记录

- 待填写。
