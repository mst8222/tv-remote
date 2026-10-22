# SPEC-006 直连协议加固

## 元信息
- 状态：developing
- 创建：2026-10-21
- 更新：2026-10-22（开工；按无障碍架构校正 keycode 错误码与 ERR noroot）
- 优先级：high

## 变更记录
- 2026-10-21 创建：来自 SPEC-001「边界与已知限制」中的风险项立项
- 2026-10-22 开工：SPEC-014 已用无障碍服务取代 su，白名单外 keycode 的错误码
  由 `ERR arg` 改为 `ERR cmd`（与现有实现、tc_key_unsupported 一致）；
  删除 FR-6-5 中已不存在的 `ERR noroot`；补充包名长度与文本长度用例映射。
- 2026-10-22 实现：盒端与 Mock 同步加入包名校验、文本上限、按 IP 配对锁定；
  手机端补充 locked / toolong 文案；协议回归 92 项全绿。

## 背景与目标

v1.0 直连协议以「先跑通」为目标，存在若干局域网内的安全/健壮性缺口。
本 spec 在不破坏协议兼容（手机端与盒子端同批升级）的前提下加固。
因为两端 APK 由本项目同时发布、无历史版本负担（无老用户），可以直接改协议，
不需要服务端兼容旧客户端。

## 功能需求（FR）

- FR-6-1 keycode 白名单：盒子端仅接受本遥控器用到的 keycode 集合
  （3/4/19/20/21/22/23/24/25/26/82/164），其余一律 `ERR cmd`
  （与 SPEC-014 无障碍架构的 RES_UNSUPPORTED 一致），不做任何命令拼接。
- FR-6-2 包名校验：`APP` 的包名必须匹配 `[A-Za-z0-9._]+` 且长度 ≤ 128，
  否则 `ERR arg`；拼进命令前不再需要其他清洗。
- FR-6-3 文本长度上限：`TEXT` 解码后长度 ≤ 256 字符（UTF-8），超长返回
  `ERR toolong`；base64 段长度 ≤ 2048，超长直接拒绝。
- FR-6-4 PAIR 速率限制：同一 IP 连续配对失败 5 次后，锁定 5 分钟（期间一律
  `ERR locked`）；失败计数按 IP 记录，重启后清零（可接受）。
- FR-6-5 命令执行反馈：注入失败仍返回 `ERR exec`，不再区分 `ERR noroot`
  （SPEC-014 已移除 su 依赖，该错误码不存在）。
- FR-6-6 手机端同步：错误分支在 UI 上有对应文案（badcode/locked/toolong/auth/exec）。

## 非功能需求（NFR）

- NFR-6-1 加固后所有合法路径行为与 SPEC-001 完全一致（白名单内的 keycode、
  合法包名、长度内的文本照常工作）。
- NFR-6-2 防注入：任一参数拼入 shell 命令前均经过白名单或格式校验，
  不再依赖字符剥离（单引号删除规则可随白名单一并收紧）。
- NFR-6-3 速率限制不增加正常配对操作的延迟与步骤。
- NFR-6-4 加固实现不引入第三方库，纯 JDK/平台 API。

## 验收标准

- AC-1 自动测试：SPEC-001 的 30+ 协议用例全部继续通过（回归）。 【自动】
- AC-2 自动测试：keycode 白名单外的值（如 9999、`abc`、`23; rm`）返回 `ERR cmd`，
  纯数字但越界的值（如 9999）不得被注入。 【自动】
- AC-3 自动测试：非法包名（含空格/分号/超长）返回 `ERR arg`。 【自动】
- AC-4 自动测试：超长 TEXT（解码后 > 256 字符或 base64 段 > 2048）返回
  `ERR toolong`，边界值 256 字符通过。 【自动】
- AC-5 自动测试：同一 IP 连续 5 次 PAIR 错误码后第 6 次返回 `ERR locked`，
  正确码在锁定期间也返回 `ERR locked`。 【自动】
- AC-6 手工：真机上白名单内按键、合法应用、正常长度中文文本全部照常工作。 【手工】

## 边界与已知限制

- 锁定按 IP 记录，NAT 后多设备共享同一出口 IP 时会互相影响（局域网自用可接受）。
- 不做 TLS 加密传输（局域网内抓包可见 token 的风险本版接受，靠局域网边界保护；
  若未来需要，另行立项）。
- 白名单外 keycode 被拒绝是有意行为，遥控器 UI 不会发出白名单外的值。

## 实现映射

- `server/src/com/koco/tvremoteserver/ServerService.java`：`validPkg` 校验、
  `MAX_TEXT_CHARS`/`MAX_TEXT_B64` 上限、`pairFails`/`pairLockUntil` 按 IP 锁定；
  `dispatch(line, peerIp)` 供 TCP 与 Web 共用。
- `app/src/com/koco/tvremote/Errors.java` 与 `app/res/values*/strings.xml`：
  新增 locked / toolong 文案。
- `tests/protocol/MockBoxServer.java`：同步包名、文本、配对锁定语义。
- `tests/protocol/ProtocolTest.java`：新增 `*_pkg_*`、`text_boundary_256_ok`、
  `text_b64_too_long`、`pair_lockout` 等用例。

## 测试映射

- 自动：tests/protocol/ProtocolTest.java 中标注 [SPEC-006] 的用例
  （spec 转 developing/implemented 时与 MockBoxServer 参照实现同步更新）
- 手工：tests/manual/SPEC-006-protocol-hardening.md
