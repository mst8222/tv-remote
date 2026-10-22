<div align="center">

<img src="app/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="TV Remote">

# TV 遥控器 · TV Remote

**把手机变成安卓电视盒子的遥控器**<br>
无需 root · 无需开发者选项 · 无需无线调试

<p>
  <strong>简体中文</strong> |
  <a href="./README.en.md">English</a>
</p>

<p>
  <a href="https://github.com/mst8222/tv-remote/releases/latest">
    <img src="https://img.shields.io/github/v/release/mst8222/tv-remote?color=brightgreen&include_prereleases" alt="release">
  </a><!--
  --><a href="https://gitee.com/moston/tv-remote/releases">
    <img src="https://img.shields.io/badge/Gitee-%E9%95%9C%E5%83%8F-C71D23" alt="Gitee 镜像">
  </a><!--
  --><a href="./LICENSE">
    <img src="https://img.shields.io/badge/license-PolyForm%20Noncommercial%201.0.0-blue" alt="license">
  </a><!--
  --><img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/root-not%20required-brightgreen" alt="no root">
</p>

<p>
  <a href="#项目简介">项目简介</a> •
  <a href="#功能特性">功能特性</a> •
  <a href="#界面截图">界面截图</a> •
  <a href="#快速开始">快速开始</a> •
  <a href="#常见问题">常见问题</a>
</p>

</div>

## 项目简介

在盒子上装一个常驻服务，手机与盒子连同一个局域网即可控制；盒子重启后服务自动恢复。
没有安装盒子端 App 时，也可以用手机蓝牙 HID 应急遥控。

> [!NOTE]
> 直连协议是局域网**明文 TCP**，只面向可信的家庭/办公网络，不要把 8899 端口映射到公网。

| | |
|---|---|
| 控制端 | Android 手机（开发与验证：三星 Note20 / Android 13） |
| 被控端 | Android 电视盒子（H96 Max V56 / Android 12、三星 Fold3 / Android 13） |
| 运行前提 | 手机与盒子同一局域网；盒子端开启无障碍（无需 root） |
| 构建方式 | 手工编译链，无第三方依赖，两端 APK 约 100–130 KB |

把手机变成电视盒子的全能遥控器，主要解决三类日常问题：

1. **找不到遥控器**——方向、OK、返回、主页、音量、静音、电源全部在手机上。
2. **电视上打字太难**——在手机上输入搜索词、账号密码，直接写进盒子当前输入框。
3. **常用应用层级太深**——一键启动盒子上安装的可启动第三方应用（OK影视、飞牛TV、Kodi 等）或系统设置。

同时管理多台盒子：扫描或手动添加设备，切换设备时自动使用该设备上次的连接方式和配对信息。

## 功能特性

| 能力 | 说明 |
|---|---|
| 完整按键 | 方向、OK、返回、主页、菜单、电源、音量、静音 |
| 文字输入 | 手机输入搜索词或账号密码，写入盒子当前焦点输入框 |
| 应用快捷 | 动态列出盒子全部可启动的第三方应用并显示盒子端真实图标，首项固定为「设置」 |
| 飞鼠 | 蓝牙 HID 模式下用手机陀螺仪移动盒子光标，OK 作为鼠标左键 |
| 触控板 | 直连模式下方向盘 / 触摸 / 鼠标分段切换：触摸把手指位置直接映射到盒子屏幕（点哪打哪），鼠标拖动移动盒端光标；适合不同盒子固件能力 |
| OK 长按 | 蓝牙模式持续发送按下由盒子应用判定；直连模式按住 500ms 以上在抬起时发送长按命令 |
| 多设备管理 | 局域网扫描、手动添加、切换与删除；配对信息和连接方式按设备记忆 |
| 免 root | 盒端通过无障碍服务、`AudioManager`、`PackageManager` 完成控制 |
| 开机自启 | 前台服务常驻，开机/重启后自动拉起 |
| 中英文界面 | 手机端支持中文 / English / 跟随系统；盒子端跟随盒子系统语言 |
| 应用内更新 | 两端同时读取 GitHub 与 Gitee 的最新 Release，任一源版本更高即提示，下载优先走 Gitee |

### 应用快捷的显示规则

手机端通过 `LIST` 命令获取盒子上的第三方应用，载荷为 `包名|base64(应用名)`；
界面按应用名排序动态生成，首项固定为「设置」（`com.android.settings`，系统应用，始终显示）。

- 应用名取盒子系统语言下的 `PackageManager.getApplicationLabel`；
- 只包含有启动入口（LAUNCHER / LEANBACK_LAUNCHER）的第三方应用；
- 图标通过 `ICON <token> <包名>` 按需拉取（144x144 PNG，Base64）：先显示名称首字，
  取回后替换并缓存（按设备 IP + 包名，内存级），失败保持首字占位；
- 应用快捷和文字输入只在直连模式可用。

## 界面截图

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/phone-remote-zh.png" width="250"><br>手机端（中文）</td>
    <td align="center"><img src="docs/screenshots/phone-remote-en.png" width="250"><br>手机端（English）</td>
    <td align="center"><img src="docs/screenshots/box-server-zh.png" width="460"><br>盒子端服务（配对码 / 地址 / 无障碍状态）</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/phone-devices-zh.png" width="250"><br>设备选择与删除</td>
    <td align="center"><img src="docs/screenshots/phone-scan-zh.png" width="250"><br>局域网自动扫描</td>
    <td align="center">盒子端界面跟随盒子系统语言；手机端可在「中/EN」切换</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/phone-apps-zh.png" width="250"><br>应用快捷（中文界面）</td>
    <td align="center"><img src="docs/screenshots/phone-apps-en.png" width="250"><br>应用快捷（English 界面）</td>
    <td align="center">应用名称来自盒子系统语言，按名称排序；首项固定为「设置」</td>
  </tr>
</table>

## 两种连接方式

| | 局域网直连（推荐） | 蓝牙 HID（备用） |
|---|---|---|
| 原理 | 手机 TCP 连接盒子端常驻服务 | 手机注册为蓝牙键盘/遥控器 |
| 前提 | 盒子端安装「TV遥控服务」并开启无障碍 | 手机与盒子完成蓝牙配对 |
| 方向 / OK / 返回 / 主页 / 菜单 / 电源 | 支持 | 支持 |
| 音量 / 静音 | 支持 | 支持 |
| 文字输入 | 支持 | 不支持 |
| 应用快捷 | 支持 | 不支持 |
| 自动发现 | 支持（UDP 广播） | 需在系统蓝牙设置里配对 |
| 适用场景 | 日常首选，功能完整，重启自愈 | 盒子没装 App、或临时应急 |

> 直连的按键与文字由盒子端无障碍服务注入，应用启动和音量不依赖无障碍；
> 蓝牙模式要求手机 App 保持在前台（Android 会在应用退到后台时注销 HID 注册，回到前台会自动重新注册）。

## 跨端能力矩阵

盒子端服务同时提供内置网页遥控：同一局域网内用浏览器打开
`http://<盒子IP>:17880` 即可，无需在手机、平板或电脑上安装任何东西；
盒子界面会直接显示该地址的二维码，并已带上配对码；手机相机扫一下即可打开并
自动完成配对，无需输入地址与配对码。
信任自签证书后可用 `https://<盒子IP>:17843` 获得安装到主屏幕与离线外壳能力。

网页遥控同样支持多设备：配对过的盒子记在浏览器本地，顶部设备栏可随时切换；
在 HTTPS 页面点「扫码添加」可直接扫描另一台盒子的二维码入列，也可手动输入地址与配对码，
列表里的设备可单独改名或删除。网页端提供方向盘 / 触摸 / 鼠标分段切换，不再提供飞鼠。
（相机扫码要求浏览器安全上下文，HTTP 页面只提供手动添加。）

| 端 | 局域网直连 | 蓝牙 HID | 触控板 |
|---|---|---|---|
| Android 原生 App | 支持（TCP 8899） | 支持（API 28+） | 蓝牙陀螺仪飞鼠；直连触摸 / 鼠标 |
| 浏览器（Android / iOS / HarmonyOS NEXT / PC） | 支持（17880 / 17843） | 不提供 | 直连触摸 / 鼠标 |
| 原生 iOS / HarmonyOS NEXT App | 不单独提供，改用浏览器 | 不提供 | 不提供 |

> 浏览器没有 HID 设备接口，Web Bluetooth 也只能做 GATT 客户端，
> 因此网页端不提供蓝牙入口；无法使用蓝牙时请改用 Android App 的蓝牙模式或 2.4G 遥控器。

## 工作原理

```
┌──────────────────────────┐                       ┌────────────────────────────────┐
│ 手机端 TV遥控器.apk        │      TCP 8899         │ 盒子端 TV遥控服务.apk            │
│                          │ ────────────────────► │                                │
│  · 方向 / OK             │   PAIR / KEY / TEXT   │  ServerService（前台常驻）       │
│  · 返回 / 主页 / 菜单     │   APP / LIST / PING   │   ├ 监听 TCP 8899               │
│  · 音量 / 静音 / 电源     │ ◄──────────────────── │   ├ 每 3 秒 UDP 8898 广播        │
│  · 文字输入 / 应用快捷     │                       │   ├ BootReceiver 开机自启        │
│  · 设备扫描与切换         │      UDP 8898         │   └ 无障碍服务（无 root 注入）    │
│                          │ ◄──── 设备广播 ─────── │                                │
└──────────────────────────┘                       └────────────────────────────────┘
          │
          └── 蓝牙 HID ──────────► 盒子蓝牙栈（此模式不需要盒子端 App）
```

| 组件 | 端口 / 机制 | 作用 |
|---|---|---|
| `ServerService` | TCP 8899 | 接收 `PING` / `PAIR` / `KEY` / `KEYLONG` / `TEXT` / `APP` / `LIST` / `ICON` |
| 设备发现 | UDP 8898 | 盒子每 3 秒广播 `TVREMOTE\|8899\|<盒子型号>` |
| `TvRemoteAccessibilityService` | 系统无障碍 API | 全局按键、焦点遍历、文本写入、长按注入 |
| `BootReceiver` | 开机广播 | 断电重启后自动拉起服务 |
| `BtHid` | 蓝牙 HID | 键盘、消费者控制、鼠标（飞鼠）报告 |

## 快速开始

### 运行条件

| 组件 | 最低版本 | 已验证版本 |
|---|---|---|
| 手机端 · 直连模式 | Android 8.0（API 26） | Android 13（三星 Note20） |
| 手机端 · 蓝牙 HID 模式 | Android 9.0（API 28） | Android 13（三星 Note20） |
| 盒子端 App | Android 8.0（API 26） | Android 12（H96 Max V56）、Android 13（三星 Fold3） |

盒子端要求：

1. 可安装第三方 APK 的安卓电视盒子或安卓电视；非安卓系统（webOS、Tizen、传统电视）不支持。
2. 安装「TV遥控服务」并**手动开启无障碍服务**：盒子「设置 → 无障碍 → TV遥控服务」。
   未开启时按键/文字返回 `ERR a11y`；音量、应用启动、应用列表仍可用。
3. **不需要 root**，盒端通过 `AccessibilityService`、`AudioManager`、`PackageManager` 完成控制。
4. 建议盒子保持联网（有线或 WiFi）。服务以前台通知常驻，并在开机/重启后自动拉起；
   个别固件有后台限制时，需在系统设置中允许本应用自启。
5. 盒子端界面在部分固件上不响应触摸（实测 H96），可用盒子自带遥控器聚焦后按 OK，
   或通过 ADB 打开系统设置。

网络条件：

- 手机与盒子必须在**同一局域网/同一网段**。
- 直连使用 **TCP 8899**（控制）和 **UDP 8898**（设备发现广播）。
- 路由器若开启 **AP 隔离**，自动发现会失败，但可以用「添加」手动填写盒子 IP。
- 建议在路由器上为盒子保留固定 IP（DHCP 静态分配）；IP 变化后需要重新添加设备。

### 1. 安装盒子端

1. 安装 `dist/TVRemoteServer.apk`（U 盘安装或 `adb install`）。
2. 打开「TV遥控服务」，允许通知权限。界面会显示 **6 位配对码** 和 **本机 IP**。
3. 进入盒子「设置 → 无障碍」，开启「TV遥控服务」。
4. 之后保持服务运行即可；开机/重启会自动启动。

> 配对码在首次启动时随机生成并保存在应用私有存储中，清除应用数据或重装会生成新码。

### 2. 手机端 · 局域网直连

1. 安装 `dist/TVRemote.apk`，手机与盒子连同一个 WiFi。
2. 打开 App。点顶部「扫描」，在扫描结果里点选盒子；或点「添加」手动填写名称和 IP。
3. 点「直连」，输入盒子屏幕上的 6 位配对码，点「配对并连接」。
4. 配对成功后即可按键。配对信息按盒子 IP 保存，下次打开自动恢复。

> 首次启动的设备列表自带两条示例设备（荐片TV · RK3566、三星 Fold3）。
> 点顶部设备名 →「删除当前」即可替换成你自己的盒子；列表至少保留一台。

### 3. 手机端 · 蓝牙 HID（可选）

<details>
<summary><strong>展开蓝牙配对步骤</strong></summary>

1. 手机打开蓝牙；盒子打开蓝牙并进入「搜索设备」。
2. 手机点「蓝牙」。若还没有已配对设备，点「让盒子搜索这台手机」（开启 120 秒可见）。
3. 在盒子蓝牙列表中选择这台手机，两边确认配对码。
4. 回到 App 再点「蓝牙」，选择刚配对的盒子，状态显示「蓝牙已连接」后即可按键。

</details>

## 日常使用

| 操作 | 说明 |
|---|---|
| 点击设备名 | 切换设备、删除当前设备 |
| 扫描 | 监听盒子 UDP 广播，列出局域网内所有盒子，点一下即添加并连接 |
| 添加 | 手动输入设备名称和盒子 IP |
| 直连 | 未配对时弹出配对框；已配对则直接进入直连 |
| 长按直连 | 清除当前设备的配对信息，用于盒子重置或换盒子 |
| 蓝牙 | 注册 HID 并连接已配对的盒子；切换设备时会自动连该设备记住的蓝牙目标 |
| 中/EN | 中文 / English / 跟随系统，即时生效并持久保存 |
| 方向键与 OK | 直连时优先用系统 DPAD 全局动作；固件不支持时自动改为焦点遍历 |
| OK 短按 / 长按 | 蓝牙模式按住 OK 时盒子持续收到「按下」，由 TV 应用自行判定长按；直连模式按住 500ms 以上在抬起时发送长按命令 |
| 飞鼠（右上角 ↖） | 仅蓝牙模式：开启后转动手机移动盒子光标，OK 键为鼠标左键；再次点击关闭 |
| 触控板（方向盘上方分段） | 仅直连模式，方向盘 / 触摸 / 鼠标三段。触摸：手指位置映射到盒子屏幕同一位置（不显示光标），点按为点击、按住 500ms 为长按、单指滑动为滑动手势；鼠标：拖动移动盒端光标，点按单击、按住长按、双指滚动 |
| 返回 / 主页 | 系统全局返回、桌面 |
| 菜单 | 打开最近任务（非 root 环境没有菜单键全局动作） |
| 电源 | 盒子系统锁屏（不是真正关机） |
| 音量 / 静音 | 调整盒子媒体音量 |
| 文字输入 | 写入盒子当前焦点输入框；当前界面没有输入框时提示执行失败 |
| 应用快捷 | 动态显示盒子全部可启动的第三方应用（名称 + 真实图标） |
| 检查更新 | 手机端在「关于」卡、盒子端在「版本更新」卡同时查询 GitHub 与 Gitee 的 Release，取版本更高者，下载优先 Gitee，失败回退 GitHub，最终由系统安装界面确认 |

## 直连协议

盒子端 TCP 8899，每行一条命令，`\n` 结尾。
触控板鼠标模式使用 `MOVE` / `MCLICK` / `MTAP` / `MLONG` / `SCROLL`（相对光标），
触摸模式使用 `MTAPN` / `MLONGN` / `MSWIPEN`（绝对坐标）。

| 发送 | 返回 | 说明 |
|---|---|---|
| `PING` | `PONG` | 探活，无需认证 |
| `PAIR <6位配对码>` | `OK <token>` / `ERR badcode` | 用配对码换长期 token |
| `KEY <token> <keycode>` | `OK` / `ERR auth` / `ERR a11y` / `ERR cmd` / `ERR exec` | 注入按键 |
| `KEYLONG <token> <keycode>` | `OK` / `ERR auth` / `ERR a11y` / `ERR cmd` / `ERR exec` | 长按注入（仅 23/OK），焦点节点可长按则执行 `ACTION_LONG_CLICK`，否则按焦点中心合成 600ms 手势 |
| `TEXT <token> <base64>` | `OK` / `ERR a11y` / `ERR exec` | 写入当前输入框（base64 编码 UTF-8） |
| `APP <token> <包名>` | `OK` / `ERR exec` | 启动应用，不依赖无障碍 |
| `LIST <token>` | `OK <包名\|base64(应用名),逗号分隔>` | 第三方可启动应用列表，不依赖无障碍 |
| `ICON <token> <包名>` | `OK <base64(PNG)>` / `ERR arg` | 应用图标（144x144 PNG），不依赖无障碍；未知包返回 `ERR arg` |
| `MOVE <token> <dx> <dy>` | `OK` / `ERR arg` / `ERR a11y` | 相对移动盒端光标，单轴 -127..127 |
| `MCLICK <token> <0\|1>` | `OK` / `ERR arg` / `ERR a11y` | 光标按下 / 抬起（0 抬起，1 按下） |
| `MTAP <token>` | `OK` / `ERR a11y` | 光标处单击，节点优先、手势兜底 |
| `MLONG <token>` | `OK` / `ERR a11y` | 光标处长按 |
| `SCROLL <token> <步数>` | `OK` / `ERR arg` / `ERR a11y` | 双指滚动，正数向下、负数向上，范围 -5..5 |
| `MTAPN <token> <x> <y>` | `OK` / `ERR arg` / `ERR a11y` | 绝对点按，x/y 为 0..10000 的归一化屏幕坐标 |
| `MLONGN <token> <x> <y>` | `OK` / `ERR arg` / `ERR a11y` | 绝对长按，坐标同 `MTAPN` |
| `MSWIPEN <token> <x1> <y1> <x2> <y2> <ms>` | `OK` / `ERR arg` / `ERR a11y` | 绝对滑动，坐标 0..10000，时长 50..1500ms |

UDP 8898 广播格式：`TVREMOTE|8899|<盒子型号>`，每 3 秒一次，供手机自动发现。

## 测试

```powershell
powershell -File tests\protocol\run.ps1
```

自动回归共 196 项（直连协议 125 + Web API 55 + 二维码 16），正常输出 `通过 196，失败 0`。
要求本机 8898 端口空闲；若局域网中有真实盒子在广播，`discover_no_broadcast_null`
这一项会因收到真实广播而失败，属于环境干扰。

## 目录结构

```
tv-remote/
├── app/                    # 手机端 com.koco.tvremote
│   ├── AndroidManifest.xml
│   ├── res/                # 图标 + 中英文文案（默认中文，values-en 英文）
│   └── src/com/koco/tvremote/
│       ├── MainActivity.java   # 界面与交互（纯代码构建，无 layout XML）
│       ├── LanClient.java      # 直连客户端（TCP + UDP 发现）
│       ├── BtHid.java          # 蓝牙 HID 键盘/消费者控制/鼠标（飞鼠）
│       └── Errors.java         # 异常到本地化报错文案
├── server/                 # 盒子端 com.koco.tvremoteserver
│   ├── AndroidManifest.xml
│   ├── res/                # 图标 + 界面/通知文案
│   └── src/com/koco.tvremoteserver/
│       ├── MainActivity.java                  # 配对码 / 地址 / 无障碍状态
│       ├── ServerService.java                 # 前台服务，TCP 8899 + UDP 8898
│       ├── TvRemoteAccessibilityService.java  # 无 root 按键/文字注入
│       └── BootReceiver.java                  # 开机自启
├── docs/screenshots/       # README 截图
├── spec/                   # 需求与规格（planned / developing / implemented / discarded）
├── tests/                  # protocol 自动测试 + manual 手工验收清单
├── tools/                  # PC 端 Java 协议原型（ADB / TLS）
├── build.py                # 一键构建
├── keys/                   # 签名与密钥（不入库）
└── dist/                   # 构建产物（不入库）
```

## 构建

需要：

- Python 3
- JDK 11+（`javac` 需在 PATH；已验证 JDK 21）
- Android SDK：`build-tools/36.1.0`、`platforms/android-35`

```powershell
python build.py app       # -> dist/TVRemote.apk
python build.py server    # -> dist/TVRemoteServer.apk
```

`build.py` 默认从 `%LOCALAPPDATA%\Android\Sdk` 查找 SDK；构建链为
aapt2 → javac → d8 → 打包 dex → zipalign → apksigner。

> [!WARNING]
> `keys/` 不入库（`.gitignore`），`keys/release.jks` 丢失后无法覆盖升级已安装的 App，
> 只能卸载重装。请自行备份该文件。

## 常见问题

| 现象 | 原因与处理 |
|---|---|
| 提示「连接被拒绝：盒子端服务未运行」 | 在盒子上打开「TV遥控服务」 |
| 提示「连接超时 / 连接失败」 | 盒子离线、IP 变化，或手机与盒子不在同一 WiFi |
| 提示「配对码错误」 | 对照盒子屏幕重新输入 6 位数字；清除应用数据后配对码会变 |
| 提示「配对已失效」 | 长按「直连」清除配对，再重新配对一次 |
| 按键提示「盒子端未开启无障碍服务」 | 盒子「设置 → 无障碍」开启「TV遥控服务」 |
| 按键提示「盒子执行失败」 | 当前界面没有可操作焦点/输入框，先在盒子上打开目标界面 |
| 扫描不到盒子 | 检查同一 WiFi、盒子服务是否运行、路由器是否开启 AP 隔离；可手动添加 IP |
| 方向键没反应 | 该界面没有可聚焦元素（如全屏播放器）；部分固件只能走焦点遍历 |
| 触控板点按无反应或滑动无效 | 该盒子固件不执行合成手势时，切到「鼠标」模式拖动光标操作；两种模式都需先开启无障碍 |
| 蓝牙按键没反应 | App 需在前台；回前台后重新点「蓝牙」；确认已显示「蓝牙已连接」 |
| 飞鼠开启后光标不动 | 盒子端缓存了首次配对时的 HID 描述符：在盒子蓝牙设置里删除与手机的配对，再重新配对一次；H96 等固件删除配对后仍不生效时，需重启盒子蓝牙或在 root 下改 `bt_config.conf` 的 `HidDescriptor` |
| 盒子重启后连不上 | 确认服务已自启；部分固件需允许应用自启，或重新打开一次 App |
| 网页遥控打不开 | 确认与盒子在同一 WiFi，地址取盒端界面显示的 `http://<IP>:17880`；`https://<IP>:17843` 需先在浏览器里信任自签证书 |
| 顶栏出现「有更新」 | App 每次启动自动探测 GitHub 与 Gitee 的最新 Release，任一源有更高版本时提示；点击标签查看更新说明，忽略不影响使用 |

## 适用范围与已知限制

明确不适用：

- 控制电视本身的电源与音量（红外 / HDMI-CEC）——本项目的电源键是盒子系统锁屏，
  音量键调的是盒子媒体音量。
- 跨网段、外网远程控制。协议是明文局域网协议，不要把 8899 / 17880 / 17843 暴露到公网。
- 原生 iOS / HarmonyOS NEXT 客户端（不单独开发，改用盒子端内置网页遥控）。

已知限制：

- 「电源」是系统锁屏，不能真正关机/开机；「菜单」是最近任务，不是传统菜单键。
- 音量只调节盒子媒体音量，不控制电视或功放。
- 文字输入要求当前界面存在可编辑输入框，且盒子输入法接受 `ACTION_SET_TEXT`。
- 部分电视固件不支持 DPAD 全局动作（实测 H96 Android 12），已回退到焦点遍历；
  界面没有可聚焦控件时方向键无效果。
- 蓝牙 HID 模式不支持文字输入与应用快捷，且 App 需保持前台。
- OK 长按在蓝牙模式由盒子应用自行判定；直连模式依赖焦点节点支持
  `ACTION_LONG_CLICK`，不支持长按的应用无效果。
- 飞鼠只在 Android App 的蓝牙 HID 模式可用：盒子需接受蓝牙鼠标 HID 报告；
  手机没有陀螺仪时无法开启，横竖屏均可但灵敏度为固定值。
- 手机与网页触控板都只走直连协议，需要盒端无障碍服务开启；蓝牙模式不提供触控板。
  触摸模式依赖可点击节点或合成手势，盒端固件不执行合成手势时（实测 H96 Android 12）
  纯触摸区域与滑动会失效，应改用鼠标模式；鼠标模式依赖光标覆盖层，
  只响应 D-pad 的电视桌面仍无法通过触控板操作。
- 网页遥控依赖盒子端服务在线；浏览器收不到 UDP 广播，添加设备需扫盒端二维码或手动输入地址。
  扫码添加只在 HTTPS 页面可用（安全上下文限制）。
  自签证书未受信任时 Chrome 不提供安装入口，Service Worker 可能被禁用，
  iOS Safari 仍可「添加到主屏幕」但没有离线缓存。
- 从一台盒子的页面控制另一台盒子属于跨源请求，盒端 Web 服务为此允许跨源访问，
  因此不要把 17880 / 17843 暴露到不可信网络。
- 盒子端会把**首次配对时**的 HID 报告描述符持久化在配对记录里（H96 实测存于
  `/data/misc/bluedroid/bt_config.conf` 的 `HidDescriptor`，Fold3 在重连时重新读取）。
  从旧版本升级后，曾用旧版配对过的盒子（尤其是 H96 这类定制固件）需删除配对并
  重新配对一次，否则盒子仍按「无鼠标」的旧描述符建输入设备，飞鼠无反应。
- 应用快捷覆盖盒子上有启动入口的第三方应用；纯服务类/无桌面入口的应用不会列出。
- 盒子端 `LIST` 依赖 Android 11+ 的包可见性声明（manifest 中的 `<queries>`）。
- 应用内更新同时查询 GitHub 与 Gitee 的公开 Release，任一源有更高版本即提示；
  GitHub 匿名接口限流为每小时 60 次，超限时该源失败、由另一源兜底。
- 安装包优先从 Gitee 直链下载，失败回退 GitHub API 资产端点；两个源都不可达时
  需自备代理或手动下载 APK。
- 更新必须经过系统安装确认，无法静默安装；部分安卓版本还需在系统设置里允许本应用
  安装未知应用。盒子端安装界面需要用物理遥控选择确认。
- 盒子端「检查更新」按钮同时处理遥控 OK/ENTER 与触屏点击（部分固件可聚焦 TextView
  不响应默认 OK 事件）。
- 启动更新探测每次打开 App 执行一次，不做后台定时轮询；两个源都不可达时静默忽略，
  可在「关于」卡手动重试。

## 版本记录

- **v1.6.6**：应用内更新改为双源探测：同时查询 GitHub 与 Gitee 的公开 Release，
  任一源有更高版本即显示「有更新」；下载优先走 Gitee 直链，失败回退 GitHub 资产接口。
  GitHub 在部分国内网络不可达时仍可通过 Gitee 完成检查与升级。
- **v1.6.5**：两端触控板改为「方向盘 / 触摸 / 鼠标」三段。触摸模式把手指位置直接映射到
  盒子屏幕（点哪打哪，点按 / 长按 / 单指滑动），鼠标模式拖动移动盒端光标（点按单击、
  按住长按、双指滚动），适配不支持绝对触摸的盒子固件；修复盒端分辨率覆盖后触摸坐标
  按错误屏幕尺寸换算导致的落点偏移；切模式、切设备或离开页面时立即隐藏光标并释放按压。
  升级说明：触控板只在直连模式可用，蓝牙模式不提供。
- **v1.6.0**：网页遥控支持多设备管理（记住已配对盒子、切换、扫码或手动添加、改名、删除）；
  网页新增方向盘 / 触控板切换，删除网页飞鼠；两端触控板宽度改为铺满卡片内容区，
  与系统键行等宽；网页音量行对齐 Android 版（`[− 音量 +]` 胶囊 + 静音键与电源同列）；
  电源按钮文字改为红色；盒端地址卡新增二维码（扫码直接带配对码）；手机端每次启动
  自动探测更新，有新版本时顶栏显示「有更新」标签，点击直接查看更新说明；
  修复直连模式长按 OK 无效（焦点在外层容器时向下查找
  可长按节点，与短按明确区分）；触控板切回方向盘时立即隐藏盒端光标（新增 `MHIDE` 命令）；
  修复当贝桌面等 TV 桌面短按 OK 不点击（`performGlobalAction` 假成功，改为焦点节点
  点击 + 合成手势）以及焦点落在大容器时点按落点错位。
- **v1.5.1**：应用快捷显示盒子端真实图标（新增 `ICON` 协议命令，按包名懒加载
  144x144 PNG，失败保持首字占位）。
- **v1.5.0**：OK 键支持短按/长按（蓝牙发按下-抬起，直连新增 `KEYLONG` 长按命令）；
  遥控器卡右上角新增飞鼠键，蓝牙 HID 模式用手机陀螺仪移动盒子光标，OK 为左键。
  升级说明：曾在旧版本配对过的盒子需删除配对并重新配对一次，飞鼠才会生效。
- **v1.4.1**：修复更新安装回调不弹出系统确认界面的缺陷（PendingIntent 需为 MUTABLE）；
  安装包改走 GitHub API 资产端点下载，超时放宽到 30 秒；盒子端更新按钮补 OK/ENTER 处理。
  v1.4.0 的 OTA 有上述缺陷，需手动安装一次 v1.4.1 才能正常使用应用内更新。
- **v1.4.0**：两端新增应用内检查更新，读取 GitHub 最新 Release 并下载对应 APK，
  由系统安装界面完成升级；盒子端更新后服务自动恢复。
- **v1.3.0**：应用快捷动态化，`LIST` 同时返回应用名（`包名|base64(应用名)`），
  手机端动态展示盒子全部可启动的第三方应用；盒子端补 `<queries>` 适配
  Android 11+ 包可见性。
- **v1.2**：盒端去 root，改用无障碍服务注入；连接方式与蓝牙目标按设备记忆；
  蓝牙返回/主页改用消费者控制（`AC Back` / `AC Home`）。
- **v1.1**：移除手机端 ADB 控制，保留「直连 + 蓝牙」两种方式。

## 开发文档

- 需求与验收记录：[spec/](spec/README.md)
- 自动测试与手工验收清单：[tests/](tests/README.md)
- 所有会话的开发规范：[AGENTS.md](AGENTS.md)

## 许可

本项目采用 [PolyForm Noncommercial License 1.0.0](LICENSE)：
允许个人学习、研究、测试以及非商业组织使用，**禁止任何商业用途**。
分发或修改后的作品必须保留本许可与版权声明。

---

<div align="center">

如果这个项目对你有帮助，欢迎点一个 Star。

[GitHub Releases](https://github.com/mst8222/tv-remote/releases) • [Gitee Releases](https://gitee.com/moston/tv-remote/releases) • [Issues](https://github.com/mst8222/tv-remote/issues)

<sub>TV 遥控器 · 仅限非商业使用</sub>

</div>
