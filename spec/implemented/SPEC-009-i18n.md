# SPEC-009 中英文语言切换

## 元信息
- 状态：implemented
- 创建：2026-10-21
- 更新：2026-10-21
- 优先级：medium

## 变更记录
- 2026-10-21 创建
- 2026-10-21 移入 developing，开始实现
- 2026-10-21 实现完成并真机验收通过，移入 implemented
- 2026-10-21 缺陷修复：「跟随系统」在中文系统上显示英文。根因是取
  `Activity` base Resources 的 `getLocales().get(0)` 判断系统语言，而该机应用资源里的
  localeList 顺序为 `[en_US, zh_CN-Hans, de_BE]`，与系统（`LocaleList.getDefault()`
  = `[zh_CN-Hans, de_BE, en_US]`）不一致。改为 `LocaleList.getDefault()` 取系统主语言；
  中文用系统 locale，英文锁定 English，其他语言回落中文。Note20（中文系统）与
  模拟器（en-US）双向复测通过。

## 背景与目标

SPEC-008 已把两端界面文案资源化（默认资源为中文）。本 spec 补全英文文案并实现语言切换：
默认跟随系统语言，用户可在应用内手动锁定（跟随系统 / 中文 / English），切换即时生效。

## 功能需求（FR）

- FR-9-1 两模块 `values-en/strings.xml` 覆盖全部字符串资源（含报错文案英文版），
  不遗漏 Dialog/Toast/通知文案。
- FR-9-2 默认跟随系统语言：系统中文则中文界面，系统英文则英文界面，其他语言回落
  中文（默认资源）。
- FR-9-3 手机端头部提供语言切换按钮，点击弹单选对话框：跟随系统 / 中文 / English，
  当前项选中高亮。
- FR-9-4 选择后立即生效（重建 Activity 即可，无需重启进程），偏好持久化存
  SharedPreferences（`lang` 取值 system|zh|en，默认 system），重启 App 后保持。
- FR-9-5 手动锁定语言后，界面、对话框、Toast、报错文案全部按所选语言显示。
- FR-9-6 盒子端仅资源化并跟随盒子系统语言，不提供应用内切换入口。

## 非功能需求（NFR）

- NFR-9-1 不引入 AppCompat / AndroidX，采用 attachBaseContext + createConfigurationContext
  实现（API 26 可用），保持零依赖。
- NFR-9-2 切换语言不重启进程，仅重建 Activity。

## 验收标准

- [x] AC-1 系统语言为英文时，两端 App 首次打开即为英文界面。
- [x] AC-2 手动切换三态（跟随系统 / 中文 / English）各自生效，重启 App 后保持所选状态。
- [x] AC-3 切换后对话框、Toast、报错文案（触发一次连接失败验证）随语言变化，无中英混排。
- [x] AC-4 英文系统的手机上选择「中文」后界面强制显示中文。
- [x] AC-5 协议回归 `run.ps1` 全绿（51 用例 0 失败）。

## 边界与已知限制

- 盒子端语言取决于盒子系统语言设置，无法在应用内切换。
- 动态文本（设备名、蓝牙设备名、异常原始消息等来自系统或代码的文本）不翻译，保持原样
  （如状态栏「Action failed: 握手失败」中「握手失败」来自 AdbClient 异常消息）。
- 仅支持中英两种语言，其他系统语言回落中文默认资源。
- 语言列表含次选语言的设备上，「跟随系统」必须用系统主 locale
  （`LocaleList.getDefault()`），不能读应用 Resources 的 localeList：实测 Note20 的
  应用资源顺序为 `[en_US, zh_CN-Hans, de_BE]`，与系统顺序相反，会把中文系统判成英文。

## 实现映射（implemented 后填写）

- 英文资源：app/res/values-en/strings.xml、server/res/values-en/strings.xml（全量覆盖，
  应用快捷名按边界约定保持原名）
- 语言切换机制：app/src/com/koco/tvremote/MainActivity.java `attachBaseContext()`——
  读 SharedPreferences `lang`（system|zh|en）；system 时取
  `LocaleList.getDefault().get(0)`（系统主语言）按语言分流：zh → 系统 locale、
  en → English、其他 → 中文；zh/en 手动锁定直接 setLocale，再
  createConfigurationContext 包装
- 切换入口：buildHeader() 右侧「中/EN」固定文案按钮（不随语言变化，一眼可辨）→
  `showLangDialog()` 单选对话框，选择后写 sp 并 recreate() 即时生效
- 构建：python build.py app / server，TVRemote.apk 117KB、TVRemoteServer.apk 97KB

## 测试映射

- 手工：tests/manual/SPEC-009-i18n.md（已执行，5/5 通过；含中文系统「跟随系统」复测）
- 自动：powershell -File tests\protocol\run.ps1（51/51 通过）
