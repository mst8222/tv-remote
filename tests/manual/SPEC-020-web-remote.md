# SPEC-020 盒端内置 Web 遥控页（PWA）— 手工验收清单

对应 spec：`spec/developing/SPEC-020-web-remote.md`
执行环境：盒子端 TVRemoteServer 装于 H96 Max V56（192.168.3.125），
浏览器端至少覆盖 Android Chrome、iPhone Safari、HarmonyOS NEXT 浏览器、PC Chrome/Edge。
当前状态：已实现；自动项与本地 Chromium 联调已执行，真机与多浏览器待验收。

| 编号 | 操作 | 预期 | 结果 |
| --- | --- | --- | --- |
| AC-1 | 执行 `python build.py server`，解包 APK 检查 `assets/web/` | 含 `index.html`、`manifest.webmanifest`、`sw.js`、192/512 图标、`web.p12` | P（8 个文件全部打包） |
| AC-2 | 盒子启动服务，PC 访问 `http://192.168.3.125:17880/` 与 `https://192.168.3.125:17843/` | 两个地址均能打开页面；盒端地址卡显示这两个地址 | P（荐片TV RK3566：17880 返回 200；17843 监听正常，TLS 1.3 握手，`GET /` 返回 200；旧 8080/8443 均不可达） |
| AC-3 | 页面输入盒端 6 位配对码，刷新页面；再输入错误配对码 | 正确码配对成功且刷新后保持；错误码提示「配对码错误」且不写入 token | P（本地 Chromium + MockBoxServer：配对、刷新保持均通过） |
| AC-4 | 无障碍开启，依次点击方向、OK、返回、主页、菜单、音量加/减、静音、电源 | H96 对应操作全部生效 | 待执行 |
| AC-5 | 关闭盒子无障碍，重复任一按键；再开启后输入中英文文本、点击应用卡 | 关闭时提示「无障碍服务未开启」；文本落到焦点输入框；应用卡显示真实图标并可启动 | 待执行 |
| AC-6 | 长按页面 OK 键 | 盒子端触发长按行为（如桌面图标菜单） | 待执行 |
| AC-7 | 分别用 iPhone Safari、HarmonyOS NEXT 浏览器、Android Chrome、PC Chrome/Edge 打开同一地址并配对按键 | 均可用；页面不横向溢出、不遮挡操作区；320px 宽下可用 | 部分通过（Chromium 1080px 宽无横向溢出；其余浏览器待执行） |
| AC-8 | 在设备上信任盒端证书后访问 HTTPS：PC/Android Chrome 安装页面为应用；断开盒子电源后打开已安装应用 | 出现安装入口；Service Worker 注册成功；离线显示「未连接到盒子」而非白屏 | 待执行 |
| AC-9 | 不信任证书：iPhone Safari 跳过警告后配对按键；「添加到主屏幕」后打开 | 页面可用、独立窗口打开；记录该浏览器下 Service Worker 是否可用 | 待执行 |
| AC-10 | 无 token 请求 `/api/key`；请求 `/../etc/passwd` 及编码变体；查看 `/api/*` 响应头；停掉盒端服务后操作页面 | 分别返回 401 + `err=auth`、404、无可缓存头、页面提示「未连接到盒子」 | 自动部分 P（401/404/no-store 均由 WebApiTest 验证）；停服页面提示待执行 |
| AC-11 | 运行 `powershell -File tests\protocol\run.ps1` | 全部通过（含新增 Web API 用例） | P（协议 125 + Web 55 + QR 16 = 196） |
| AC-12 | 用 SPEC-024 二维码地址（带 `#pair=<正确码>`）打开页面；再改用错误码打开 | 正确码自动配对并进入遥控界面、hash 清除；错误码显示配对错误提示并停留配对卡 | 部分通过（PC Chromium：正确码自动配对、显示「已连接」、hash 清除；错误码待执行） |

## 实测备注

- 本地联调：Chromium 打开 `http://127.0.0.1:18080`（MockBoxServer），完成配对、
  方向/OK/返回/音量按键、中文文本发送、刷新保持；控制台无 JS 报错。
- 静态资源未压缩合计约 217KB（图标占 185KB），满足 300KB 上限。
- MockBoxServer 只对 `com.example.tvapp` 返回图片，因此联调时「设置」图标显示占位块；
  该行为属测试替身限制，真实盒子 ICON 对全部应用可用。
- 待真机确认：HTTPS/证书信任、Service Worker 与安装入口、无障碍错误提示、多浏览器布局。
- 端口与证书复测（荐片TV RK3566，2026-10-22）：`/proc/net/tcp6` 可见 17880(45B8)
  与 17843(45B3) 均由盒端 app（uid 10094）监听；旧格式证书导致 17843 未监听，
  根因与修复见 SPEC-020 变更记录（keytool 需 `-J-Dkeystore.pkcs12.legacy`）。
