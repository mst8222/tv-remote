# SPEC-001 局域网直连遥控（核心）

## 元信息
- 状态：implemented
- 创建：2026-10-21
- 更新：2026-10-21
- 优先级：high

## 变更记录
- 2026-10-21 创建：从 v1.0 已验证实现提炼成文
- 2026-10-21 按自动测试结果修正三处描述：未知命令/空行实际返回 `ERR auth`；
  空格逐个转 `%s`；TEXT 空文本返回 `ERR arg`。并依据 FR-1-18 修复
  LanClient 异常后不关连接的缺陷（见 AC-9）。

## 背景与目标

盒子（RK3566 / Android 12 userdebug / 已 root）与手机同处局域网时，手机直连盒子端
常驻服务完成遥控。**不依赖开发者选项与无线调试**，盒子重启后自动恢复，是日常遥控的
主力模式（区别于 SPEC-002 的 ADB 模式）。

## 功能需求（FR）

### 盒子端（com.koco.tvremoteserver）

- FR-1-1 常驻服务：前台 Service（START_STICKY），启动后监听 TCP 8899。
- FR-1-2 开机自启：BootReceiver 收到 BOOT_COMPLETED 后自动拉起服务，无需人工操作。
- FR-1-3 配对码：首次启动生成 6 位数字配对码（`%06d`，允许前导 0），持久化保存，
  重启不变；同一时刻在盒子端界面可见。
- FR-1-4 token：首次启动同时生成 16 字节随机 token（32 位小写 hex），持久化保存；
  重启不变。配对成功返回的就是该 token，多次配对返回同一 token。
- FR-1-5 TCP 行协议（每行一条，`\n` 结尾，服务端对行做 trim）：

| 客户端发送 | 服务端响应 | 说明 |
|---|---|---|
| `PING` | `PONG` | 探活，无需认证 |
| `PAIR <6位配对码>` | `OK <token>` / `ERR badcode` / `ERR arg` | 配对换 token |
| `KEY <token> <keycode>` | `OK` / `ERR auth` / `ERR arg` / `ERR exec` | 注入按键 |
| `TEXT <token> <base64>` | `OK` / `ERR auth` / `ERR arg` / `ERR <异常名>` | 输入文字 |
| `APP <token> <包名>` | `OK` / `ERR auth` / `ERR arg` / `ERR exec` | 启动应用 |
| 未知命令（带有效 token） | `ERR cmd` | 认证先于命令字识别 |
| 未知命令（无 token）/ 空行 | `ERR auth` | 空行落入认证分支；`ERR empty` 为不可达死代码 |
| 参数异常（如 TEXT 非法 base64） | `ERR <异常类名>` | 如 `ERR IllegalArgumentException` |

> 注意：`KEY`/`TEXT`/`APP` 缺第三段参数返回 `ERR arg`（认证通过后校验参数个数）。

- FR-1-6 认证：除 PING 外，所有命令第二段必须等于当前 token，否则一律 `ERR auth`。
- FR-1-7 按键注入：`su root sh -c "input keyevent <keycode>"`（本机 su 是 toybox 版，
  不支持 `su -c`；必须经 `sh -c`）。命令退出码 0 → `OK`，否则 `ERR exec`。
- FR-1-8 文字注入：base64 解码为 UTF-8 后，空格逐个替换为 `%s`（连续空格替换为
  连续的 `%s`）、删除单引号，再执行 `su root sh -c "input text '<处理后文本>'"`。
  退出码 0 → `OK`。空文本（base64 后为空串）整行缺第三段参数，返回 `ERR arg`。
- FR-1-9 应用启动：包名为 `com.android.settings` 时执行
  `am start -a android.settings.SETTINGS`（该固件 Settings 无 LAUNCHER 入口）；
  其余包名执行 `monkey -p <包名> -c android.intent.category.LAUNCHER 1`。
  退出码 0 → `OK`，否则 `ERR exec`。
- FR-1-10 UDP 发现广播：服务运行期间每 3 秒向 `255.255.255.255:8898` 广播
  `TVREMOTE|8899|<Build.MODEL>`，供手机端自动发现盒子 IP。
- FR-1-11 盒子端界面：显示配对码（大字号）、本机 IP、端口 8899 / 发现端口 8898、
  服务运行状态、root 权限检测结果（`su root sh -c "id"` 输出含 `uid=0`）。
- FR-1-12 连接处理：每个客户端连接独立线程处理（线程池），读超时 30 秒；
  并发多连接均可使用同一 token。

### 手机端（com.koco.tvremote）

- FR-1-13 配对：输入盒子 IP + 6 位配对码，发送 `PAIR`，拿到 token 后持久化保存
  （SharedPreferences `lanHost`/`lanToken`），下次不再要求输入配对码。
- FR-1-14 已配对直连：已保存 host+token 时，点「直连」直接进入直连模式，不再弹配对框。
- FR-1-15 清除配对：长按「直连」按钮清除已保存的 host/token，并重新弹配对框
  （盒子 IP 变化时使用）。
- FR-1-16 自动发现：配对框点「自动发现」，监听 UDP 8898 最多 4.5 秒，收到
  `TVREMOTE|` 前缀的广播即取来源 IP 回填配对框。
- FR-1-17 遥控命令：方向键/OK/返回/主页/菜单/音量±/静音/电源 通过 `KEY` 发送；
  文字通过 `TEXT`（UTF-8 → base64）；应用快捷通过 `APP`。
- FR-1-18 连接管理：未连接时发命令自动重开 TCP 连接；收到非 `OK` 响应抛异常并在
  状态栏提示；任何 IO 异常（写失败、读超时、对端断开、响应为空）都会关闭 socket，
  下一条命令自动重连，不会停留在假连接状态。
- FR-1-19 模式互斥：进入直连模式时退出蓝牙模式（`btMode=false`），状态栏显示
  「直连 · <IP>」。

## 非功能需求（NFR）

- NFR-1-1 响应速度：局域网内单条 `KEY` 命令端到端（发送→OK 响应）不超过 4 秒
  （客户端超时即报错）；正常局域网应在 100ms 量级。
- NFR-1-2 可靠性：盒子重启后服务自动恢复（前台服务 + 开机自启），手机无需重新配对
  （token 与配对码持久化）。
- NFR-1-3 安全性：token 为 128 bit 安全随机数；配对码为安全随机 6 位数字；
  错误配对码不泄露 token。
- NFR-1-4 兼容性：minSdk 26（`java.util.Base64` / `android.util.Base64` 可用）；
  手机端不申请任何额外系统权限即可使用直连功能。
- NFR-1-5 无依赖：协议实现不引入第三方库，纯 `java.net` + 平台 API。
- NFR-1-6 资源：空闲时服务仅维持 1 个 TCP accept 线程 + 1 个 UDP 广播线程，
  广播间隔 3 秒。

## 验收标准

- AC-1 盒子安装「TV遥控服务」并打开后，电视屏幕显示 6 位配对码、本机 IP、
  端口 8899/8898、「服务运行中」与 root 状态。 【手工】
- AC-2 手机输入 IP + 配对码 → 提示「已配对」，状态栏显示直连模式与盒子 IP。 【手工】
- AC-3 配对后按方向键/OK/音量/返回/主页/菜单/静音/电源，电视有对应反应。 【手工】
- AC-4 文字输入框输入中文、英文、含空格文本并发送，电视端焦点输入框出现相同文本
  （单引号除外，见 FR-1-8 的已知处理规则）。 【手工】
- AC-5 点应用快捷按钮，电视启动对应应用；点「设置」能进入系统设置。 【手工】
- AC-6 重启盒子：不操作手机，等待盒子开机进入桌面后，直接按方向键仍能控制。 【手工】
- AC-7 杀掉手机进程后重新打开 App，点「直连」不要求重新配对，直接可用。 【手工】
- AC-8 输错配对码 → 手机提示配对失败，电视端无异常。 【手工】
- AC-9 协议一致性：`tests/protocol/run.ps1` 全部通过（PING/PAIR/KEY/TEXT/APP/
  错误分支/断线重连/UDP 发现/多命令复用等 30+ 用例）。 【自动】
- AC-10 未装盒子端服务时，手机配对/发现失败并给出可理解提示，App 不崩溃。 【手工】

## 边界与已知限制

- keycode 不做白名单校验，任意字符串都会拼入命令（非数字会导致 input 失败 →
  `ERR exec`，不会崩溃）。
- `PAIR` 无速率限制，6 位数字理论上可被局域网内攻击者暴力枚举 → 见 SPEC-006。
- TCP 明文传输，局域网内可被抓包看到 token → 见 SPEC-006。
- 文本中的单引号会被服务端删除（防注入的已知代价）。
- 连续空格经 `%s` 转换后输入效果为多个空格（`input text` 特性）。
- 手机端「自动发现」绑定 UDP 8898，若手机上有其他 App 占用该端口则发现失败。
- 广播为全网段 255.255.255.255，跨网段/路由器 AP 隔离下不可达。
- 服务端读超时 30 秒后连接由 `readLine` 抛异常关闭，客户端下次命令自动重连。

## 实现映射

| 需求 | 文件 |
|---|---|
| FR-1-1/5/6/7/8/9/10/12 | server/src/com/koco/tvremoteserver/ServerService.java |
| FR-1-2 | server/src/com/koco/tvremoteserver/BootReceiver.java |
| FR-1-3/4/11 | ServerService.java + server/.../MainActivity.java |
| FR-1-13/14/15/16/18/19 | app/src/com/koco/tvremote/LanClient.java + MainActivity.java（直连部分） |
| FR-1-17 | MainActivity.java（press/doText/doApp 直连分支） |

## 测试映射

- 自动：tests/protocol/ProtocolTest.java（MockBoxServer 为协议参照实现）+ run.ps1
- 手工：tests/manual/SPEC-001-lan-direct.md
