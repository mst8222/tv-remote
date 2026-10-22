# SPEC-021 直连飞鼠协议（Web 端空气鼠标）— 手工验收清单

对应 spec：`spec/developing/SPEC-021-airmouse-protocol.md`
执行环境：盒子端 TVRemoteServer 装于 H96 Max V56（192.168.3.125），
Web 页面按 SPEC-020 实现；浏览器覆盖 Android Chrome、iPhone Safari。
当前状态：协议与盒端光标已实现并验证；网页飞鼠 UI 已由 SPEC-025 删除，
AC-4 ~ AC-8 不再适用，保留协议与光标相关项。

| 编号 | 操作 | 预期 | 结果 |
| --- | --- | --- | --- |
| AC-1 | 可行性门：盒端临时用 `dispatchGesture` 在 H96 桌面、Kodi、当贝播放器上点按与拖动 | 至少两类界面产生真实交互；否则 spec 转 discarded 并记录结论 | P（H96 桌面：无障碍开启后光标可见，拖动可移动并改变桌面焦点；SPEC-023/025 触控板复用） |
| AC-2 | PC 发送 `MOVE <token> 1`、`MOVE <token> 1 1 1`、`MOVE <token> 21 0`、`MOVE 错误token 1 1`，并在关闭无障碍后重发 | 依次 `ERR arg`、`ERR arg`、`ERR arg`、`ERR auth`、`ERR a11y`；正常参数 `OK` | P（`move_*` 系列用例通过） |
| AC-3 | PC 发送 `MCLICK <token> 2`、`MCLICK <token> 0`（未按下时） | 依次 `ERR arg`、`OK` | P（`mclick_*` 系列用例通过） |
| AC-4 | HTTPS 页面开启飞鼠，转动手机并点击 | 光标跟随移动，点按可打开桌面应用 | 已废止（网页飞鼠由 SPEC-025 删除） |
| AC-5 | 飞鼠模式下按住 OK 并移动，在响应触摸的列表应用中操作 | 产生拖动/滚动，松开后结束 | 已废止（同上） |
| AC-6 | 退出飞鼠、关闭页面、断开盒子网络、停止服务 | 光标 5 秒内消失；盒子端无卡住的按压 | 部分通过（页面隐藏/切设备会发 `MCLICK 0`；光标 5 秒自动隐藏沿用，SPEC-025 待真机复核） |
| AC-7 | iPhone Safari（HTTPS、证书已信任）开启飞鼠，分别在允许与拒绝陀螺仪权限下操作 | 允许后可移动；拒绝时提示不支持且不进入飞鼠 | 已废止（同上） |
| AC-8 | HTTP 页面点击飞鼠开关 | 提示「飞鼠需要 HTTPS 地址」，不发送 `MOVE`/`MCLICK` | 已废止（同上） |
| AC-9 | 运行 `powershell -File tests\protocol\run.ps1`，执行两端构建 | 全部通过；`python build.py app` 与 `python build.py server` 成功 | P（协议 125 + Web 55 + QR 16 = 196；两端构建成功） |

## 实测备注

（实现并执行后填写：可行性门结果、光标手感、响应触摸的应用清单、异常现象）
