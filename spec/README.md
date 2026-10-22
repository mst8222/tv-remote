# spec 体系 — 需求与规格管理约定

本目录是 tv-remote 项目的唯一需求来源。每个功能/改动都必须有对应的 spec 文档，
先有 spec 再有代码。所有会话（人或 AI）必须遵循本约定，详见根目录 `AGENTS.md`。

## 目录结构（按状态分目录）

```
spec/
├── planned/       # 已规划、未开工：需求已写清，等待排期实现
├── developing/    # 开发中：正在写代码，spec 可能随实现微调
├── implemented/   # 已实现并验收通过：代码合入、测试通过、有验证记录
└── discarded/     # 已废弃：不做或换方案。文件保留不删，头部必须写废弃原因
```

状态流转：`planned → developing → implemented`，任何状态可转入 `discarded`。

流转操作 = 把文件移动到目标目录 + 更新文档头部元信息（状态、日期、变更记录）。
**移动文件，不要复制**：任何时刻一个 spec 只能存在于一个状态目录中。

## 文件命名

`SPEC-<编号>-<英文短名>.md`，编号全局唯一、只增不减，废弃的编号不复用。
例：`SPEC-001-lan-direct.md`。

## spec 文档必填结构

每个 spec 必须包含以下章节，缺一不可：

```markdown
# SPEC-XXX 标题

## 元信息
- 状态：planned | developing | implemented | discarded
- 创建：YYYY-MM-DD
- 更新：YYYY-MM-DD（每次修改必须追加，写明改了什么）
- 优先级：high | medium | low

## 变更记录
- YYYY-MM-DD 创建
- YYYY-MM-DD <修改内容>

## 背景与目标
<为什么做这件事，解决什么问题>

## 功能需求（FR）
<逐条列出，每条编号 FR-X-Y，必须是可验证的陈述>

## 非功能需求（NFR）
<逐条列出，每条编号 NFR-X-Y：性能 / 可靠性 / 安全 / 兼容性等>

## 验收标准
<逐条可执行的验收项，与 FR/NFR 一一对应或归纳；实现完成后逐条勾选并记录验证方式>

## 边界与已知限制
<空值、超长、非法输入、失败路径、明确不做的场景>

## 实现映射（implemented 后填写）
<代码文件、模块与 FR 的对应关系>

## 测试映射
<对应的测试文件：tests/protocol/...（自动）或 tests/manual/SPEC-XXX-....md（手工）>
```

## 与测试的对应关系

- 每个 spec 必须在 `tests/` 中有对应测试，二选一或两者都有：
  - **自动测试**：`tests/protocol/`（纯 Java 协议一致性测试，`run.ps1` 一键执行）；
  - **手工验收清单**：`tests/manual/SPEC-XXX-<短名>.md`（对照验收标准逐条列步骤与预期）。
- 规划中的 spec（planned）允许先把手工清单写好（TDD：先定标准再实现）。
- 验收通过 = 验收标准逐条满足 + 对应测试全部通过，缺一不得转入 implemented。

## 当前索引

| 编号 | 标题 | 状态 |
|---|---|---|
| SPEC-001 | 局域网直连遥控（核心） | implemented |
| SPEC-002 | ADB 模式 | discarded |
| SPEC-003 | 手机端 UI 与设备管理 | implemented |
| SPEC-004 | 构建体系 | implemented |
| SPEC-005 | 蓝牙 HID 模式打通 | developing |
| SPEC-006 | 直连协议加固 | developing |
| SPEC-007 | 直连设备发现与多盒子管理 | developing |
| SPEC-008 | GUI 整体设计与多尺寸适配 | developing |
| SPEC-009 | 中英文语言切换 | implemented |
| SPEC-010 | 断电重启自启动保障 | developing |
| SPEC-011 | 直连目标跟随设备切换 | developing |
| SPEC-012 | 界面视觉重设计与应用动态化 | developing |
| SPEC-013 | 移除手机端 ADB 控制方案 | developing |
| SPEC-014 | 盒端无 root 控制（无障碍服务注入） | implemented |
| SPEC-015 | 按设备记忆连接方式 | developing（AC-5/AC-6 待手工） |
| SPEC-016 | 应用快捷动态列表 | implemented |
| SPEC-017 | 基于 GitHub Release 的 OTA 更新 | implemented |
| SPEC-018 | OK 键长按与陀螺仪飞鼠 | developing |
| SPEC-019 | 应用快捷方式显示真实图标 | developing |
| SPEC-020 | 盒端内置 Web 遥控页（PWA） | developing |
| SPEC-021 | 直连飞鼠协议（Web 端空气鼠标） | developing |
| SPEC-022 | 蓝牙 HID 跨端边界与降级引导 | developing |
| SPEC-023 | 手机端触控板（直连 + 蓝牙） | developing |
| SPEC-024 | 盒端显示网页遥控二维码 | developing |
| SPEC-025 | 网页设备管理与触控板（删除飞鼠） | developing |
| SPEC-026 | 直连方向键焦点边界翻页 | developing |
| SPEC-027 | 启动自动探测更新与「有更新」标签 | developing |
| SPEC-028 | 触控板绝对触摸（点哪打哪） | developing |
| SPEC-029 | 触控板双模式（触摸 / 鼠标兜底） | developing |
| SPEC-030 | OTA 双源探测（GitHub + Gitee） | developing |

> 新增/修改 spec 后必须同步维护此索引表。
