# SPEC-039 盒端 ADB 自连注入（shell 权限真实按键）

## 元信息
- 状态：developing
- 创建：2026-10-28
- 更新：2026-10-28
- 优先级：high

## 变更记录
- 2026-10-28 创建：用户在荐片 RK3566 上实测云视听小电视（Bilibili TV）场景，
  直连模式上下方向键切换影片、退出弹窗确定键均无效。根因是无障碍注入不产生真实
  KeyEvent：方向键走 GLOBAL_ACTION_DPAD_*（固件可阉割，H96 实测返回 false）或
  无障碍焦点移动（对自绘选中态的 TV 播放器 UI 无效）；云视听退出弹窗不暴露节点树
  （SPEC-032 已知），回退全局 DPAD_CENTER 同样依赖固件。用户明确要求：除 Root 外
  可使用开发者模式权限，盒端 UI 提供对应权限开关入口，目标是达到蓝牙 HID 的控制效果。
- 2026-10-28 转入 developing 并完成实现：AdbClient（自连 adbd）+ AdbShell（注入通道
  + 权限自授予）+ 协议分发分支 + 盒端 UI 两行开关。荐片 RK3566（= H96，192.168.3.125）
  实测：注入模式切换、ADB 连接、方向键/确定键生效（详见实现映射）。剩余 AC-4（API 22
  实机）与 AC-5（ADB 关闭态）待手工验收。

## 背景与目标
蓝牙 HID 模式下按键是系统级真实输入事件，任意 TV 应用（含云视听）都按物理遥控器响应；
直连模式的无障碍注入在 Android 权限模型里先天不足，对「不暴露节点 + 自绘焦点」的
TV 播放器 UI 存在机制性天花板。非 root 且能注入真实输入事件（INJECT_EVENTS）的唯一
途径是 shell 身份：盒端 App 自连本机 adbd，用 `input keyevent/tap/swipe/text` 注入，
与蓝牙 HID 等效（并额外获得真实菜单键 82、ASCII 文本直输等能力）。

目标：盒端新增「ADB 自连」注入模式。用户在开发者选项打开一次调试开关后，
直连遥控在云视听等 TV 应用内达到蓝牙 HID 同等效果；直连协议对手机端/Web 端不变。

## 功能需求（FR）
- FR-039-1 盒端内嵌极简 ADB over TCP 客户端（复活并改造 git 历史 108d8cc^ 的
  `app/src/com/koco/tvremote/AdbClient.java`，先 copy 再改写）：自生成 RSA 密钥对并
  持久化到私有目录，连接 127.0.0.1:5555；`ro.adb.secure=1` 时首次连接触发系统调试
  授权弹窗，用户在电视上确认一次后永久免确认。
- FR-039-2 盒端设置区新增「注入模式」开关：无障碍（默认）/ ADB（shell），选择持久化；
  选 ADB 但连接不可用时状态行红字提示并给出「打开开发者选项」入口，协议命令返回
  `ERR adb`，不静默回退无障碍。
- FR-039-3 ADB 模式下命令映射：
  - `KEY <code>` -> `input keyevent <code>`（含真实菜单键 82，解除 SPEC-014
    用最近任务替代的已知限制）；
  - `KEYLONG 23` -> `input keyevent --longpress 23`；
  - `MTAPN/MLONGN` -> `input tap` / 同点 `input swipe` 长按；
  - `MSWIPEN` -> `input swipe`；
  - `TEXT`：ASCII -> `input text`；非 ASCII 回退无障碍 `ACTION_SET_TEXT`
    （此时无障碍未开返回 `ERR a11y`）；
  - 飞鼠光标系（MOVE/MCLICK/MTAP/MLONG/SCROLL/MHIDE）保持现有无障碍路径。
- FR-039-4 盒端 UI 新增两行可聚焦控件：「注入模式」行（OK 切换）与「ADB 调试」状态行，
  状态行点击行为：已连接 -> 打开开发者选项；有 WRITE_SECURE_SETTINGS -> 直接写入
  `adb_enabled=1` 并后台重连；无权限 -> 跳开发者选项引导用户手动打开。
  跳转目标 `android.settings.APPLICATION_DEVELOPMENT_SETTINGS`，缺失时退回设置首页。
- FR-039-5 连接管理：命令触发时惰性连接，失败后 5 秒冷却自动重试一次；adbd 重启/断线后
  的下一条命令触发重连；重连仍失败返回 `ERR adb`。服务销毁时关闭连接。
- FR-039-6 首次连接成功后由 shell 执行 `pm grant <包名> WRITE_SECURE_SETTINGS`
  （一次，结果持久化），使盒端 ADB 开关日后可自行打开，不必每次进开发者选项。
- FR-039-7 引导文案：说明「开发者选项 → 打开 USB 调试 / 网络调试（ADB 调试）」，
  不同固件开关名称差异在手工清单中记录实测路径。

## 非功能需求（NFR）
- NFR-039-1 直连协议与 Web API 的命令集合不变，仅新增 `ERR adb` 一种失败返回；
  手机端与 Web 端零改动。
- NFR-039-2 盒端兼容 API 22（SPEC-035）：不使用 `java.util.Base64`（用
  `android.util.Base64`），不使用 API 23+ 独有接口（权限检查用
  `PackageManager.checkPermission`）。
- NFR-039-3 ADB 连接与命令执行在协议分发线程内串行（类内加锁），单条命令注入超时 3 秒，
  连接超时 2.5 秒；首次授权等待 60 秒。
- NFR-039-4 密钥仅存应用私有目录 `files/adbkey.pk8`；不引入第三方依赖。
- NFR-039-5 Android 11+ 无线调试 TLS 配对（SPAKE2 + mDNS 发现）为二期范围；
  本期仅支持固件自带 ADB / 网络调试开关（5555 常开）的场景，遇 A_STLS 直接报错。

## 验收标准
- AC-1 荐片 RK3566 打开 ADB 调试后，盒端切到 ADB 模式，首次连接电视弹出调试授权框，
  确认后连接成功；杀掉服务重进免确认。
- AC-2 云视听小电视内：方向键上下切换影片条目有真实焦点移动；播放页退出弹窗按 OK
  命中「确定」退出（与蓝牙 HID 对照组行为一致）。
- AC-3 当贝桌面 / 系统设置内方向键、OK、返回、主页、菜单（82 为真实菜单键）行为正常。
- AC-4 Redmi 电视（MiTV-ANTL0，API 22）打开 ADB 调试后 ADB 模式同样可用，
  无 `NoClassDefFoundError`。
- AC-5 ADB 调试关闭状态下选 ADB 模式：命令返回 `ERR adb`，盒端状态行红字提示；
  切回无障碍模式后功能恢复。
- AC-6 `powershell -File tests\protocol\run.ps1` 回归 0 失败。
- AC-7 盒端 UI 可见注入模式与 ADB 状态，点击状态行可跳开发者选项。

## 边界与已知限制
- 需要用户在系统开发者选项中手动打开一次调试开关（Android 安全模型，
  非 root 应用无法代开）；开启后本应用可自授予 WRITE_SECURE_SETTINGS，之后可自行开关。
- 本期仅覆盖固件提供 ADB/网络调试开关的设备；无开关且 Android 11+ 的设备留待
  二期无线调试配对。
- ADB 模式下飞鼠光标（MOVE/MCLICK）仍走无障碍覆盖层，触控板完整体验需无障碍保持开启。
- `input text` 仅支持 ASCII；中文输入回退无障碍 SET_TEXT。
- 云视听等应用使用安全表面（FLAG_SECURE 类），`screencap` 抓不到画面，
  验收只能靠 `dumpsys window` 焦点窗口与 logcat 判断。
- 荐片 RK3566 的 adbd 对本地回环连接免确认（实测直接连上，未弹授权框）；
  其它固件仍按标准流程弹框。

## 实现映射
- `server/src/com/koco/tvremoteserver/AdbClient.java`：ADB over TCP 客户端（握手、
  AUTH SIGNATURE / AUTH RSAPUBLICKEY、按流 id 过滤输出的 exec）
- `server/src/com/koco/tvremoteserver/AdbShell.java`：注入通道（key/keyLong/text/
  tap/longPress/swipe）、模式持久化（`srv.inject_adb`）、失败冷却、权限自授予
  （`adb_sec_granted`）、`wm size` 坐标换算
- `server/src/com/koco/tvremoteserver/ServerService.java`：dispatch 中 KEY/KEYLONG/
  TEXT/MTAPN/MLONGN/MSWIPEN 的 ADB 分支与 `ERR adb` 映射；onDestroy 关闭连接
- `server/src/com/koco/tvremoteserver/MainActivity.java`：注入模式行、ADB 状态行、
  后台探测、开发者选项跳转
- `server/AndroidManifest.xml`：声明 WRITE_SECURE_SETTINGS
- `server/res/values/strings.xml`、`values-en/strings.xml`：中英文文案与使用提示
- `server/assets/web/{index.html,app.js,sw.js}`：iOS Safari「添加到主屏幕」引导
  （与 ADB 注入无关，同批交付）

实测记录（2026-10-28，荐片 RK3566 / H96，192.168.3.125，Android 12）：
- 注入模式切换：无障碍 -> ADB -> 无障碍 -> ADB 均生效，模式持久化。
  注意：盒端界面用 PC adb 的 `input keyevent` 导航；a11y 模式下同一界面
  KEY 20 + KEY 23 也能切模式（本机 a11y 焦点遍历对自研控件有效）。
- ADB 连接：`sys.boot_completed` 后自连 127.0.0.1:5555 成功且无需电视确认；
  `dumpsys package` 显示 `WRITE_SECURE_SETTINGS: granted=true`（自授予成功）。
- 真实按键：系统设置页 KEY 20 连续两次均触发列表滚动（截图尺寸 61k -> 71k -> 79k）；
  MTAPN 5000 5000 命中屏幕中心并打开子设置页（坐标换算正确）。
- 云视听小电视 `com.xiaodianshi.tv.yst`：
  - 播放在播时 KEY 20 触发 `E/PlayerKeyEventDelegate: ACTION_DOWN / switchNext /
    ACTION_UP`（应用真实收到并处理按键，无障碍模式下无效）；
  - BACK 三次唤出 `ChannelReturnVisitMultiDialogV2` 退出弹窗，弹窗在场时 KEY 23
    使焦点窗口从云视听切到系统设置（即命中「确定」并退出应用）。
- 无障碍路径回归：a11y 模式下 BACK 仍正常（H96 方向键弱为既有固件限制）。
- 协议回归：235 用例 234 通过，唯一失败为 `discover_no_broadcast_null`
  （真实盒子 UDP 广播撞「无广播」用例，环境抖动，与本次改动无关）。

## 测试映射
- 手工：`tests/manual/SPEC-039-adb-shell-inject.md`
- 自动：`tests/protocol/run.ps1`（回归；MockBoxServer 无 ADB 通道，`ERR adb`
  仅盒端产生，协议命令集合与语义未变）
