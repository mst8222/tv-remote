# SPEC-018 OK 键长按与陀螺仪飞鼠

## 元信息

- 状态：developing
- 创建：2026-10-21
- 更新：2026-10-21（开工：spec 与手工清单已建立，移入 developing）
- 优先级：high

## 变更记录

- 2026-10-21 创建：用户提出两点需求
  1. OK 键要像实体遥控器那样区分长按与短按（部分 TV 软件长按 OK 有功能）；
  2. 遥控器增加「鼠标功能」键，调用手机陀螺仪实现飞鼠。
  已确认：飞鼠仅在蓝牙 HID 模式实现（直连无法移动系统光标）；飞鼠模式下 OK 键当鼠标左键。
- 2026-10-21 界面修正：按用户要求取消独立整行的「鼠标」胶囊，改为遥控器卡标题行
  右上角的圆形箭头键（↖，参考实体遥控器），激活时蓝底白字；同步修改 FR-18-5 与手工清单。
- 2026-10-21 真机复测：H96、Fold3 飞鼠均可用。Fold3 失败根因同为盒子缓存旧 HID 描述符
  （日志 `dscp_len = 90` 无鼠标集合），在 Fold3 上取消配对重连后变为 `dscp_len = 142`，
  `dumpsys input` 出现带鼠标轴的输入设备。已在 README「常见问题 / 已知限制」补充
  删配对重配的处理方式与版本升级说明。
- 2026-10-21 待手工项仍为 AC-1 / AC-2 / AC-3 的长按界面效果、AC-5（无陀螺仪机型）、
  AC-7 的切换场景，spec 保持在 developing。

## 背景与目标

现有手机端 OK 键只发一次短按：蓝牙 HID 固定 down + 35ms up，直连发送
`KEY <token> 23`，两端都无法触发盒子端应用的长按菜单。同时遥控器没有空中鼠标，
在需要指向操作的界面（浏览器、文件管理、部分游戏）只能靠方向键逐格移动。

目标：
- OK 键具备实体遥控器的短按/长按语义；
- 增加飞鼠开关，蓝牙 HID 模式下用手机陀螺仪移动盒子光标，OK 键作为左键。

## 功能需求（FR）

- FR-18-1 手机端 OK 键按 500ms（与 Android 系统长按阈值一致）区分短按与长按，
  手指抬起时判定；手指滑出后取消（`ACTION_CANCEL`）按抬起处理。
- FR-18-2 蓝牙 HID 遥控模式：OK 按下立即向盒子发键盘 Enter 按下报告，
  抬起发释放报告；按住期间盒子持续处于「按下」状态，由盒子端应用自行判定长按，
  与实体键盘/遥控器行为一致，不做手机侧定时补发。
- FR-18-3 直连模式：按下不发送；抬起时若按住时长 < 500ms 发
  `KEY <token> 23`，否则发 `KEYLONG <token> 23`。
- FR-18-4 协议新增 `KEYLONG <token> <keycode>`：
  - 仅接受 keycode 23，其余返回 `ERR cmd`；
  - 缺参/非数字返回 `ERR arg`；token 错误返回 `ERR auth`（沿用现有认证顺序）；
  - 无障碍未开启返回 `ERR a11y`；
  - 盒端对当前焦点节点执行 `ACTION_LONG_CLICK`（沿父链找到可长按节点），
    成功 `OK`；无焦点/节点不可长按视为无操作返回 `OK`；动作被拒 `ERR exec`。
- FR-18-5 遥控器卡标题行右上角增加圆形箭头键（↖）作为飞鼠开关，
  直径与系统键行的电源键相同，右边缘与电源键右边缘对齐：
  - 仅蓝牙 HID 模式可开启；直连模式点击提示「飞鼠需要蓝牙模式」；
  - 手机无陀螺仪时提示不支持，不进入飞鼠模式；
  - 开启时按键蓝底白字并在状态栏显示飞鼠操作提示，再次点击关闭。
- FR-18-6 飞鼠模式下用 `Sensor.TYPE_GYROSCOPE` 的角速度换算出鼠标相对位移，
  通过蓝牙 HID 鼠标报告发送；蓝牙 HID 描述符新增 Mouse Report
  （Report ID 3：按键位 + X + Y），SDP subclass 从 KEYBOARD 改为 COMBO。
- FR-18-7 飞鼠模式下 OK 键作为鼠标左键：按下发左键按下报告，抬起发释放报告，
  支持按住拖动/框选；退出飞鼠模式时补发一次释放报告，避免按键卡住。
- FR-18-8 飞鼠模式在以下情况自动退出并停止陀螺仪监听：再次点击开关、切换到直连、
  切换设备、蓝牙断开、Activity 销毁；不持久化飞鼠开关状态。
- FR-18-9 新增中英文文案（飞鼠开关名、需要蓝牙、无陀螺仪、已开启提示）。

## 非功能需求（NFR）

- 陀螺仪采样 `SENSOR_DELAY_GAME`（约 50Hz）；死区 0.05 rad/s 抑制手抖，
  单次报告位移限制在 ±20，避免瞬移。
- 鼠标报告最小间隔 20ms，避免 HID 发送队列堆积导致光标漂移。
- 飞鼠关闭或蓝牙未连接时不注册传感器，不产生后台耗电。
- 不改变现有方向键/音量/系统键语义；飞鼠模式不影响直连命令。

## 验收标准

- [ ] AC-1 蓝牙模式下短按 OK，盒子端应用触发一次确认（H96 实测）。 【手工】
- [ ] AC-2 蓝牙模式下按住 OK 约 1 秒再抬起，盒子端应用触发长按功能（选一个支持
      长按菜单的界面验证）。 【手工】
- [ ] AC-3 直连模式下长按 OK，盒子端收到 `KEYLONG` 并执行 `ACTION_LONG_CLICK`
      （Fold3/H96 上验证节点可长按的界面）。 【手工】
- [ ] AC-4 蓝牙模式开启飞鼠后，转动手机可移动盒子光标；按 OK 可点击目标。 【手工】
- [ ] AC-5 手机无陀螺仪（模拟器）点击「鼠标」提示不支持，不进入飞鼠模式。 【手工】
- [ ] AC-6 直连模式点击「鼠标」提示需要蓝牙模式。 【手工】
- [ ] AC-7 关闭飞鼠、切直连、切设备、蓝牙断开、退出 App 后陀螺仪停止，
      光标不再移动，左键状态已释放。 【手工】
- [ ] AC-8 `powershell -File tests\protocol\run.ps1` 全绿（新增 KEYLONG 用例）。 【自动】
- [ ] AC-9 `python build.py app` / `python build.py server` 构建通过。 【自动】

## 边界与已知限制

- 直连长按依赖盒子端无障碍节点支持 `ACTION_LONG_CLICK` 且
  `isLongClickable()` 为 true；不支持的应用会无效果或返回 `ERR exec`。
- 飞鼠需要盒子支持蓝牙 HID 鼠标；部分 Android TV 对 COMBO 描述符兼容性有限。
- 盒子端会把首次配对时的 HID 报告描述符持久化在绑定记录里（H96 实测存于
  `/data/misc/bluedroid/bt_config.conf` 的 `HidDescriptor`），重连不会重新读取。
  因此从旧版本升级后，曾在旧版配对过的盒子必须**删除配对并重新配对一次**，
  否则盒子仍按「无鼠标」的旧描述符建输入设备，飞鼠无效。
- 陀螺仪映射：水平位移取绕屏幕竖直轴（Y）的角速度，垂直位移取绕水平轴（X）
  的角速度；Z 轴只在平放打转时有值，不能用于水平位移。灵敏度为固定常量，
  手感不合适时调整常量，不做用户可调灵敏度的设置项。
- 飞鼠不提供滚轮、右键、中键；不由直连模式提供光标移动。
- 蓝牙 HID 的 Enter 长按在盒子端是否表现为长按，取决于盒子应用对 key down/up
  的处理；手机侧不再额外定时补发按键。

## 实现映射

- 协议：`server/src/com/koco/tvremoteserver/ServerService.java` 新增 `KEYLONG` 分支；
  `TvRemoteAccessibilityService.keyLong()` / `longClickFocused()` 执行 `ACTION_LONG_CLICK`。
- 手机端直连：`app/src/com/koco/tvremote/LanClient.java` 新增 `keyLong(int)`。
- 手机端按键：`MainActivity.buildDpadDisc()` 的 OK 改为 `OnTouchListener`，
  `okDown()/okUp()/okCancel()` 按模式分流；`LONG_PRESS_MS = 500`。
- 蓝牙 HID：`app/src/com/koco/tvremote/BtHid.java` 新增鼠标 Report 3、
  SDP subclass 改为 `SUBCLASS1_COMBO`，新增 `sendKeyDown()/sendKeyUp()`、
  `sendMouseMove()/sendMouseButton()/releaseAll()`。
- 飞鼠 UI 与传感器：`MainActivity.buildAirKey()`（遥控器卡标题行右上角 ↖ 圆键）、
  `toggleAirMouse()/stopAirMouse()/gyroListener`；退出条件接入 `enableLan()`、
  `applyDeviceMode()`、蓝牙断开回调、`onDestroy()`。
- 文案：`app/res/values/strings.xml`、`values-en/strings.xml` 新增 air_on / air_off /
  air_need_bt / air_no_sensor。
- 版本：两端 `versionCode=6` / `versionName=1.5.0`。

## 测试映射

- 自动：`tests/protocol/ProtocolTest.java`（KEYLONG 语义与错误分支）、
  `tests/protocol/MockBoxServer.java`（参照实现）
- 手工：`tests/manual/SPEC-018-ok-longpress-airmouse.md`
