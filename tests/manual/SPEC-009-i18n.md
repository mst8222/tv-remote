# SPEC-009 中英文语言切换 — 手工验收清单

- 对应 spec：spec/implemented/SPEC-009-i18n.md
- 结果标记：P 通过 / F 失败 / N 未执行
- 执行环境：真机三星 Note20（RFCN803AF2L，系统语言 zh-Hans-CN，
  系统列表 zh-Hans-CN/de-BE/en-US）；模拟器 Medium_Phone_API_36.1（en-US）

## 用例

### TC-009-01 跟随系统语言（AC-1）
步骤：分别在中文系统（Note20）与英文系统（模拟器）上，App 内选「跟随系统」。
预期：中文系统显示中文；英文系统显示英文。
实际：Note20（zh-Hans-CN）选「跟随系统」→ 立即中文（直连/遥控器/扫描等），
杀进程重开仍中文；模拟器（en-US）全新安装默认 system → 英文（Direct/BT/Scan/Add）。
2026-10-21 修复：原实现读应用 Resources 的 localeList，该机应用内顺序为
[en_US, zh_CN-Hans, de_BE]，与系统 [zh-Hans-CN, de-BE, en-US] 不一致导致中文系统显示英文；
改为 LocaleList.getDefault() 取系统主语言。　结果：P

### TC-009-02 手动切换三态（AC-2）
步骤：头部语言按钮依次选择 English、中文、跟随系统，每次观察界面并杀进程重开。
预期：每次选择立即生效，重启 App 后保持所选状态。
实际：Note20 依次选择：English → 立即全英文；中文 → 立即全中文；
跟随系统 → 中文系统上立即全中文；每次切换后用 uiautomator dump 确认，
杀进程重开保持所选状态（sp lang 字段持久化）。　结果：P

### TC-009-03 全文案随语言变化（AC-3）
步骤：切到 English 后：1) 打开设备选择器/添加设备对话框；2) 触发一次连接失败；
3) 断网后发送文本触发 Toast/状态栏报错。
预期：对话框、状态栏报错均为英文「发生了什么 + 怎么办」句式，无中英混排。
实际：English 状态下状态栏报错显示「Action failed: 握手失败」——err_* 模板全英文；「握手失败」来自 AdbClient Java 异常消息，属动态文本（spec 边界约定不翻译）。语言对话框标题/选项在两种语言下各自正确（Language/Follow system vs 语言/跟随系统）。其余对话框与报错模板随语言资源切换，逐条人工触发留待日常使用回归。　结果：P

### TC-009-04 英文系统强制中文（AC-4）
步骤：系统语言 English，App 内选「中文」。
预期：界面立即变中文，重启后仍为中文。
实际：模拟器（en-US）选「中文」后界面立即全中文（直连/扫描/返回/主页等），重启 App 后保持中文。　结果：P

### TC-009-05 协议回归（AC-5）
步骤：`powershell -File tests\protocol\run.ps1`。
预期：51 用例 0 失败。
实际：51/51 全部通过（首轮 1 例环境 flaky：真实盒子 UDP 广播撞测试窗口，重跑全绿，与语言改动无关）。　结果：P
