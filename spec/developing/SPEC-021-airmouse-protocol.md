# SPEC-021 直连飞鼠协议（Web 端空气鼠标）

## 元信息

- 状态：developing
- 创建：2026-10-22
- 更新：2026-10-22（开工）
- 优先级：medium

## 变更记录

- 2026-10-22 创建：SPEC-020 明确不含飞鼠后，单独立项。目标是在直连协议中增加
  相对位移与左键命令，由盒端无障碍服务绘制应用层光标并合成触摸，
  供 Web 遥控页用陀螺仪操作；不改变蓝牙 HID 飞鼠（SPEC-018）。
- 2026-10-22 开工：先实现协议与 Web API，可行性门（AC-1）在 H96 真机执行，
  未通过则整体转 discarded。
- 2026-10-22 实现：按 `dispatchGesture` 的实际能力把按压改为「按下记录 + 移动分段拖动
  + 抬起合成点按/拖动」，不再声称单手势持续按压。
- 2026-10-22 调整 `MOVE` 单轴范围从 -20..20 改为 -127..127，供 SPEC-023 手机端触控板
  快速滑动使用；Web 飞鼠仍自行把单次位移限速到 ±20，手感不变。
  触控板同时复用本 spec 的 `MOVE`/`MCLICK` 与盒端覆盖光标，新增命令见 SPEC-023。
- 2026-10-22 Web 飞鼠 UI 废止：SPEC-025 删除网页飞鼠按钮与陀螺仪采样（FR-21-7 ~ FR-21-9），
  网页改用手接触控板（复用 `MOVE`/`MCLICK` 并新增 `MTAP`/`MLONG`/`SCROLL`）。
  本 spec 的协议能力（FR-21-1 ~ FR-21-6、FR-21-10、FR-21-11）与盒端光标仍然有效，
  仅 AC-4 ~ AC-8 的网页飞鼠手工验收不再适用。
- 2026-10-22 新增 `MHIDE <token>`（FR-21-12）：退出触控板或切回方向盘时立即移除光标，
  不再等 5 秒空闲回收；Web 对应 `POST /api/hide`，手机端由 `TouchSession.hide()` 发送。

## 背景与目标

SPEC-020 的 Web 遥控页只能靠方向键逐格移动，无法像实体飞鼠遥控器那样指向操作。
蓝牙 HID 飞鼠（SPEC-018）只在 Android 原生端可用，浏览器没有 HID 设备接口。

直连模式此前被认为「无法移动系统光标」，原因是 `INJECT_EVENTS` 属系统权限。
本 spec 改用另一条非 root 路径：无障碍服务绘制 `TYPE_ACCESSIBILITY_OVERLAY` 光标，
用 `dispatchGesture` 在光标位置合成触摸点击。它移动的是应用层光标而不是系统鼠标指针，
因此可覆盖 Android、iOS、HarmonyOS NEXT 的浏览器，但效果取决于被控应用是否响应触摸。

目标：

1. 直连协议具备相对位移与左键能力，Web 端可用陀螺仪飞鼠；
2. 不引入 root、不申请系统签名权限、不修改蓝牙 HID 实现；
3. 先做可行性验证，验证不通过就放弃，避免在无效路径上继续投入。

## 功能需求（FR）

- FR-21-1 协议新增 `MOVE <token> <dx> <dy>`：
  - `dx`、`dy` 为十进制整数，取值范围 -127..127（SPEC-023 起放宽单次步长上限；
    调用方自行限速，Web 飞鼠仍按 ±20 发送）；
  - 缺参、非整数、越界返回 `ERR arg`；
  - token 错误返回 `ERR auth`（沿用「认证先于参数」顺序）；
  - 无障碍未开启返回 `ERR a11y`；光标合成失败返回 `ERR exec`；成功 `OK`。
- FR-21-2 协议新增 `MCLICK <token> <state>`：`state` 仅允许 `0`（左键抬起）
  与 `1`（左键按下）；其余值或缺参返回 `ERR arg`；错误顺序同 FR-21-1。
- FR-21-3 盒端光标：首次成功执行 `MOVE` 或 `MCLICK` 时创建无障碍覆盖层光标
  （箭头图形，约 24dp，屏幕坐标居中初始），坐标按屏幕像素累加并限制在屏幕内；
  光标窗口不可聚焦、不可触摸，不拦截页面之外任何按键与焦点。
- FR-21-4 盒端点击：`MCLICK 1` 记录按下位置；按住期间 `MOVE` 继续驱动光标，
  并按位移分段合成短时拖动；`MCLICK 0` 时若累计位移超过 3px，合成一次完整拖动手势，
  否则在当前位置合成点按；未按下时收到 `MCLICK 0` 视为无操作并返回 `OK`。
  受 `dispatchGesture` 限制，按压不是单一长手势，而是分段合成，
  长时间按住且完全不动时不会产生持续按压事件。
- FR-21-5 光标生命周期：最后一次 `MOVE`/`MCLICK` 后 5 秒无新命令自动移除光标
  并释放未完成的按压；无障碍服务销毁、无障碍被关闭、`ServerService` 停止时
  立即移除光标且不残留触摸状态。
- FR-21-6 可行性门（实施第一步）：在 H96 上用 `dispatchGesture` 验证
  桌面、Kodi、当贝播放器的点按与拖动是否产生真实交互。任三类界面中有两类
  无响应，本 spec 转 `discarded` 并记录结论，不再继续开发。
- FR-21-7 Web 遥控页新增飞鼠开关，位置与交互语义与 SPEC-018 一致
  （遥控器卡标题行右上角圆形箭头键，激活蓝底白字），仅 HTTPS 页面可开启；
  点击时若页面不是安全上下文，提示「飞鼠需要 HTTPS 地址」。
- FR-21-8 陀螺仪映射与参数复用 SPEC-018：水平位移取绕屏幕竖直轴（Y）角速度，
  垂直位移取绕水平轴（X）角速度；死区 0.05 rad/s；单次上报位移 -20..20；
  上报间隔 20ms。iOS 13+ 需在用户手势中调用
  `DeviceOrientationEvent.requestPermission()`；被拒绝或无传感器时提示不支持，
  不进入飞鼠模式。
- FR-21-9 飞鼠模式下页面 OK 键改为发送 `MCLICK`：按下发 `1`，抬起发 `0`，
  支持按住拖动；退出飞鼠、页面隐藏、盒子断开、请求超时时必须补发 `0`。
- FR-21-10 Web API 扩展（沿用 SPEC-020 的鉴权、错误码透出与不缓存规则）：
  - `POST /api/move`，请求体 `{"dx":3,"dy":-2}`，对应 `MOVE`；
  - `POST /api/click`，请求体 `{"down":true}`，对应 `MCLICK`。
- FR-21-11 不改变现有 `KEY` / `KEYLONG` / `TEXT` / `APP` / `LIST` / `ICON`
  行为；不改 `BtHid`、不改蓝牙飞鼠；Android 原生直连界面本期不接入飞鼠 UI。
- FR-21-12 协议新增 `MHIDE <token>`：立即移除光标并释放未完成的按压，成功返回 `OK`；
  缺参或多参数返回 `ERR arg`；token 错误返回 `ERR auth`；无障碍未开启返回 `ERR a11y`。
  Web 对应 `POST /api/hide`（走同一命令与鉴权）。

## 非功能需求（NFR）

- NFR-21-1 从陀螺仪采样到光标移动的端到端延迟不超过 80ms（局域网）；
  `MOVE` 在盒端仅做内存坐标运算，不做磁盘或跨进程序列化。
- NFR-21-2 覆盖层不获取焦点、不消费触摸；光标存在时盒端原有按键与焦点行为不变。
- NFR-21-3 光标隐藏后不保留定时器与传感器监听；页面侧退出飞鼠即注销陀螺仪。
- NFR-21-4 失败安全：任何异常路径都必须移除光标、释放按压；不得出现光标残留
  或盒子端触摸卡在按下状态。
- NFR-21-5 屏幕旋转、分辨率变化后光标坐标重新夹取到新屏幕范围内。
- NFR-21-6 新增命令不改变现有协议错误顺序，回归用例全部保持通过。

## 验收标准（AC）

- AC-1 可行性门：H96 上桌面、Kodi、当贝播放器至少两类界面能响应
  `dispatchGesture` 的点按与拖动；未通过则本 spec 转 discarded。
- AC-2 自动：`MOVE` 缺参、非整数、越界返回 `ERR arg`；错误 token 返回
  `ERR auth`；无障碍关闭返回 `ERR a11y`；正常返回 `OK`。
- AC-3 自动：`MCLICK` 仅接受 0/1，其余返回 `ERR arg`；未按下时 `0` 返回 `OK`。
- AC-4 手工：HTTPS 页面开启飞鼠后转动手机，H96 上光标跟随移动，
  点按可打开桌面应用。
- AC-5 手工：按住 OK 移动可完成拖动/滚动（选一个响应触摸列表的应用）。
- AC-6 手工：退出飞鼠、关闭页面、拔掉盒子网络、停止服务后，光标 5 秒内消失，
  盒子端无卡住的按压。
- AC-7 手工：iPhone Safari（HTTPS、证书已信任）授权陀螺仪后可用；
  拒绝权限时提示不支持且不进入飞鼠。
- AC-8 手工：HTTP 页面点击飞鼠提示「飞鼠需要 HTTPS 地址」，不发任何命令。
- AC-9 `powershell -File tests\protocol\run.ps1` 全绿（含新增 MOVE/MCLICK 用例）；
  `python build.py app` 与 `python build.py server` 构建通过。
- AC-10 自动：`MHIDE` 正常返回 `OK`；多参、错误 token、无障碍关闭分别返回
  `ERR arg`、`ERR auth`、`ERR a11y`。手工：触控板切回方向盘后光标立即消失。

## 边界与已知限制

- 合成触摸不是系统鼠标指针：只对响应触摸的应用有效；纯 D-pad 应用可能完全无反应。
  这是可行性门的核心判断依据，也是本 spec 可能转 discarded 的原因。
- 不提供滚轮、右键、中键与双击语义；飞鼠只有移动与左键。
- 应用层光标会出现在系统截图/录屏中，不是系统 PointerIcon。
- iOS 陀螺仪要求安全上下文与用户手势授权；自签证书未受信任时该能力不可用。
- 本期只在 Web 页接入飞鼠 UI；Android 原生直连界面保持方向键操作，
  蓝牙模式飞鼠仍走 SPEC-018 的 HID 通道。
- 光标依赖 `ServerService` 与无障碍服务在线；任一停止即消失。
- 光标自动隐藏为 5 秒固定值，不提供用户可调项。

## 实现映射

- `server/src/com/koco/tvremoteserver/ServerService.java`：`MOVE` / `MCLICK`
  参数校验与错误码，停止服务时调用 `hideAirCursor()`。
- `server/src/com/koco/tvremoteserver/TvRemoteAccessibilityService.java`：
  `airMove`/`airClick`、`TYPE_ACCESSIBILITY_OVERLAY` 箭头光标、
  5 秒自动隐藏、`dispatchGesture` 点按与分段拖动。
- `server/src/com/koco/tvremoteserver/WebServer.java`：
  `/api/move`、`/api/click` 映射。
- `server/assets/web/app.js` + `index.html` + `style.css`：遥控器卡标题行飞鼠键、
  陀螺仪 20ms 上报、OK 按下/抬起映射、HTTP 与非安全上下文提示。
- `tests/protocol/ProtocolTest.java`：`move_*` / `mclick_*`；
  `WebApiTest.java`：`/api/move`、`/api/click`。

## 测试映射

- 自动：`tests/protocol/ProtocolTest.java` 新增 `move_*` / `mclick_*` 用例，
  `MockBoxServer` 同步参照实现（记录最近一次位移与按键状态）；
  `WebApiTest` 覆盖 `/api/move` 与 `/api/click`。
- 手工：`tests/manual/SPEC-021-airmouse-protocol.md`
  （含可行性门、H96 真机、iOS Safari、HTTP/HTTPS 两条路径）。
