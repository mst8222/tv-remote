# SPEC-029 触控板双模式（触摸 / 鼠标）

## 元信息

- 状态：developing
- 创建：2026-10-22
- 更新：2026-10-22（开工）
- 优先级：high

## 变更记录

- 2026-10-22 创建并开工：SPEC-028 的绝对触摸在部分盒子固件上不可用
  （不执行合成手势、界面无点击节点），需要保留 SPEC-023/025 的
  「触控板相对移动光标」作为兜底。触控板改为两段模式：触摸（绝对点按）与
  鼠标（相对移动光标），手机端与网页端同步；蓝牙模式两者都不可用。
- 2026-10-22 实现完成：手机端 TouchPadView 双状态机 + TouchSession 恢复相对命令、
  三段输入模式与 `touchpad`→`touch` 迁移；盒端恢复 5 个鼠标路由；网页端三段切换与
  鼠标手势；自动回归 235 项全绿（协议 156 + Web 63 + 二维码 16），双端构建通过，
  H96 已装 1.6.0 盒端并确认网页三段与 `/api/move` 路由生效。AC-4~8 待手工。

## 背景与目标

SPEC-028 把触控板改成「点哪打哪」，依赖盒端无障碍节点点击或合成手势。
实测部分盒子（H96 Max V56 定制固件）不执行 `dispatchGesture`，且当贝桌面等
TV 界面大量区域没有可点击节点，绝对触摸会失效。SPEC-023 的
「拖动移动光标 + 点按单击」只依赖节点点击与光标覆盖层，在这类设备上更可靠。

目标：触控板同时保留两种操作方式，用户按盒子能力自行切换：

- 触摸：盒子屏幕的等比映射面，点哪打哪，适合支持合成手势或节点完整的设备；
- 鼠标：手指拖动产生相对位移，盒端显示光标，点按为单击，适合绝对触摸无效的设备。

命名：分段控件「方向盘 | 触摸 | 鼠标」；原「触控板」名称不再作为分段文案，
仅作为视图的无障碍描述。

## 功能需求（FR）

- FR-29-1 分段控件：手机端与网页端的方向盘卡片分段统一为三段
  「方向盘 / 触摸 / 鼠标」，与现有深色分段样式一致；选中态高亮。
- FR-29-2 模式记忆：手机端 `SharedPreferences` 键 `input` 取值
  `dpad` / `touch` / `mouse`；网页端 `localStorage` 键 `tvremoteweb_input`
  取值相同；旧值 `touchpad` 读取时按 `touch` 处理并在下次保存时改写。
- FR-29-3 触摸模式：完全沿用 SPEC-028（绝对点按、长按、单指滑动、
  第二指取消、无光标、进入/退出发送 `MHIDE`）。
- FR-29-4 鼠标模式手势（沿用 SPEC-023 直连语义）：
  - 单指移动超过 8dp 后按 1.5 倍增益换算相对位移，20ms 合并发送 `MOVE`；
  - 点按（未移动、未长按）发送 `MTAP`；
  - 按住 500ms 未移动发送 `MLONG`；长按待定期间移动则先 `MCLICK 1` 转拖动，
    抬起时 `MCLICK 0`；
  - 双指上下滑动按 24dp 一格发送 `SCROLL`，单次 ±5 格、最小间隔 100ms；
  - 第二指落下时若已按下左键先发送 `MCLICK 0`，不残留按下状态。
- FR-29-5 鼠标模式光标：进入鼠标模式立即发送 `MOVE <token> 0 0` 让盒端显示光标；
  退出鼠标模式（切到方向盘/触摸、切设备、切模式、页面隐藏、退出 App）
  发送 `MHIDE` 立即隐藏光标。
- FR-29-6 模式切换：方向盘 → 触摸不显示光标；方向盘/触摸 → 鼠标先 `MHIDE`
  再 `MOVE 0 0`，保证从干净状态开始；鼠标 → 触摸先结束会话再 `MHIDE`。
- FR-29-7 蓝牙边界：蓝牙模式下触摸与鼠标都不可用（HID 鼠标不支持绝对坐标，
  本次也不恢复 HID 鼠标触控板）。点这两个分段提示「触控板需使用直连」且不切换；
  已处于触摸/鼠标时切到蓝牙自动回到方向盘并提示；两段按钮在蓝牙模式下置灰。
- FR-29-8 盒端协议与网页路由：
  - 盒端沿用既有 `MOVE` / `MCLICK` / `MTAP` / `MLONG` / `SCROLL` / `MHIDE`
    与 SPEC-028 的 `MTAPN` / `MLONGN` / `MSWIPEN`，全部保留；
  - 网页恢复 `POST /api/move`（`{dx,dy}`）、`POST /api/click`（`{down}`）、
    `POST /api/tap`、`POST /api/long`、`POST /api/scroll`（`{steps}`），
    与 `/api/tapn`、`/api/longn`、`/api/swipen`、`/api/hide` 并存；
  - `dx`/`dy` 单轴 -127..127，`steps` -5..5，缺失或非整数返回 `ERR arg`；
    认证顺序与错误码与既有 Web API 一致。
- FR-29-9 提示文案：按模式区分
  - 触摸：「点按对应位置 · 长按菜单」「上下滑动列表」；
  - 鼠标：「拖动移动光标 · 点按单击」「按住长按 · 双指滚动」；
  - 中英文资源同步，网页 i18n 同步。

## 非功能需求（NFR）

- NFR-29-1 方向盘、系统键行、音量行、飞鼠、应用列表等既有功能不变；
  协议回归与 Web API 回归全部通过。
- NFR-29-2 两种模式共用一条 `TouchSession` 连接；鼠标模式移动按 20ms 合并，
  单轴超过 127 的余量分批发送不丢位移。
- NFR-29-3 任何手势结束路径都不得残留长按定时器、按下状态或进行中的手势；
  异常断连不刷屏（沿用 2 秒节流）。
- NFR-29-4 最低 API 26；不新增权限；不修改 HID 键盘/消费者控制/飞鼠报告。
- NFR-29-5 模式切换只影响触控板本身，不改变已配对信息与连接方式记忆。

## 验收标准（AC）

- AC-1 自动：Web API `POST /api/move`（正常/缺 dy/越界由盒端夹取）、
  `/api/click`（down=true/false/缺参）、`/api/tap`、`/api/long`、
  `/api/scroll`（正常/缺参）全部 200 且盒端记录正确；`401`（错误 token）、
  `405`（GET）保持。
- AC-2 自动：`powershell -File tests\protocol\run.ps1` 全绿。
- AC-3 构建：`python build.py app` 与 `python build.py server` 通过。
- AC-4 手工（直连）：手机端切「鼠标」，盒端出现光标；拖动可移动光标，
  点按可单击桌面图标，长按可触发菜单，双指滑动可滚动列表。
- AC-5 手工（直连）：从「鼠标」切回「触摸」，光标立即消失，点按仍为绝对点按。
- AC-6 手工（蓝牙）：蓝牙模式下「触摸」「鼠标」两段置灰，点击提示
  「触控板需使用直连」且不切换；当前处于触摸/鼠标时切蓝牙自动回方向盘。
- AC-7 手工（网页）：网页三段切换正常，鼠标模式下拖动/单击/长按/双指滚动生效，
  触摸模式行为不变；刷新页面后记住模式；切设备后光标状态正确。
- AC-8 手工：切设备、切模式、页面隐藏、退出 App 后无卡住的光标或按下状态。

## 边界与已知限制

- 蓝牙模式两种触控板模式都不可用，这是 HID 鼠标能力与本次范围边界，
  不新增 HID 触摸屏报告。
- H96 定制固件不执行 `dispatchGesture`：触摸模式下点按/长按依赖节点动作，
  纯 Surface 区域无效；此类设备应改用鼠标模式。
- 鼠标模式的光标为盒端无障碍覆盖层箭头，位置依赖相对位移累计；
  光标 5 秒空闲后自动隐藏，再次移动会按上次位置重建。
- 触控板不显示盒子画面，两种模式都凭记忆定位。

## 实现映射

- 手机端视图：`app/src/com/koco/tvremote/TouchPadView.java`
  （`setMouseMode(boolean)` 与两套手势状态机，提示文案随模式切换）
- 手机会话：`app/src/com/koco/tvremote/TouchSession.java`
  （恢复 `move/tap/longTap/press/release/scroll/showCursor`，与绝对命令并存）
- 手机界面：`app/src/com/koco/tvremote/MainActivity.java`
  （三段 input 模式、`input` 持久化与迁移、蓝牙拦截与置灰、进入鼠标模式显示光标）
- 手机资源：`app/res/values/strings.xml`、`values-en/strings.xml`
  （`input_touch`、`input_mouse`、`touchpad_mouse_hint_top/bottom`）
- 网页：`server/assets/web/index.html`（第三段按钮）、`app.js`
  （鼠标模式手势与 `/api/move` 等路由、模式持久化与迁移）、`i18n` 文案
- 盒端路由：`server/src/com/koco/tvremoteserver/WebServer.java`
  （恢复 `/api/move`、`/api/click`、`/api/tap`、`/api/long`、`/api/scroll`）
- 自动测试：`tests/protocol/WebApiTest.java`（`tc_touch` 内新增鼠标路由用例，
  覆盖成功、缺参 err、缺 token 401、GET 405）

## 测试映射

- 自动：`tests/protocol/run.ps1`（协议回归 + Web API 回归 + 二维码）
- 手工：`tests/manual/SPEC-029-touchpad-mouse-fallback.md`
