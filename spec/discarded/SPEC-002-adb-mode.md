# SPEC-002 ADB 模式

> 废弃原因（2026-10-21）：用户要求手机侧去掉全部 ADB 控制方案，只保留直连与蓝牙。
> ADB 依赖盒子开启无线调试、首次人工确认公钥，且 Android 13 的 adbd 不接受
> App 内置公钥（Invalid base64 key），Fold3 实测无法握手。相关移除工作见
> SPEC-013；本文件仅作历史记录保留。

## 元信息
- 状态：discarded
- 创建：2026-10-21
- 更新：2026-10-21（移入 discarded，原因见上方）
- 优先级：medium

## 变更记录
- 2026-10-21 创建：从 v1.0 已验证实现提炼成文
- 2026-10-21 废弃：由 SPEC-013 移除手机端 ADB 控制方案，结论为不再维护 ADB 模式

## 背景与目标

手机内置一个手写的 ADB over TCP 协议客户端，直连盒子的 5555 端口执行 shell 命令。
ADB 模式功能强于直连（可装应用、截图、抓日志），但依赖盒子的开发者选项与
无线调试常开，作为直连模式的补充。

## 功能需求（FR）

- FR-2-1 ADB 握手：实现 CNXN / AUTH / OPEN / OKAY / WRTE / CLSE 消息，
  小端 24 字节头 + CRC32 + 命令取反校验。
- FR-2-2 RSA 认证：收到 AUTH_TOKEN 时用预置私钥（keys/adbkey.pk8 → 构建期生成
  KeyConst.java）做 SHA1withRSA 签名回 AUTH_SIGNATURE；设备不认识时上送
  AUTH_RSAPUBLICKEY（公钥按 ADB 格式编码：4B 长度 + 256B 小端模数 + 4B 指数）。
- FR-2-3 免确认连接：预置公钥已写入盒子 `/data/misc/adb/adb_keys`，日常连接
  不需要在电视上确认。
- FR-2-4 TLS 升级：安卓 11+ 无线调试会先回 A_STLS，客户端在同一 socket 上
  升级 TLSv1.3（客户端证书 = 同一对密钥生成的自签证书 keys/cert.der），
  再重新走一遍 CNXN；不支持 TLSv1.3 时回落通用 "TLS"。
- FR-2-5 shell 执行：OPEN `shell:<cmd>`，收集 WRTE 数据直至 CLSE，去除 `\r`
  后返回输出；连接断开时抛异常。
- FR-2-6 首次授权：设备未认识公钥时，电视端弹「允许调试」，用户确认后连接成功；
  超时未确认则报「首次连接：请在电视上点允许」。
- FR-2-7 设备连接：默认端口 5555；启动时自动连接当前设备并读取
  `getprop ro.product.model` 显示在状态栏。
- FR-2-8 命令发送：所有按键在非直连、非蓝牙模式下经 ADB 执行
  `input keyevent <code>`；文字经 `input text "..."`（空格转 `%s`、剔除双引号）；
  应用启动经 `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`。
- FR-2-9 NSD 扫描：同时发现 `_adb._tcp` 与 `_adb-tls-connect._tcp` 两种服务类型
  （每种类型独立 DiscoveryListener），9 秒后汇总去重展示，点击即添加并连接。

## 非功能需求（NFR）

- NFR-2-1 连接超时 4–5 秒（connect 5s / 命令 4–6s），超时给出可读错误。
- NFR-2-2 无第三方依赖：协议手写，仅用 JDK + 平台 API。
- NFR-2-3 私钥安全：KeyConst.java 由构建脚本从 keys/adbkey.pk8 生成，
  不入库（.gitignore），不手工编辑。
- NFR-2-4 线程安全：connect/exec/close 均 synchronized，UI 经单线程 Executor
  串行下发命令。

## 验收标准

- AC-1 预置密钥的盒子：打开 App 自动连接，状态栏绿色显示「已连接 · <型号>」。 【手工】
- AC-2 全新盒子（无预置公钥）：首次 ADB 连接时电视弹「允许调试」，确认后连接成功。 【手工】
- AC-3 安卓 11+ 开启无线调试的设备（TLS 路径）：扫描发现并可连接执行命令。 【手工】
- AC-4 非直连模式下按方向键/音量等，电视有对应反应（ADB 路径）。 【手工】
- AC-5 盒子关闭无线调试后，按键提示「未连接」，重新打开后再次按键自动恢复。 【手工】
- AC-6 扫描能列出同网段开了无线调试的设备，点击添加后状态栏显示已连接。 【手工】
- AC-7 手动添加「IP:端口」格式设备（端口缺省 5555）可连接。 【手工】

## 边界与已知限制

- 依赖开发者选项 + 无线调试常开；盒子重启后无线调试可能关闭，需手动重开
  （这正是直连模式成为主力原因）。
- TLS 信任管理器不校验服务端证书（局域网自用场景的取舍，记录在案）。
- 首次授权路径在电视端需要遥控确认，属于一次性流程。
- 扫描依赖 NSD，路由器禁用 mDNS 时扫描无效，只能手动添加。

## 实现映射

| 需求 | 文件 |
|---|---|
| FR-2-1 ~ FR-2-5 | app/src/com/koco/tvremote/AdbClient.java |
| FR-2-6 ~ FR-2-8 | app/src/com/koco/tvremote/MainActivity.java（ADB 分支） |
| FR-2-9 | MainActivity.java（startScan/makeDiscovery） |
| KeyConst 生成 | build.py |

## 测试映射

- 手工：tests/manual/SPEC-002-adb-mode.md（协议层无盒子无法自动化，
  tools/AdbTest.java、TlsTest.java 为历史验证原型）
