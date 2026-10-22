# SPEC-005 蓝牙 HID 模式打通

## 元信息
- 状态：developing
- 创建：2026-10-21
- 更新：2026-10-21
- 优先级：high

## 变更记录
- 2026-10-21 创建：v1.0 遗留的实验性功能立项（当时 HID 报文通道未打通）
- 2026-10-21 转 developing：移除 ADB 后蓝牙成为 Fold3（无 root）唯一可控路径，
  补 FR-5-7~5-9（回前台重注册、记忆上次盒子、可发现配对入口），
  删除「断开退回 ADB」的旧约定
- 2026-10-21 实测打通：Note20 → H96 Max V56 配对后，`sendReport` 返回 true，
  盒子 `getevent` 收到 KEY_ESC / KEY_HOME / KEY_UP / KEY_VOLUMEUP /
  KEY_VOLUMEDOWN / KEY_MUTE，按键真实生效。同时修复两处缺陷：
  ① `onConnectionStateChanged` 把 CONNECTING 中间态当成断开，导致连上后
  模式标记被清空；② 进入蓝牙模式未清 lanMode，按键仍走直连。
  尚未完成 Note20 ↔ Fold3 系统配对（双方可发现但互相扫不到，属系统配对问题）。
- 2026-10-21 Fold3 打通：Note20 ↔ Fold3 在系统蓝牙设置完成配对后，App 注册 HID
  并连接成功（Fold3 `dumpsys input` 出现「嵩韬 的 Note20 Keyboard」）。
  实测发现键盘页 `Home(0x4A)` / `Esc(0x29)` 在手机上只被当作光标移动，
  改为消费者控制 `AC Home=0x0223` / `AC Back=0x0224`：手机按「←」从系统设置
  回到当前视频 App，按「⌂」回当贝桌面，H96 同步回归通过。
- 2026-10-21 记忆方式改为按设备（SPEC-015）：蓝牙目标从全局 `bt_addr`
  改为 `bt_<host>`，连接成功时同时记录 `mode_<host>=bt`。

## 背景与目标

手机作为蓝牙 HID 键盘/遥控器直连盒子（Android 9+ `BluetoothHidDevice`），
不依赖网络、无需开发者选项、配对后重启自动回连。
当前状态：HID 报文通道已在 H96 Max V56 上实测打通（见变更记录），
注册、报告描述符（键盘 Report 1 + 消费者控制 Report 2）、设备选择、
连接状态回调、回前台重注册与记忆设备均已实现（`BtHid.java`）。

本 spec 的目标：把蓝牙模式从「实验性」变为「可用」，并明确在什么情况下放弃它。

## 功能需求（FR）

- FR-5-1 注册：App 获得默认蓝牙适配器的 `BluetoothHidDevice` profile 代理，
  以报告描述符注册 SDP 记录，注册结果回调到 UI。
- FR-5-2 连接：从已配对设备列表选择盒子发起连接；连接/断开状态实时显示；
  断开后退出蓝牙模式并提示，不再回退到其他模式（ADB 已移除）。
- FR-5-3 按键映射：手机 keycode → HID usage（键盘页）与消费者控制页：
  19→0x52, 20→0x51, 21→0x50, 22→0x4F, 23→0x28(ENTER)；
  4→消费者页 `AC Back 0x0224`，3→消费者页 `AC Home 0x0223`
  （键盘页 Esc/Home 在手机受控端不触发系统返回/主页）；
  26→消费者页 0x30(power)；24/25/164 走消费者控制报告。
- FR-5-4 报文发送：按 Report ID 组装 report，经 `sendReport` 下发；
  键盘按键按下 + 35ms 后抬起，消费者控制同理。
- FR-5-5 后台保活：App 退后台会被系统注销 HID 注册，需前台 Service 或
  明确的用户引导（保持前台使用）。
- FR-5-6 支持性检测：API < 28 或适配器不支持 HID device profile 时，
  弹窗说明并引导使用直连或 2.4G 飞鼠遥控器。
- FR-5-7 回前台自动恢复：蓝牙模式激活时，`onResume` 检测 HID 未注册
  则重新 `start()` 注册，成功后自动连接上次使用的盒子。
- FR-5-8 记忆蓝牙目标：按设备保存盒子地址（`bt_<host>`，SPEC-015），
  HID 重新注册后优先自动连接当前设备记住的盒子，不再重复弹选择框。
- FR-5-9 可发现配对入口：没有已配对设备时，除「去蓝牙设置」外提供
  「让盒子搜索这台手机」按钮，发起系统可发现请求 120 秒，提示在盒子
  蓝牙设置里选择本机。

## 非功能需求（NFR）

- NFR-5-1 按键端到端延迟（点击→盒子响应）与人手感知一致（< 150ms）。
- NFR-5-2 断开重连：蓝牙链路闪断后回前台 3 秒内自动恢复注册与连接。
- NFR-5-3 不申请定位权限完成蓝牙设备枚举（API 28–30 范围验证）。

## 验收标准

- [x] AC-1 三星 Note20 与目标盒子完成蓝牙配对后，App 内选择设备显示「蓝牙已连接」。 【手工】
  结果：H96 Max V56 与三星 Fold3 均显示「蓝牙已连接 · <盒子名>」。
- [x] AC-2 十字键/OK/返回/主页/菜单/音量/电源在盒子上有对应反应。 【手工】
  结果：方向键/OK/音量在 H96、Fold3 生效；返回/主页改消费者控制后在
  H96（返回→当贝桌面、主页→当贝桌面）与 Fold3（返回→当前视频 App、
  主页→当贝桌面）均生效。
- [ ] AC-3 App 切后台再回前台，HID 注册自动恢复并自动连回上次盒子，按键继续有效。 【手工】
  结果：进程被杀后重开 App 自动恢复注册并回连 H96/Fold3（`onAppStatusChanged
  registered=true` → 目标设备 state 2）；切后台回前台重注册路径尚未单独执行。
- [ ] AC-4 关闭盒子蓝牙，手机端 3 秒内显示「蓝牙已断开」并退出蓝牙模式；重新打开后点「蓝牙」可再次连接。 【手工】
- [ ] AC-5 在不支持 HID device profile 的手机上，弹窗给出直连/2.4G 替代方案提示。 【手工】
- [ ] AC-6 没有已配对设备时点「蓝牙」，能用「让盒子搜索这台手机」引导完成配对。 【手工】
  备注：Note20 ↔ Fold3 实际是在系统蓝牙设置里完成配对；App 内可发现入口未单独执行。

## 边界与已知限制

- 系统限制：App 退后台被注销 HID 注册（文档明确），无法完全规避，只能保活或引导。
- 若 FR-5-4 经验证仍无法打通（报告格式/SDP 记录不被盒子接受），本 spec 转
  discarded 并把结论写入变更记录，避免无限投入。
- 蓝牙模式与直连模式互斥（进入其一退出另一）。

## 实现映射

- HID 注册/报文：`app/src/com/koco/tvremote/BtHid.java`
  - 报告描述符（键盘 Report 1 + 消费者控制 Report 2）、`register()`、`sendKey()`、`sendConsumer()`
  - 消费者控制常量 `C_VOL_UP/DOWN/MUTE`、`C_HOME=0x0223`、`C_BACK=0x0224`
  - `disconnect()` / `stop()` 先清 `target`，切设备后不误发旧盒子（SPEC-015）
- 手机端交互：`app/src/com/koco/tvremote/MainActivity.java`
  - `startBt()`、`onRegistered()`、`onConnectionChanged()`、`connectSavedBtOrPick()`、
    `showBtDevicePicker()`、`requestDiscoverable()`、`onResume()` 重注册
- 协议对照：直连模式下同一 keycode 的处理见 SPEC-014（盒端无障碍）。

## 测试映射

- 手工：tests/manual/SPEC-005-bt-hid.md（已按 TDD 先行写好，执行前标记「待实现」）
