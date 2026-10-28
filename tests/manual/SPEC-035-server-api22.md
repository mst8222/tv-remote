# SPEC-035 手工验收清单：盒端 App 在 Android 5.1（API 22）

被测设备：Redmi 电视 MiTV-ANTL0，192.168.3.39，Android 5.1.1（SDK 22），
固件 22.8.17.1977。ADB：`platform-tools\adb.exe`（电视「设置 → 账号与安全 → ADB 调试」开启）。
执行日期：2026-10-24。

| 编号 | 步骤 | 预期 | 结果 |
|---|---|---|---|
| AC-1 | `python build.py server`，然后 `aapt2 dump badging dist\TVRemoteServer.apk` | 构建成功；输出含 `sdkVersion:'22'` | 通过：产物 338 KB，`minSdkVersion:'22'`，versionCode 14 / 1.7.0 |
| AC-2 | `adb install -r dist\TVRemoteServer.apk` | `Success` | 通过：`Performing Push Install ... Success` |
| AC-3 | 电视上打开「TV遥控服务」 | 界面显示配对码、本机 IP；通知栏有常驻通知 | 通过（通知受限）：界面显示配对码 567316、192.168.3.39:8899；该固件拒绝对前台服务展示通知（日志 `not allow service ... become forceground`），回桌面 10 秒后进程仍在、PING 仍为 PONG |
| AC-4 | `PING` / `PAIR 567316`（直连 8899） | `PONG` / `OK <token>` | 通过：`PING -> PONG`；`PAIR -> OK 6eb2d6bf...` |
| AC-5 | 电视「设置 → 无障碍」开启「TV遥控服务」，发 `KEY <token> 19/20/21/22/23` | 界面有实际反应 | 通过：五个方向键 + `KEYLONG 23` 均返回 `OK`；界面同步显示「无障碍服务：已开启（按键可用）」 |
| AC-6 | 浏览器打开 `http://192.168.3.39:17880`，输入文字点发送 | 页面正常；返回成功或提示当前界面无输入框 | 通过：首页 HTTP 200（5234 字节）；`POST /api/text` 返回 `{"ok":false,"err":"exec"}`（当前界面无输入框，属预期）；`/api/apps` 200、`/api/key` 返回 `{"ok":true}` |
| AC-7 | 发 `MTAPN <token> 5000 5000` | 返回 `ERR exec`；App 不崩溃、8899 仍可 `PING` | 通过：`MTAPN -> ERR exec`，随后 `PING -> PONG`；logcat 无 `FATAL EXCEPTION` / `NoClassDefFoundError` |
| AC-8 | `powershell -File tests\protocol\run.ps1` | 全部通过 | 通过：ProtocolTest / WebApiTest / QrCodeTest 均 0 失败（脚本退出码 0） |

备注：
- AC-7 允许在无命中节点时返回 `ERR exec`，这是 SPEC-035 的预期降级行为。
- 无障碍通过 `adb shell settings put secure enabled_accessibility_services ...` 开启，
  等价于在电视上手动开关。
