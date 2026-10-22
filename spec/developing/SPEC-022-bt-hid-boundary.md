# SPEC-022 蓝牙 HID 跨端边界与降级引导

## 元信息

- 状态：developing
- 创建：2026-10-22
- 更新：2026-10-22（能力矩阵里的 Web 端口随 SPEC-020 改为 17880/17843）
- 优先级：medium

## 变更记录

- 2026-10-22 创建：SPEC-020 明确 Web 端不含蓝牙后，为蓝牙 HID 单独立项。
  本 spec 只定义「哪些端能用 HID、不能用时如何引导」，不改现有 HID 实现；
  实现细节与验收仍由 SPEC-005（模式打通）、SPEC-015（按设备记忆）、
  SPEC-018（长按与飞鼠）负责。
- 2026-10-22 开工：能力矩阵随 Web 遥控页落地，补充不支持机型的浏览器替代引导。
- 2026-10-22 端口同步：Web 端口由 8080/8443 改为 17880/17843，矩阵与降级文案同步。
- 2026-10-22 实现：Android 不支持 HID 弹窗加入浏览器地址，中英文 README
  增加能力矩阵与边界；协议与两端构建回归通过。

## 背景与目标

蓝牙 HID 是 Android 手机端在无网络、盒子未装服务或局域网隔离时的关键通道，
但它依赖 Android 的 `BluetoothHidDevice`（API 28+）：iOS 没有公开的 HID 设备接口，
HarmonyOS NEXT 不提供同等能力，浏览器也没有 HID 设备接口。

多端方案落地后，容易出现三种错误预期：网页端以为能连蓝牙、iOS/鸿蒙用户找不到入口、
Android 端在不支持 HID 的机型上只看到「不支持」而没有替代方案。本 spec 把这些边界
固定下来，避免各端重复尝试不可能的能力。

目标：

1. 明确蓝牙 HID 仅属于 Android 原生端，其他端只走局域网 Web；
2. 在不支持 HID 的 Android 机型上给出可执行的替代路径；
3. 把能力矩阵写进文档，后续迭代不再为「网页/iOS 蓝牙」投入。

## 功能需求（FR）

- FR-22-1 能力矩阵固定为：

  | 端 | 直连（局域网） | 蓝牙 HID | 飞鼠 |
  | --- | --- | --- | --- |
  | Android 原生 App | 支持（8899） | 支持（API 28+，SPEC-005） | HID 陀螺仪（SPEC-018） |
  | Web 页（Android / iOS / HarmonyOS NEXT / PC） | 支持（17880/17843） | 不提供 | 直连协议飞鼠（SPEC-021，可选） |
  | 原生 iOS / HarmonyOS NEXT App | 不单独开发 | 不提供 | 不提供 |

- FR-22-2 Web 页面不出现蓝牙按钮、不调用任何蓝牙 API；盒子离线或地址不可达时，
  页面提示替代路径：改用 Android 手机端 App 的蓝牙模式，或使用 2.4G 遥控器。
- FR-22-3 Android 原生端现有 HID 行为不变：注册、连接、按键映射、模式记忆、
  长按与飞鼠全部沿用 SPEC-005 / SPEC-015 / SPEC-018，本 spec 不重复实现，
  也不修改报告描述符。
- FR-22-4 Android 端在不支持 HID 的机型（API < 28 或适配器无 HID device profile）上，
  沿用 SPEC-005 FR-5-6 的弹窗，并在文案中增加「用浏览器访问盒端网页」的替代路径；
  地址取当前设备的局域网地址，不可得时提示到盒端界面查看。
- FR-22-5 中英文 README 的「已知限制」与「常见问题」同步加入能力矩阵与边界：
  iOS、HarmonyOS NEXT 只能走局域网 Web；蓝牙 HID 仅 Android；
  曾在旧版本配对过的盒子需删除配对并重新配对才能使用新 HID 描述符（SPEC-018 结论）。
- FR-22-6 版本说明中标注各端能力范围，不新增权限、不改变现有模式记忆键
  （`mode_<host>` / `bt_<host>`）。

## 非功能需求（NFR）

- NFR-22-1 新增或修改的提示文案中英双语，随系统语言切换。
- NFR-22-2 提示不阻塞主流程：关闭提示后可继续使用当前可用模式。
- NFR-22-3 不新增蓝牙、定位等权限；不改变现有前台服务与保活行为。
- NFR-22-4 已有的 Android 端 HID 回归（配对、连接、切设备、飞鼠）不受影响。

## 验收标准（AC）

- AC-1 Web 页面在 Android Chrome、iPhone Safari、HarmonyOS NEXT 浏览器上
  均不出现蓝牙入口，只提供局域网遥控。
- AC-2 盒子离线时打开 Web 页面，提示包含「Android App 蓝牙模式」与
  「2.4G 遥控器」两条替代路径。
- AC-3 在不支持 HID 的 Android 设备/模拟器上点击蓝牙，弹窗包含
  「浏览器访问盒端网页」的说明；关闭弹窗后其他功能正常。
- AC-4 Android 端蓝牙模式回归：Note20 → H96 按键、长按、飞鼠与
  SPEC-005 / SPEC-018 的手工结论一致。
- AC-5 中英文 README 均包含 FR-22-1 的能力矩阵与 FR-22-5 的边界说明。
- AC-6 `powershell -File tests\protocol\run.ps1` 全绿；两端构建通过。

## 边界与已知限制

- 不实现 iOS / HarmonyOS NEXT 的 HID：平台没有公开 API，iOS 需要 MFi 硬件方案。
- Web Bluetooth 只能做 GATT 客户端，不能把手机注册为 HID 设备，因此网页端不做尝试。
- 本 spec 不修改 SPEC-005 的实现与验收项；后者仍处于 developing，
  剩余手工项（后台恢复、盒子关蓝牙提示、不支持机型提示、可发现配对入口）按其自身清单执行。
- 盒子端首次配对会缓存 HID 描述符，旧配对不删除时飞鼠无效；该限制不因本 spec 改变。
- Android 端 App 退后台会被系统注销 HID 注册，无法规避，只能保持前台使用。
- 不提供「自动在多端之间选择最佳通道」的逻辑；通道选择仍由用户显式操作。

## 实现映射

- `app/src/com/koco/tvremote/MainActivity.java`：`webAddrText()` 为不支持 HID
  的机型弹窗补充浏览器替代地址；弹窗文案参数改为 `%1$s` + `%2$s`。
- `app/res/values*/strings.xml`：新增 `bt_web_addr` / `bt_web_addr_unknown`，
  三选一替代路径。
- `server/assets/web/`：无蓝牙入口；离线文案包含 Android App 蓝牙模式与 2.4G 遥控器。
- `README.md` / `README.en.md`：跨端能力矩阵、Web 端无蓝牙、证书与离线限制。

## 测试映射

- 自动：无新增协议用例；沿用 `tests/protocol/run.ps1` 做回归。
- 手工：`tests/manual/SPEC-022-bt-hid-boundary.md`
  （网页无蓝牙入口、离线替代提示、不支持机型引导、README 检查）。
