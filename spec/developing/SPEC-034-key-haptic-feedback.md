# SPEC-034 遥控按键震动反馈

## 元信息

- 状态：developing
- 创建：2026-10-24
- 更新：2026-10-28（补齐已安装 PWA 下不震动的场景，待真机手工验收）
- 优先级：medium

## 变更记录

- 2026-10-24 创建并开工：用户要求在手机端 App 与盒端网页遥控页的按键上加入震动反馈，
  点击时短震以提升手感；确认盒端 Android App 界面（电视无马达）不参与。
- 2026-10-24 实现完成：安卓端新增 `VIBRATE` 权限与 `haptic()`（15ms 单次），
  覆盖 `press()`（四向/系统键/音量/静音）、OK 按下、飞鼠开关、应用图标、发送按钮；
  网页端新增 `haptic()`（12ms），覆盖 `data-key` 按键、OK pointerdown、应用图标、发送按钮，
  并提升 Service Worker 缓存版本至 `tvrc-shell-v6`。双端 APK 编译通过，
  VIBRATE 权限已入包，APK 内 `app.js` / `sw.js` 已确认为新版本；真机震动效果待手工验收。
- 2026-10-24 发布：双端版本升至 1.7.0（versionCode 14），代码与 README 已同步
  Gitea、Gitee、GitHub 三个仓库，并在三个仓库发布 v1.7.0 Release（附双端 APK）；
  真机震动效果仍待手工验收。
- 2026-10-28 修正：用户实测「PWA 添加到桌面后点击按钮没有震动」。判断桌面模式
  （display-mode: standalone）下部分浏览器在 click 阶段已不把振动视为即时用户手势，
  改为 `pointerdown` 触发震动、click 只发命令；首次触摸先 `vibrate(1)` 解锁再
  `vibrate(12)`。新增 `bindHaptic()` 统一绑定，覆盖按键、应用图标、发送按钮与
  方向盘/触摸/鼠标分段切换。缓存版本提升至 `tvrc-shell-v8`。
  同时在盒端「连接地址」卡二维码下方补充 iOS / 鸿蒙 / 安卓的添加到桌面步骤，
  并写明 iOS Safari 不支持网页震动、鸿蒙与安卓需开启系统「触摸振动」。
- 2026-10-28 部署验证：荐片 RK3566（192.168.3.125）安装 v1.7.1（versionCode 15），
  `http://192.168.3.125:17880/app.js` 含 `hapticOn`/`bindHaptic`、`sw.js` 为
  `tvrc-shell-v8`、首页含 `#iosHint`；`node --check` 语法通过。
  安卓 Chrome 下桌面 PWA 的实际振感仍需用户在手机上手感确认（AC-9）。

## 背景与目标

当前手机端 App 与盒端网页遥控页的按键只有视觉涟漪反馈，点击时没有触觉反馈。
在手持手机操作遥控器时，短震动可以让每次按键都有明确"已触发"的手感，减少误以为没按到的重复点击。

目标：两端遥控主键按下即产生一次短震动；不改变按键功能、布局与协议。

## 功能需求（FR）

- FR-34-1 安卓手机端 App：方向盘四向键、OK 键、返回/主页/菜单/电源键、音量减/加/静音键按下时各震动一次。
- FR-34-2 安卓手机端 App：飞鼠开关、应用图标、文字发送按钮点击时各震动一次。
- FR-34-3 安卓手机端 App：OK 长按只在按下瞬间震动一次，长按触发时不再补震。
- FR-34-4 盒端网页遥控页：`data-key` 按键（方向、系统键、电源、音量、静音）、OK 键、应用图标、发送按钮点击时各短震一次。
- FR-34-5 两端震动均为单次短震（安卓 15ms、Web 12ms），不改变原有按键功能与发送逻辑。
- FR-34-6 无振动马达的设备、以及不支持振动 API 的浏览器，按键功能保持正常且不报错。
- FR-34-7 网页端震动在 `pointerdown`（无 PointerEvent 时 `touchstart`）触发，click 只发命令；
  已添加到桌面（standalone）打开时同样有震动，首次触摸先以 1ms 振动解锁。

## 非功能需求（NFR）

- NFR-34-1 安卓端仅新增 `android.permission.VIBRATE` 权限，不引入其他权限或服务。
- NFR-34-2 最低 API 26，使用 `VibrationEffect.createOneShot`，不做版本分支。
- NFR-34-3 震动在按键回调内同步触发，不阻塞协议发送与 UI 线程。
- NFR-34-4 新增 spec 编号不复用；网页静态外壳升级需提升 Service Worker 缓存版本。

## 验收标准

- [ ] AC-1 安卓端四向键、OK、返回/主页/菜单/电源、音量±/静音每次按下均短震一次。
- [ ] AC-2 安卓端飞鼠开关、应用图标、发送按钮点击均短震一次。
- [ ] AC-3 安卓端长按 OK 只震一次，不出现连续震动。
- [ ] AC-4 安卓端直连与蓝牙两种模式按键均有震动，功能与改动前一致。
- [ ] AC-5 网页端方向/系统键/音量/静音、OK、应用图标、发送按钮点击均短震一次。
- [ ] AC-6 网页端长按 OK 只震一次。
- [ ] AC-7 不支持振动 API 的浏览器（如 iOS Safari）页面无报错，按键功能正常。
- [ ] AC-8 已安装 PWA 的设备在升级盒端 APK 后加载到新 `app.js`（缓存版本 `tvrc-shell-v8`）。
- [ ] AC-9 已添加到桌面的 PWA（standalone）打开后，点击方向键 / OK / 应用图标 / 发送按钮均有短震。

## 边界与已知限制

- 电视盒子通常没有振动马达，盒端 Android App 界面不做震动。
- iOS Safari 不支持 `navigator.vibrate`，网页震动静默跳过，不做替代弹窗提示；
  该限制与「添加到桌面」的步骤一起写在盒端二维码下方的说明里。
- 鸿蒙与安卓浏览器依赖系统振动能力：系统关闭「触摸振动」时网页同样无振感，页面无法读取该设置。
- 不提供震动开关，不读取系统触感设置，不区分短按/长按强度。
- 触控板、设备管理弹窗、更新检查等其它交互不加震动。

## 实现映射（implemented 后填写）

- `app/AndroidManifest.xml`：新增 `android.permission.VIBRATE`（NFR-34-1）。
- `app/src/com/koco/tvremote/MainActivity.java`：字段 `vibrator` 与 `haptic()`；
  `press()` 覆盖四向/系统键/音量/静音（FR-34-1），OK 触摸 ACTION_DOWN（FR-34-1/3），
  `buildAirKey()` / `appCell()` / 发送按钮（FR-34-2）。
- `server/assets/web/app.js`：`haptic()`（首次 1ms 解锁 + 12ms）与 `bindHaptic()`
  接入 `bindKeys()`、`bindOk()` pointerdown、应用图标、发送按钮、分段切换（FR-34-4/5/6/7）。
- `server/assets/web/sw.js`：缓存名提升为 `tvrc-shell-v8`（NFR-34-4）。
- `server/res/values/strings.xml`、`values-en/strings.xml`：`ui_home_tip` 记录
  iOS / 鸿蒙 / 安卓添加到桌面的步骤与震动限制说明。

## 测试映射

- 手工清单：`tests/manual/SPEC-034-key-haptic.md`。
- 无协议改动，不新增 `tests/protocol/` 用例。
