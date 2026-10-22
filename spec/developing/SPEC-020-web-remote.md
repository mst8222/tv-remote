# SPEC-020 盒端内置 Web 遥控页（PWA）

## 元信息

- 状态：developing
- 创建：2026-10-22
- 更新：2026-10-22（Web 端口改为 17880/17843，避开常见端口；证书改用 legacy PKCS12）
- 优先级：high

## 变更记录

- 2026-10-22 创建：确定采用「Web 核心」路线。盒端新增 HTTP/HTTPS（自签证书）
  服务并内嵌静态页，覆盖 Android、iOS、HarmonyOS NEXT 与 PC 浏览器，要求支持 PWA；
  手机端不安装任何东西。飞鼠、蓝牙 HID、自动发现明确不在本期范围。
- 2026-10-22 开工：命令处理器签名由 `String -> String` 调整为
  `(String line, String peerIp) -> String`，使 SPEC-006 的 PAIR 按 IP 锁定在
  TCP 与 Web 两条入口语义一致。
- 2026-10-22 实现：WebServer、静态页、PWA、证书生成与打包完成；
  Web API 38 项与协议 92 项全绿，本地 Chromium 联调通过。
- 2026-10-22 扫码自动配对：配合 SPEC-024，页面识别 `#pair=` 并自动提交配对，
  成功后清除 hash；手动配对流程不变（FR-20-12）。
- 2026-10-22 端口调整：用户要求避开 8080/8443 等常见端口，避免被其它应用占用。
  HTTP 改为 17880、HTTPS 改为 17843；盒端地址卡/二维码、手机端网页地址文案、
  网页手动添加设备的默认端口同步更新。自动测试使用系统分配的临时端口，不受影响。
- 2026-10-22 证书兼容修复：改端口后 HTTPS 17843 未监听。盒端日志
  `web tls unavailable: error constructing MAC: ... No installed provider supports
  this key: PKCS12Key`，根因是 JDK 21 的 keytool 默认生成 AES/HmacPBESHA256 的
  PKCS12，Android BouncyCastle 无法解析。`build.py` 生成证书时加
  `-J-Dkeystore.pkcs12.legacy` 并删除旧 `keys/web.p12` 重建；
  真机复测 17843 已监听，TLS 1.3 握手成功，`GET /` 返回 200，旧 8080/8443 均不可达。

## 背景与目标

手机端目前只有 Android 原生实现（直连 + 蓝牙 HID）。iOS 没有 HID 设备接口，
HarmonyOS NEXT 不能安装 APK，两条路径都无法覆盖。经过方案对比，选择「手机端零安装」：
由盒子端提供网页，任何带浏览器的设备打开即用。

目标：

1. 一份页面同时覆盖 Android、iOS、HarmonyOS NEXT、PC 浏览器；
2. 功能与现有直连协议一一对应，不复制协议逻辑，不产生第二套行为；
3. 以 PWA 方式提供桌面图标与离线外壳，减少「每次输地址」的摩擦；
4. 改动集中在盒端，Android 手机端与蓝牙模式保持现状。

## 功能需求（FR）

- FR-20-1 盒端新增 Web 服务，与 `ServerService` 同生命周期（`onCreate` 启动、
  `onDestroy` 停止）：HTTP 监听 17880，HTTPS 监听 17843，二者共用同一路由与资源根。
- FR-20-2 静态资源内嵌于 `server/assets/web/`，随 `TVRemoteServer.apk` 发布；
  页面不引用任何外部 CDN、字体或公网资源，完全离线可用。
- FR-20-3 静态路由：`GET /` 返回 `index.html`；其余静态文件按路径白名单读取；
  未知路径返回 404；拒绝 `..`、绝对路径、编码变体造成的路径穿越。
- FR-20-4 API 与现有 TCP 命令一一对应，语义（含错误码）完全沿用：

  | 方法与路径 | 请求体 | 等价 TCP 命令 | 成功响应 |
  | --- | --- | --- | --- |
  | `POST /api/pair` | `{"code":"123456"}` | `PAIR <code>` | `{"ok":true,"token":"..."}` |
  | `POST /api/key` | `{"code":4}` | `KEY <token> <code>` | `{"ok":true}` |
  | `POST /api/keylong` | `{"code":23}` | `KEYLONG <token> <code>` | `{"ok":true}` |
  | `POST /api/text` | `{"text":"..."}` | `TEXT <token> <base64>` | `{"ok":true}` |
  | `POST /api/app` | `{"pkg":"..."}` | `APP <token> <pkg>` | `{"ok":true}` |
  | `GET /api/apps` | 无 | `LIST <token>` | `{"ok":true,"apps":[{"pkg":"...","name":"..."}]}` |
  | `GET /api/icon?pkg=...` | 无 | `ICON <token> <pkg>` | `image/png`（144x144） |
  | `GET /api/ping` | 无 | `PING` | `{"ok":true}` |

  - 除 `/api/pair`、`/api/ping` 与静态资源外，所有请求必须带 `X-Token` 头；
    缺失或错误返回 HTTP 401 + `{"ok":false,"err":"auth"}`。
  - 其余协议错误返回 HTTP 200 + `{"ok":false,"err":"<原样错误码>"}`，
    `err` 取值与 TCP 一致（`arg` / `a11y` / `cmd` / `exec` / `badcode`）。
  - `/api/text` 由服务端把 UTF-8 文本编码为 Base64 后交给协议层；
    `/api/apps` 由服务端解码 `LIST` 载荷并输出 JSON；`/api/icon` 由服务端把
    `ICON` 的 Base64 解码为 PNG 字节流。
- FR-20-5 复用协议入口：Web 层不得复制命令解析。`WebServer` 为纯 Java 类
  （不依赖 Android API），构造时注入 `(line, peerIp) -> response` 的命令处理器；
  运行时注入 `ServerService` 的 dispatch 适配器，测试时注入 MockBoxServer
  同语义处理器。
- FR-20-6 页面功能与手机端对齐：配对（6 位码，token 存 `localStorage`）、
  方向键、OK（含长按发送 `KEYLONG`）、返回、主页、菜单、音量加/减、静音、电源、
  文本输入、应用卡（图标 + 完整名称，点击启动）、连接状态提示。
  键值与手机端 `KeyConst` 保持一致。
- FR-20-7 PWA：提供 `manifest.webmanifest`（`display: standalone`、
  `start_url: "/"`、`scope: "/"`、192/512 图标、主题色与背景色）、
  `sw.js` 与 iOS 主屏 meta 标签；Service Worker 缓存应用外壳（HTML/CSS/JS/图标），
  `/api/*` 一律不缓存；离线打开时显示「未连接到盒子」提示。
  Service Worker 仅在 `isSecureContext` 时注册；HTTP 页面照常可用，
  但显示一行「使用 HTTPS 地址可获得安装与离线能力」的提示，不阻断操作。
- FR-20-8 页面中英双语，按 `navigator.languages` 选择，文案独立维护，
  语义与手机端一致（不共享 Android 资源文件）。
- FR-20-9 盒端 `MainActivity` 地址卡同时显示 Web 访问地址：
  `http://<ip>:17880` 与 `https://<ip>:17843`，供其他设备手动输入。
- FR-20-10 自签证书：构建时若 `keys/web.p12` 不存在则生成，存在则复用；
  `build.py server` 把证书复制进 `server/assets/web/` 并随 APK 打包；
  HTTPS 服务使用该证书。生成时必须使用 legacy PKCS12 算法
  （keytool 加 `-J-Dkeystore.pkcs12.legacy`），否则 Android BouncyCastle 无法解析，
  HTTPS 端口不会监听。证书仅显式更换时才变化，更换须在发布说明中注明。
- FR-20-11 `build.py` 增加静态资源打包：`aapt2 link` 使用 `-A server/assets`
  把 `assets/web/` 打进 APK；缺失该目录时报错退出，不生成「无页面」的 APK。
- FR-20-12 页面加载时若 URL hash 带 `#pair=<6位码>`（SPEC-024 二维码地址），
  自动填入并提交配对；成功后清除 hash 进入遥控界面，失败保留配对卡与提示。
  手动输入配对码的原有流程保持不变。

## 非功能需求（NFR）

- NFR-20-1 静态资源（HTML/CSS/JS/manifest/两个图标）未压缩总计不超过 300KB；
  局域网首屏加载不超过 1.5 秒（不含首次证书确认）。
- NFR-20-2 Web 服务使用独立线程与线程池，异常只记录日志，不得导致
  `ServerService` 退出或影响 8899 TCP / 8898 广播。
- NFR-20-3 单请求处理超时与现有协议一致：KEY 4 秒、TEXT 5 秒、APP/LIST/ICON 6 秒；
  页面请求超时略大于服务端。
- NFR-20-4 安全：请求体上限 64KB；静态资源只读白名单；日志不得打印 token。
  跨源头由 SPEC-025 调整：为支持网页多设备切换，所有响应返回
  `Access-Control-Allow-Origin: *` 并处理 OPTIONS 预检，鉴权仍由 token 与配对锁定保证。
- NFR-20-5 兼容：Safari 15+、Chrome 100+、ArkWeb（M114 及以上）、Edge；
  页面从 320px 宽起可用，手机竖屏为主，横屏与桌面浏览器内容居中且最大宽度 480px。
- NFR-20-6 每个 API 请求独立完成，不依赖长连接或会话状态（配对状态仅由 token 体现）。
- NFR-20-7 页面不写入、不上传任何数据到公网；不申请定位、通知等额外权限。

## 验收标准（AC）

- AC-1 `python build.py server` 成功；APK 内 `assets/web/` 含
  `index.html`、`manifest.webmanifest`、`sw.js`、两个图标与 `web.p12`。
- AC-2 盒子启动服务后，同一局域网内 `http://<ip>:17880/` 与
  `https://<ip>:17843/` 均可打开页面；盒端地址卡显示这两个地址。
- AC-3 页面输入盒端显示的 6 位配对码可完成配对，刷新页面仍保持已配对；
  输入错误码提示「配对码错误」，不写入 token。
- AC-4 无障碍已开时，方向、OK、返回、主页、菜单、音量加/减、静音、电源
  均能在 H96 上生效；关闭无障碍后操作提示「无障碍服务未开启」。
- AC-5 文本输入的中英文可落到盒子当前焦点输入框；应用卡列出盒子全部第三方应用、
  显示真实图标，点击可启动。
- AC-6 长按 OK 发送 `KEYLONG`，触发盒子端长按行为（如桌面图标菜单）。
- AC-7 同一页面在 iPhone Safari、HarmonyOS NEXT 浏览器、Android Chrome、
  PC Chrome/Edge 上均可完成配对与按键，页面不横向溢出、不遮挡操作区。
- AC-8 信任盒端证书后：HTTPS 页面 Service Worker 注册成功，PC/Android Chrome
  可出现安装入口；断网打开已安装应用显示离线提示而不是白屏。
- AC-9 未信任证书时：浏览器跳过警告后页面仍可完成配对与按键；
  iOS Safari「添加到主屏幕」后以独立窗口打开；此时 Service Worker 是否可用
  按实测记录（预期受浏览器策略限制）。
- AC-10 安全与异常：无 token 请求 `/api/key` 返回 401 + `err=auth`；
  `/../` 及编码变体返回 404；`/api/*` 响应不携带可缓存头；
  盒子停服后页面操作提示「未连接到盒子」而非卡死。
- AC-11 `powershell -File tests\protocol\run.ps1` 全部通过（含新增 Web API 用例）。

## 边界与已知限制

- 不含飞鼠：飞鼠由 `SPEC-021` 独立定义（新增 `MOVE`/`MCLICK` 与盒端应用层光标），
  本 spec 只保证页面在无飞鼠能力时可用。
- 不含蓝牙模式：浏览器没有 HID 设备接口，页面只走局域网；
  蓝牙 HID 的跨端边界与降级由 `SPEC-022` 定义。
- 不做自动发现：浏览器收不到盒端 UDP 广播，首次必须手动输入盒端显示的地址；
  Android 手机端仍保留原有发现能力。
- 自签证书由所有盒子共用并随公开 APK 发布，TLS 的作用是提供安全上下文与链路加密，
  不构成对同网段攻击者的身份认证；控制权限始终由 token 保证。
- `keys/web.p12` 存在即复用：旧版本 JDK 生成的 AES/HmacPBESHA256 证书不会被自动
  替换，升级构建环境后须手动删除该文件再执行 `python build.py server` 重新生成，
  否则 HTTPS 端口静默不监听（HTTP 不受影响）。
- 各浏览器对自签证书的信任策略不同：不信任时 Chrome 不提供安装入口，
  Service Worker 可能被禁用；iOS Safari 仍可添加到主屏幕（无离线缓存）。
  是否信任证书由用户决定，本 spec 两条路径都验收。
- 页面只在盒子端服务在线时可用；PWA 离线态仅显示提示，不缓存可执行操作。
- 单页面只连接一个盒子（地址可修改），不做多盒子切换列表。
- 不做应用图标的持久离线缓存，仅依赖浏览器 HTTP 缓存。
- 不引入前端框架与构建工具，保持纯静态 HTML/CSS/JS，随 APK 打包。
- 本 spec 自身不提供二维码；盒端二维码与扫码自动配对由 SPEC-024 实现
  （地址 hash 带 `#pair=<码>`，见 FR-20-12）。

## 实现映射

- `server/src/com/koco/tvremoteserver/WebServer.java`：纯 Java HTTP/HTTPS 服务，
  注入 `(line, peerIp)` 命令处理器与静态资源提供者；API 映射、token→401、
  错误码透出、64KB 上限、静态白名单与 no-store。
- `server/src/com/koco/tvremoteserver/ServerService.java`：`startWeb()` 随服务启停，
  `readAsset` 读取 `assets/web`，`buildTls` 从 `web.p12` 构建 TLS。
- `server/src/com/koco/tvremoteserver/MainActivity.java` + `strings.xml`：
  地址卡同时显示 17880 / 17843 地址。
- `server/assets/web/`：`index.html`、`style.css`、`app.js`、
  `manifest.webmanifest`、`sw.js`、192/512 图标；`app.js` 含 hash 自动配对
  （FR-20-12）。
- `build.py`：`web_assets_dir()` 校验静态文件、生成/复用 `keys/web.p12`，
  `aapt2 link -A server/assets` 打包。
- `tests/protocol/WebApiTest.java`：静态、穿越、鉴权、各命令、图标、请求体上限。

## 测试映射

- 自动：`tests/protocol/WebApiTest.java`（新增），被测对象为纯 Java 的
  `WebServer` + `MockBoxServer`，`run.ps1` 增加对应源文件；覆盖配对、
  token 校验、各命令错误码透出、路径穿越、缓存头与请求体上限。
- 手工：`tests/manual/SPEC-020-web-remote.md`（Android / iOS / HarmonyOS NEXT /
  PC 真机与浏览器逐条验收）。
