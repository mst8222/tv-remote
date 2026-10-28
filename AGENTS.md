# AGENTS.md — 项目实施规范守则

所有会话（人类或 AI）在本项目工作前必须阅读并遵循本文件。
项目速览见 [README.md](README.md)（中文）/ [README.en.md](README.en.md)（English），
需求与规格体系见 [spec/README.md](spec/README.md)，
测试体系见 [tests/README.md](tests/README.md)。

## 一、spec 体系（强制）

需求唯一来源是 `spec/`，**先有 spec 再有代码**。

1. 状态目录：`spec/planned/`（已规划）→ `spec/developing/`（开发中）→
   `spec/implemented/`（已实现并验收）；
   任何状态可转入 `spec/discarded/`（已废弃，文件保留，头部必须写废弃原因）。
2. 流转操作 = **移动文件**（不是复制）+ 更新文档头部元信息与变更记录。
   任何时刻一个 spec 只能存在于一个状态目录。
3. spec 文件命名 `SPEC-<编号>-<英文短名>.md`，编号全局唯一只增不减，废弃不复用。
4. spec 必填章节：元信息（状态/日期/优先级）、变更记录（每次修改追加）、
   背景与目标、功能需求（FR，可验证陈述）、非功能需求（NFR）、
   验收标准（可执行、逐条）、边界与已知限制、实现映射（implemented 后）、测试映射。
5. 新增/修改 spec 后必须同步 `spec/README.md` 的索引表。
6. 验收通过 = 验收标准逐条满足 + 对应测试全部通过。缺一不得转 implemented。

## 二、开发流程（TDD，强制）

1. 新需求：在 `spec/planned/` 写 spec（FR/NFR/验收标准写清），
   同时在 `tests/manual/` 写好验收清单（标记待实现）。
2. 开工：把 spec 移入 `developing/`；能自动化的需求先在 `tests/protocol/` 补用例
   （先红后绿）。
3. 实现：代码与 MockBoxServer（协议参照实现）同步演进，保持语义一致。
4. 自验：跑 `powershell -File tests\protocol\run.ps1`（回归 0 失败）+
   手工清单涉及真机的项逐条执行并填写结果。
5. 验收通过后：spec 移入 `implemented/`，填写实现映射、勾掉验收标准、
   在变更记录追加日期与内容；手工清单标记为已执行。
6. 放弃：移入 `discarded/`，头部写明原因与结论（尤其失败结论，防止重复投入）。

## 三、测试约定（强制）

- 每个 spec 必须有测试映射：自动（tests/protocol/）或手工清单（tests/manual/），
  可以两者都有。
- 直连协议（`ServerService` / `LanClient` / `MockBoxServer`）的任何改动必须跑
  run.ps1 回归；改协议先改 spec。
- MockBoxServer 是盒子端协议的参照实现，从 `ServerService.dispatch` 迁移而来；
  盒子端行为变化时必须同步修改（copy 再改写，不要凭记忆重写）。
- 提交代码前确保自动测试全绿；手工项未验完不得把 spec 转 implemented。

## 四、代码规范

- 注释用中文，UTF-8 编码；代码与文档中不使用 emoji。
- Windows 环境开发；构建命令 `python build.py app|server`（手工编译链，不用 Gradle）。
- 不考虑 fallback、不考虑旧版本兼容（两端 APK 同批发布、无老用户）；
  出问题优先"消除触发场景"，不堆兜底。
- 代码迁移（如 ServerService → MockBoxServer）：先 copy 再改写，不凭记忆重写。
- 不重复造轮子：动手前先搜本项目是否已有同类实现（tools/ 下有 ADB/TLS 原型）。
- 不过度设计：三行重复代码好过一个不必要的抽象。
- 不写与任务无关的测试脚本与说明文档；交付文档只放 spec/ 与 tests/ 约定要求的位置。

## 五、已知坑（改代码前必读）

详见 README.md「踩过的坑」一节，要点：

1. 盒子 su 是 toybox 版：`su root sh -c "命令"`，不支持 `su -c`；monkey 必须经 sh -c。
2. 该固件 Settings 无 LAUNCHER 入口，用 `am start -a android.settings.SETTINGS`。
3. 盒子界面不响应触摸，只能 keyevent 导航。
4. App 内弹 Dialog 必须 `runOnUiThread`。
5. NsdManager 同一 listener 不能注册两个服务类型。
6. 手工编译：`-source 8 -target 8`；lambda 需 core-lambda-stubs.jar 进
   bootclasspath；`java.util.Base64` 是 API 26+。手机端 minSdk 26；盒端 minSdk 22（SPEC-035），
   故盒端代码不得使用 `java.util.Base64`，`WebServer` 自带 `b64Encode/b64Decode`。
7. keys/ 与 KeyConst.java 不入库（.gitignore），release.jks 丢失则无法覆盖升级。
