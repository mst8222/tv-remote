# SPEC-028 触控板绝对触摸（点哪打哪）

## 元信息

- 状态：developing
- 创建：2026-10-22
- 更新：2026-10-22（开工）
- 优先级：high

## 变更记录

- 2026-10-22 创建并开工：触控板语义由「笔记本触控板 + 飞鼠光标相对移动」改为
  「手机式绝对触摸」：手指按在手机触控板的位置直接映射到盒子屏幕同一位置，
  不再显示光标、不再累计相对位移。直连通道新增归一化绝对坐标命令；
  蓝牙 HID 鼠标只有相对位移，蓝牙模式下触控板停用；网页触控板同步改为绝对触摸。
- 2026-10-22 实现完成并真机复测：协议、MockBoxServer、自动用例、双端代码、网页路由与
  手势全部完成，自动回归 225 项全绿（协议 156 + Web API 53 + 二维码 16）。
  H96 实测发现坐标换算缺陷：服务进程的 Resources/Display/窗口指标仍是物理分辨率
  1280x960，而节点边界与手势坐标是覆盖后的 2048x1536，导致点按落点整体偏上；
  已改为以当前窗口根节点边界换算（`screenSize()`），修复后点按当贝桌面「飞牛TV」
  图标可直接打开应用、滑动可滚动媒体列表，全程无光标。

## 背景与目标

SPEC-023 / SPEC-025 的触控板沿用 SPEC-021 飞鼠语义：手指拖动产生相对位移，
盒端维护一个应用层光标，用户需要「先移动光标到目标，再点按」。实际使用中
两步操作、光标位置不直观，与用户期望的「像操作手机一样直接点盒子界面」不符。

目标：触控板成为盒子屏幕的等比映射面——手指点哪里，盒子就点哪里；
按住不动即长按该位置；单指滑动即从起点到终点的滑动手势。
不显示光标，不做相对位移累计，不使用双击/双指滚动等触控板专用手势。

## 功能需求（FR）

- FR-28-1 绝对映射：`TouchPadView` 把手势坐标换算为归一化整数
  `x,y ∈ [0,10000]`（万分比，盒端按自身显示宽高还原像素），
  与触控板实际尺寸、手机分辨率无关；不再使用 1.5 倍增益与相对位移累计。
- FR-28-2 点击：手指按下到抬起未超过 8dp 且未触发长按，发送绝对点按，
  盒端优先命中该点可点击节点执行 `ACTION_CLICK`，否则合成点按手势。
- FR-28-3 长按：按住不动达到 500ms 立即发送绝对长按；
  盒端优先命中可长按节点执行 `ACTION_LONG_CLICK`，否则合成 600ms 按住手势。
  长按触发后手指继续移动不产生任何追加命令。
- FR-28-4 滑动：单指移动超过 8dp 后抬起，发送从起点到终点的绝对滑动，
  时长取实际按住时长（限 50~1500ms）；盒端优先对起点下方可滚动节点执行
  `ACTION_SCROLL_FORWARD/BACKWARD`（竖直滑动时，按位移折算 1~3 格），
  否则合成滑动手势。
- FR-28-5 多指：第二指落下即取消本次手势（不发任何命令），全部抬起后恢复，
  不实现双指滚动、双击、拖动按住等旧触控板语义。
- FR-28-6 蓝牙边界：蓝牙 HID 鼠标只能发相对位移，蓝牙模式下触控板不可用：
  用户点「触控板」段时提示「触控板需使用直连」并不切换；
  已处于触控板时切换到蓝牙模式，自动切回方向盘并给出同一提示；
  蓝牙模式下触控板分段按钮置灰（alpha 0.45）。
- FR-28-7 盒端协议（归一化坐标，参数均为十进制整数）：
  - `MTAPN <token> <x> <y>`：绝对点按，x/y ∈ [0,10000]；
  - `MLONGN <token> <x> <y>`：绝对长按，x/y ∈ [0,10000]；
  - `MSWIPEN <token> <x1> <y1> <x2> <y2> <ms>`：绝对滑动，
    坐标 ∈ [0,10000]，ms ∈ [50,1500]；
  - 认证顺序不变：token 错误优先 `ERR auth`；参数数量/范围/数字格式错误
    返回 `ERR arg`；无障碍未开启返回 `ERR a11y`；执行异常返回 `ERR exec`。
- FR-28-8 无光标：进入/退出触控板只发送 `MHIDE` 清理可能残留的旧光标，
  不再发送 `MOVE 0 0`，绝对触摸全程不创建、不移动、不显示盒端光标。
- FR-28-9 网页触控板：网页端手势与手机端一致（点按 / 长按 / 单指滑动），
  使用同一组归一化命令；新增 `POST /api/tapn`、`POST /api/longn`、
  `POST /api/swipen`，请求体分别 `{x,y}`、`{x,y}`、`{x1,y1,x2,y2,ms}`；
  删除网页已不再调用的 `/api/move`、`/api/click`、`/api/tap`、`/api/long`、
  `/api/scroll` 路由；`/api/hide` 保留用于进入触控板时清理旧光标。
  触控板提示改为「点按对应位置 · 长按菜单」「上下滑动列表」。
- FR-28-10 生命周期保持：切设备、切模式、蓝牙断开、`onPause()`、`onDestroy()`
  仍先重置手势（清除长按定时、取消进行中的手势），再发 `MHIDE` 并关闭直连会话。

## 非功能需求（NFR）

- NFR-28-1 单条命令即可完成一次点击/长按/滑动，不再需要按下→多次移动→抬起
  的命令序列；局域网内自手指抬起至盒子响应不超过 150ms。
- NFR-28-2 方向盘、系统键行、音量行、飞鼠、应用列表等既有功能不变，
  协议回归与 Web API 回归全部通过。
- NFR-28-3 任何手势结束路径都不得残留长按定时器或进行中的手势状态；
  异常断连不刷屏（沿用现有 2 秒节流）。
- NFR-28-4 最低 API 26；不新增权限；不修改 HID 键盘/消费者控制/鼠标报告。
- NFR-28-5 坐标越界一律在盒端按屏幕范围夹取，不产生负坐标或越界手势。

## 验收标准（AC）

- AC-1 自动：`MTAPN` 正常、缺坐标、非数字、越界（-1 / 10001）、错误 token、
  无障碍关闭；`MLONGN` 同上（至少正常/缺参/越界/错误 token/无障碍关闭）；
  `MSWIPEN` 正常、参数不足、坐标越界、ms 越界（49 / 1501）、错误 token、
  无障碍关闭；记录字段与坐标一致。
- AC-2 自动：`TouchSession` 一次会话内连续发送点按/长按/滑动，全部按序到达，
  且只建立 1 条连接。
- AC-3 自动：`powershell -File tests\protocol\run.ps1` 全绿（含 Web API 新路由用例）。
- AC-4 构建：`python build.py app` 与 `python build.py server` 通过。
- AC-5 手工（直连）：手机触控板点按盒子桌面图标可直接打开应用（无需先移光标），
  长按图标/条目触发长按行为，上下滑动列表可滚动；盒端全程无光标出现。
- AC-6 手工（蓝牙）：蓝牙模式下点「触控板」提示「触控板需使用直连」且不切换；
  当前为触控板时切到蓝牙自动回到方向盘；按钮置灰。
- AC-7 手工（网页）：网页触控板点按/长按/滑动与手机端一致，切换分段与切设备
  正常，离开触控板后无残留手势。
- AC-8 手工：切设备、切模式、退出 App 后，重新进入触控板无卡住的手势或长按。

## 验收进度

- 2026-10-22 自动：AC-1 / AC-2 / AC-3 / AC-4 通过（协议 156 + Web API 53 + 二维码 16
  共 225 项全绿；`python build.py server` 330 KB、`python build.py app` 141 KB）。
- 2026-10-22 真机（H96 直连）：AC-5 部分通过——点按当贝桌面「飞牛TV」图标直接启动
  `com.trim.tv`，媒体列表上下滑动正常滚动，全程无光标；长按仅对可长按节点生效，
  当贝图标本身没有长按行为，属被控端限制。
- 待手工：AC-6（手机端蓝牙提示与置灰）、AC-7（网页端）、AC-8（切设备/切模式/退出重进）。

## 边界与已知限制

- H96 Max V56 定制固件不执行 `dispatchGesture`：点按/长按依赖节点动作，
  在纯 Surface（视频播放器画面、部分游戏）上可能无反应；滑动若起点下方
  没有可滚动节点则无效。该限制此前已记入 SPEC-018 / SPEC-026。
- 部分 TV 桌面/应用只响应 D-pad，不响应触摸，绝对触摸对它们无效，
  属被控端界面限制，不改用按键路径。
- 触控板不显示盒子画面，用户凭记忆定位；不提供比例预览、光标预览。
- 不实现双击、双指滚动、捏合缩放、多点触控、拖动按住（长按后拖动）。
- 蓝牙模式触控板不可用，这是 HID 鼠标协议能力边界，不通过新增 HID 触摸屏绕过。
- 旧协议命令 `MOVE` / `MCLICK` / `MTAP` / `MLONG` / `SCROLL` 仍保留在盒端
  （SPEC-021/023 的飞鼠与历史能力），仅客户端不再调用；网页路由已删除。

## 实现映射

- 盒端协议：`server/src/com/koco/tvremoteserver/ServerService.java`
  （`MTAPN` / `MLONGN` / `MSWIPEN` 分发与参数校验）
- 盒端注入：`server/src/com/koco/tvremoteserver/TvRemoteAccessibilityService.java`
  （`touchTap` / `touchLong` / `touchSwipe` 静态入口，归一化坐标转像素，
  节点动作优先、合成手势兜底，不创建光标；`screenSize()` 以当前窗口根节点边界
  取屏幕像素尺寸，规避服务进程 Display 指标停留在物理分辨率的问题）
- 手机端手势：`app/src/com/koco/tvremote/TouchPadView.java`（绝对映射状态机）
- 手机端会话：`app/src/com/koco/tvremote/TouchSession.java`
  （`tapAt` / `longAt` / `swipe` / `hide`）
- 手机端接线：`app/src/com/koco/tvremote/MainActivity.java`
  （Sink 分发、蓝牙模式停用触控板、进入/退出清理）
- 文案：`app/res/values/strings.xml`、`app/res/values-en/strings.xml`
- 网页：`server/assets/web/app.js`、`server/assets/web/index.html`；
  路由：`server/src/com/koco/tvremoteserver/WebServer.java`
- 协议参照：`tests/protocol/MockBoxServer.java`；用例：`tests/protocol/ProtocolTest.java`、
  `tests/protocol/WebApiTest.java`

## 测试映射

- 自动：`tests/protocol/ProtocolTest.java` 新增 `mtapn_*` / `mlongn_*` / `mswipen_*`
  与重写后的 `touch_session_*`；`tests/protocol/MockBoxServer.java` 同步归一化命令；
  `tests/protocol/WebApiTest.java` 更新 `/api/tapn` / `/api/longn` / `/api/swipen`
  与删除旧路由后的断言。
- 手工：`tests/manual/SPEC-028-touchpad-absolute-touch.md`
