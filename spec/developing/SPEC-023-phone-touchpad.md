# SPEC-023 手机端触控板（直连 + 蓝牙）

## 元信息

- 状态：developing
- 创建：2026-10-22
- 更新：2026-10-22（开工）
- 优先级：high

## 变更记录

- 2026-10-22 创建并开工：手机端遥控器主卡增加「方向盘 / 触控板」二选一切换。
  触控板宽度改为铺满卡片内容区（与按钮行等宽）后，手感与点击面积同步放大。
  触控板复用 SPEC-021 的直连 `MOVE`/`MCLICK` 与盒端无障碍光标，同时接入蓝牙 HID 鼠标；
  新增 `MTAP`/`MLONG`/`SCROLL` 命令、直连专用长连接会话 `TouchSession`、
  蓝牙鼠标滚轮字段。可行性门（AC-1）在 H96 真机先执行，未通过则暂停直连部分。
- 2026-10-22 实现完成，待手工验收：
  - 盒端：`ServerService` 接入 `MTAP`/`MLONG`/`SCROLL`，`MOVE` 放宽到 ±127；
    `TvRemoteAccessibilityService` 增加节点命中（最多上溯两级）、节点点击/长按/滚动、
    手势兜底、滚动合成滑动、光标重建保持上次位置、旋转夹取；
    `accessibility_service.xml` 增加 `canPerformGestures`。
  - 手机端：新增 `TouchSession`（专用长连接、20ms 合并移动、127 分批不丢位移、
    空闲 20s 回收、关闭前最多等 500ms 排空队列）、`TouchPadView`（手势状态机）；
    `LanClient` 增加短连接命令；`BtHid` 鼠标报告改为 4 字节并增加滚轮与位移余量补发；
    `MainActivity` 增加分段切换、Sink 分发、切设备/切模式/暂停/退出时的释放与重建。
  - 自动测试：协议 125 例 + Web API 55 例 + 二维码 16 例全部通过。
  - 真机（H96）：`MTAP` 节点点击成功打开 Kodi；光标覆盖层坐标与命令一致；
    设置页合成滑动有响应但会触发触摸点击，需在支持触摸的应用复核。
- 2026-10-22 触控语义由 SPEC-028 取代：手机端触控板改为绝对触摸（点哪打哪），
  不再使用 `MOVE`/`MCLICK`/`MTAP`/`MLONG`/`SCROLL` 与盒端光标；蓝牙模式下
  触控板停用。本 spec 中上述光标/相对位移相关条款仅保留作历史记录，
  当前有效需求以 SPEC-028 为准。

## 背景与目标

现有手机端只能靠方向盘逐格按键操作盒子，效率低于实体遥控器的指向式操作。
SPEC-018 已实现蓝牙 HID 飞鼠，SPEC-021 已实现直连覆盖光标与 `MOVE`/`MCLICK`，
但两者各自只有陀螺仪/Web 入口，手机原生界面没有「用手指直接推光标」的体验。

目标：在手机端遥控器主卡内提供触控板，把盒子操作变为手指在手机屏幕上的
拖动、点按、按住、长按、双指滚动，直连与蓝牙两种通道都可用。

## 功能需求（FR）

- FR-23-1 输入方式切换：遥控器主卡标题行下方增加「方向盘 / 触控板」分段控件，
  两段等宽居中，高 34dp；切换只改变大圆盘位置的输入控件，系统键行、音量行、
  应用卡等其余部分保持不变。选择持久化到 SharedPreferences 键 `input`
  （`dpad` / `touchpad`），默认 `dpad`，与设备无关。
- FR-23-2 触控板外观：宽度铺满卡片内容区，与系统键行 / 音量行等宽；高度沿用方向盘
  直径（紧凑 200dp / 手机 240dp / 平板 280dp），方向盘本身尺寸与位置不变。
  背景 `#1B2432`，28dp 圆角、1dp `#2A3547` 描边；中心显示两行 12sp `SUB` 提示
  「拖动移动光标 · 点按确定」「双指滑动滚动」；按下或拖动时描边变 `ACCENT`；
  `contentDescription` 为「触控板」。触控板整体消费触摸并禁止外层滚动容器拦截。
- FR-23-3 手势映射（单指）：
  - 拖动位移超过 8dp 后按 1.5 倍增益换算为光标相对位移；
  - 抬起时若从未超过 8dp、未进入长按待定/拖动，则触发单击；
  - 两次单击间隔小于 300ms 时按两次单击发出（由盒子/应用识别双击）；
  - 按住不动达到定时阈值进入长按：蓝牙在 350ms 时立即按下左键并保持；
    直连在 500ms 时只进入待定态，抬起且期间未移动才发送 `MLONG`；
  - 长按待定期间移动超过 8dp 则转为拖动（按下 + 连续位移 + 抬起释放）。
- FR-23-4 双指滚动：第二指落下时立即退出长按待定/拖动，进入滚动；
  两指平均 Y 位移每 24dp 记 1 格；蓝牙滚轮两格之间至少 40ms，
  直连 `SCROLL` 两次之间至少 100ms；手指少于两指时结束本次手势。
- FR-23-5 触控板与陀螺仪飞鼠互斥：进入触控板前关闭飞鼠；开启飞鼠时自动切回方向盘。
- FR-23-6 直连通道：
  - 光标移动使用专用长连接会话 `TouchSession`（独立线程 + 队列，20ms 合并发送），
    只用于触控板；退出触控板即关闭，其余按键仍走原短连接（SPEC-011 不变）；
  - 单击发 `MTAP`，长按发 `MLONG`，滚动发 `SCROLL <steps>`，拖动发
    `MCLICK 1` → 多次 `MOVE` → `MCLICK 0`；
  - 单次 `MOVE` 单轴上限 127，超出部分留到下次发送，不丢位移；
  - 进入触控板时先发一次 `MOVE 0 0` 让盒子光标可见并校验无障碍状态；
  - 会话失败（断开/超时）自动重连；错误提示同一错误 2 秒内只提示一次。
- FR-23-7 蓝牙通道：复用 HID 鼠标 Report 3，描述符增加滚轮字段，
  报告改为 4 字节（按键 + X + Y + 滚轮）；移动单次上限 127 并保留待发余量定时补发；
  单击为左键按下后 50ms 抬起；拖动为左键保持按下 + 连续移动；
  滚动发送滚轮 notch。
- FR-23-8 盒端协议：
  - `MOVE <token> <dx> <dy>`：dx/dy ∈ [-127,127]，沿用 SPEC-021 语义；
  - 新增 `MTAP <token>`：在光标处优先命中可点击节点执行 `ACTION_CLICK`，
    否则合成点按手势；
  - 新增 `MLONG <token>`：优先命中可长按节点执行 `ACTION_LONG_CLICK`，否则合成
    600ms 按住手势；
  - 新增 `SCROLL <token> <steps>`：steps ∈ [-5,5]，正数向下/向前；优先对光标处
    可滚动节点执行 `ACTION_SCROLL_FORWARD/BACKWARD`，否则合成短滑动手势；
    无任何滚动目标视为无操作并返回 `OK`；
  - 认证顺序沿用现有实现：token 错误优先 `ERR auth`；无障碍未开启返回 `ERR a11y`。
- FR-23-9 盒端节点命中：遍历当前活动窗口节点树，取包含光标点的最小面积节点；
  点击/长按/滚动分别要求 `isClickable` / `isLongClickable` / `isScrollable`；
  仅可聚焦节点先 `ACTION_FOCUS` 再执行点击/长按。
- FR-23-10 生命周期：切换设备、切换连接方式、蓝牙断开、`onPause()`、`onDestroy()` 时
  调用触控板重置：有按下态先发释放，再发 `MHIDE` 让盒端立即隐藏光标，最后关闭直连会话；
  切设备必须先停止触控板再改直连目标，避免抬起命令发到新设备。切回方向盘同样发
  `MHIDE`，不等盒端 5 秒空闲回收；服务停止或无障碍关闭时立即移除并释放按压。
- FR-23-11 错误提示：未连接蓝牙时提示「触控板需要先连接蓝牙」，
  未配对直连时提示「触控板需要先配对直连」，直连 `ERR a11y` 时走现有
  `Errors.friendly()` 文案；不新增独立错误文案体系。

## 非功能需求（NFR）

- NFR-23-1 触控板移动端到端延迟：局域网下从手指位移到盒子光标更新不超过 100ms；
  直连移动按 20ms 合并，不逐点建连。
- NFR-23-2 触控板不得影响方向盘模式：原有按键、长按 OK、陀螺仪飞鼠行为保持不变，
  现有协议回归用例全部通过。
- NFR-23-3 失败安全：任何异常路径都必须释放按压并关闭会话，不得出现盒子端触摸
  卡在按下状态或手机端线程泄漏。
- NFR-23-4 节流：高频移动不产生逐条错误提示；滚动按各自最小间隔发送。
- NFR-23-5 兼容：最低 API 26；不引入 root、不新增系统权限、不修改键盘与消费者控制报告。
- NFR-23-6 屏幕旋转/分辨率变化后盒端光标坐标重新夹取到新屏幕范围内。

## 验收标准（AC）

- AC-1 可行性门：H96 上桌面、Kodi、当贝播放器中至少两类界面能响应直连
  节点点击/合成点按与拖动；未通过则暂停直连触控板，仅交付蓝牙触控板并记录结论。
- AC-2 自动：`MOVE` 正常、缺 dy、非数字、越界（±128）、错误 token、无障碍关闭；
  `MTAP` 正常、错误 token、无障碍关闭；`MLONG` 正常、无障碍关闭；
  `MCLICK` 1/0 正常、非法 state、未按下直接 0、无障碍关闭；
  `SCROLL` 正常、非数字、越界（±6）、错误 token；`MHIDE` 正常、多参、错误 token、
  无障碍关闭；`TouchSession` 单连接内连续移动 + 点击 + 滚动全部到达。
- AC-3 自动：`powershell -File tests\protocol\run.ps1` 全绿（现有 59 例保持通过）。
- AC-4 自动/构建：`python build.py app` 与 `python build.py server` 构建通过。
- AC-5 手工（蓝牙）：盒子删除旧配对并重新配对后，触控板可移动光标、单击打开应用、
  按住拖动、长按、双击、双指滚动。
- AC-6 手工（直连）：触控板可移动光标、点按打开桌面应用、拖动、长按、列表滚动；
  光标 5 秒无操作自动消失。
- AC-7 手工：切换方向盘/触控板不跳动布局；触控板宽度与系统键行一致，方向盘尺寸不变；
  切回方向盘后盒端光标立即消失；方向盘原有按键回归正常；进入触控板时飞鼠自动关闭，
  开启飞鼠时自动切回方向盘。
- AC-8 手工：切设备、切模式、蓝牙断开、退出 App、停止盒端服务后，无卡住按压，
  光标立即消失（切模式发 `MHIDE`）或 5 秒内消失，重连后触控板可继续使用。
- AC-9 手工：未连接蓝牙、未配对直连、无障碍关闭、网络中断时的提示正确且不刷屏。

## 边界与已知限制

- 直连光标是应用层覆盖层，不是系统鼠标指针，会出现在盒子截图/录屏中；
  只覆盖响应触摸或提供可点击/可滚动节点的应用，纯 D-pad 应用可能无反应。
- 拖动只能使用合成手势，节点动作无法实现连续拖拽。
- 蓝牙新增滚轮字段改变了 HID 描述符，曾配对过的盒子必须删除配对并重新配对；
  部分 H96 固件还需重启盒子蓝牙或检查 `bt_config.conf` 才能识别新描述符。
- 蓝牙长按依赖盒子端从「左键按下」时刻计 500ms：手机在 350ms 按下，合计约 850ms
  触发；蓝牙下按住 350~850ms 松开会被视为普通点击。直连 `MLONG` 为 500ms 即时触发，
  两通道手感不完全一致。
- 节点式滚动是步进的，不如蓝牙系统滚轮平滑；不做像素级模拟。
- 双击按「连续两次单击」发送，识别阈值由盒子/应用决定，不额外延迟。
- 不提供右键、中键、横向滚动、灵敏度设置、手势自定义。
- 不修改手机端以外的遥控界面；网页触控板由 SPEC-025 独立实现，
  复用本 spec 的直连触控语义与 `MTAP`/`MLONG`/`SCROLL` 命令。

## 实现映射

- 盒端协议：`server/src/com/koco/tvremoteserver/ServerService.java`（`MOVE`/`MTAP`/`MLONG`/
  `MCLICK`/`SCROLL` 分发与参数校验）
- 盒端光标与节点：`server/src/com/koco/tvremoteserver/TvRemoteAccessibilityService.java`
  （`airMove`/`airTap`/`airLongTap`/`airScroll`、`hitNode`、`smallestNodeAt`、
  `dispatchTap`/`dispatchLongPress`/`dispatchDrag`/`dispatchScrollSwipe`、`onConfigurationChanged`）
- 盒端手势声明：`server/res/xml/accessibility_service.xml`（`canPerformGestures`）
- 手机端会话：`app/src/com/koco/tvremote/TouchSession.java`、`LanClient.java`
- 手机端手势视图：`app/src/com/koco/tvremote/TouchPadView.java`
- 手机端蓝牙鼠标：`app/src/com/koco/tvremote/BtHid.java`（4 字节报告、滚轮、余量补发）
- 手机端接线与界面：`app/src/com/koco/tvremote/MainActivity.java`（分段切换、Sink、生命周期）
- 文案：`app/res/values/strings.xml`、`app/res/values-en/strings.xml`
- 协议参照实现：`tests/protocol/MockBoxServer.java`；用例：`tests/protocol/ProtocolTest.java`

## 测试映射

- 自动：`tests/protocol/ProtocolTest.java` 新增 `move_*` / `mtap_*` / `mlong_*` /
  `mclick_*` / `scroll_*` / `touch_session_*` 用例；
  `tests/protocol/MockBoxServer.java` 同步五个命令的参照实现与记录字段。
- 手工：`tests/manual/SPEC-023-phone-touchpad.md`
  （双通道手势、光标生命周期、异常提示、切设备/切模式、重新配对、H96 可行性门）。
