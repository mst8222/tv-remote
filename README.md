# TV Remote — 安卓电视盒子遥控器

用手机遥控安卓电视盒子。**三种连接模式**，主力是「局域网直连」：在盒子上跑一个常驻服务，
手机通过 WiFi 直连控制，**不需要开发者选项、不需要无线调试、盒子重启自动恢复**。

- 目标设备：荐片TV / RK3566（Android 12，userdebug，已 root）
- 手机端：三星 Note20（SM-N9810，Android 13）
- 两个 APK 都是手工编译（不用 Gradle），单文件 16–37 KB，无第三方依赖

---

## 架构

```
┌─────────────────────┐         TCP 8899          ┌──────────────────────────┐
│  手机 App            │ ───────────────────────► │  盒子 App（常驻服务）      │
│  TV遥控器.apk        │   配对码 → token 认证      │  TV遥控服务.apk           │
│                      │ ◄─────────────────────── │                          │
│  · 方向键 / OK       │         OK / ERR          │  前台 Service + 开机自启   │
│  · 音量 / 返回 / 主页 │                           │  ├ 监听 8899              │
│  · 文字输入          │         UDP 8898          │  ├ 每 3s 广播供发现        │
│  · 应用快捷          │ ◄────── 设备广播 ────────  │  └ su root sh -c "input…" │
└─────────────────────┘                           └──────────────────────────┘
```

## 三种模式

| 模式 | 原理 | 依赖 | 适用 |
|---|---|---|---|
| **局域网直连** ⭐ | 手机 TCP → 盒子端常驻服务 | 盒子端 App 在跑 | **日常遥控首选**，无需调试、重启自愈 |
| ADB | 手机内置 ADB 协议客户端 → 盒子 5555 | 开发者选项 + 无线调试常开 | 装应用、截图、抓日志 |
| 蓝牙 HID | 手机作为蓝牙键盘 | 蓝牙配对 | 实验性，HID 通道未打通，暂不用 |

## 目录结构

```
tv-remote/
├── build.py               # 一键构建（aapt2 → javac → d8 → zipalign → apksigner）
├── app/                   # 手机端：com.koco.tvremote
│   ├── AndroidManifest.xml
│   └── src/com/koco/tvremote/
│       ├── MainActivity.java    # UI（代码构建，无 layout XML）+ 三模式分发
│       ├── AdbClient.java       # 手写 ADB over TCP 协议（含 RSA 认证、STLS/TLS）
│       ├── LanClient.java       # 局域网直连客户端（TCP + UDP 发现）
│       ├── BtHid.java           # 蓝牙 HID（实验性）
│       └── KeyConst.java        # 构建时生成，含私钥，已 gitignore
├── server/                # 盒子端：com.koco.tvremoteserver
│   ├── AndroidManifest.xml
│   └── src/com/koco/tvremoteserver/
│       ├── MainActivity.java    # 显示 IP / 端口 / 配对码 / root 状态
│       ├── ServerService.java   # 常驻前台服务，TCP 8899 + UDP 8898
│       └── BootReceiver.java    # 开机自启
├── tools/                 # Java 原型（协议验证用）
│   ├── AdbTest.java             # ADB 握手/认证原型，可生成密钥
│   └── TlsTest.java             # STLS/TLS 1.3 握手测试
├── keys/                  # 密钥（gitignore，需自行备份）
│   ├── adbkey.pk8               # ADB 客户端私钥（对应盒子 /data/misc/adb/adb_keys）
│   ├── cert.der                 # 由同一私钥生成的自签证书（TLS 用）
│   └── release.jks              # APK 签名库（口令 koco123456，别名 koco）
└── dist/                  # 构建产物（gitignore）
```

## 构建

需要 **JDK 11+** 和 **Android SDK**（build-tools + platforms/android-35）。

```bash
python build.py app       # → dist/TVRemote.apk
python build.py server    # → dist/TVRemoteServer.apk
```

`build.py` 会自动定位 `%LOCALAPPDATA%\Android\Sdk`，无需配置路径。

### 密钥说明

`keys/` 不入库，需自行备份。丢失后果：

- `adbkey.pk8` 丢失 → ADB 模式连不上盒子（需重新生成公钥并写入盒子的 `adb_keys`）
- `release.jks` 丢失 → **无法覆盖升级已装的 App**（签名不一致），只能卸载重装

`app/src/com/koco/tvremote/KeyConst.java` 由 `build.py` 从 `keys/adbkey.pk8` 自动生成，不要手动编辑、也不要提交。

## 直连协议（盒子端 8899，每行一条，`\n` 结尾）

| 发送 | 返回 | 说明 |
|---|---|---|
| `PING` | `PONG` | 探活，无需认证 |
| `PAIR <6位配对码>` | `OK <token>` / `ERR badcode` | 配对换 token，token 存 SharedPreferences |
| `KEY <token> <keycode>` | `OK` / `ERR auth` / `ERR exec` | 注入按键，如 `KEY xxx 23` = OK 键 |
| `TEXT <token> <base64>` | `OK` / `ERR exec` | 输入文字（base64 避免空格/中文问题） |
| `APP <token> <包名>` | `OK` / `ERR exec` | 启动应用 |

配对码在盒子端界面显示（6 位数字），存于 App 私有存储。

## 踩过的坑（重要，改代码前先看）

1. **盒子上的 `su` 是 toybox 版**，语法 `su [WHO [COMMAND...]]`，**不支持 `su -c`**
   （会报 `su: failed to exec -c`）。必须 `su root sh -c "命令"`。
2. **`monkey` 是 shell 脚本**，`su root monkey ...` 直接 exec 会失败，必须经 `sh -c`。
3. **该固件的 Settings 没有 LAUNCHER 入口**，`monkey -p com.android.settings -c LAUNCHER`
   报 `No activities found`，要用 `am start -a android.settings.SETTINGS`。
4. **盒子界面完全不响应触摸**（`input tap` 无效），只能 `input keyevent` 导航。
5. App 内弹 Dialog 必须 `runOnUiThread`，Binder 回调线程直接 `show()` 会静默失败。
6. `NsdManager` 同一个 listener 不能注册两个服务类型，否则报 `listener already in use`。
7. 手工编译要点：
   - JDK 21 中 `-source 11` 不允许配 `-bootclasspath`，须用 `-source 8 -target 8`
   - lambda 需要把 `core-lambda-stubs.jar` 加进 bootclasspath
   - `java.util.Base64` 是 API 26+，故 minSdk=26

## 盒子的其他已知情况

- 固件是 **H96_Max_V56**（非荐片原厂），`ro.debuggable=1`、SELinux **Permissive**，
  所以普通 App 可直接提权 —— 这是本方案成立的前提
- **SATA 口不可用**：设备树 `sata@fc400000` / `sata@fc800000` 均为 `status=disabled`
  （该机型无 SATA 口，dtb 未启用），需刷回原厂固件或 Armbian 才能用
- USB 口是 **host-only**（`dwc3 dr_mode=host`），无法作为 USB device 被电脑识别

## 版本

- v1.0 三模式版本：局域网直连端到端验证通过（配对、按键、TEXT、APP、开机自启、重启后免配对重连）
