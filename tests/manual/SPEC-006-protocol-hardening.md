# SPEC-006 直连协议加固 — 手工验收清单（待实现）

- 对应 spec：spec/planned/SPEC-006-protocol-hardening.md
- 状态：TDD 先行清单；自动部分随 spec 进入 developing 时加入 ProtocolTest（标注 [SPEC-006]）
- 结果标记：P 通过 / F 失败 / N 未执行

## 自动用例（加入 tests/protocol）

### TC-006-A1 白名单外 keycode
`KEY <token> 9999` / `KEY <token> abc` / `KEY <token> "23;reboot"` → `ERR arg`，
参照端记录的命令不含这些值。
### TC-006-A2 非法包名
`APP <token> "bad pkg"` / `APP <token> "a;b"` / 超长包名 → `ERR arg`。
### TC-006-A3 文本长度
解码后 256 字符 → `OK`；257 字符 → `ERR toolong`；base64 段 > 2048 → 拒绝。
### TC-006-A4 PAIR 限速
同一 IP 连续 5 次错误码 → 第 6 次起（含正确码）`ERR locked`。
### TC-006-A5 回归
SPEC-001 现有 43 项用例全部继续通过（白名单内行为不变）。

## 手工用例

### TC-006-01 真机回归（AC-6）
步骤：加固后的 APK 全量跑一遍 SPEC-001 手工清单按键/文本/应用项。
预期：全部照常工作。
实际：（待填）　结果：（P/F/N）

### TC-006-02 错误文案（FR-6-6）
步骤：分别触发 badcode / locked / toolong / auth / exec。
预期：手机端有对应可理解文案。
实际：（待填）　结果：（P/F/N）
