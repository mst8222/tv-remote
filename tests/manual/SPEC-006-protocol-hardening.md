# SPEC-006 直连协议加固 — 手工验收清单

- 对应 spec：`spec/developing/SPEC-006-protocol-hardening.md`
- 状态：已实现，自动项已执行；真机回归与错误文案待验收
- 结果标记：P 通过 / F 失败 / N 未执行

## 自动用例（tests/protocol，已接入 run.ps1）

### TC-006-A1 白名单外 keycode
`KEY <token> 9999` / `KEY <token> abc` / `KEY <token> 23;reboot`：
纯数字越界与未知命令返回 `ERR cmd`，无法解析的参数返回 `ERR arg`。
实际：`key_unsupported` / `key_unsupported_numeric` 通过　结果：P

### TC-006-A2 非法包名
`APP <token> "bad pkg"`、分号、超长包名 → `ERR arg`。
实际：`app_invalid_pkg` / `app_pkg_too_long` 通过　结果：P

### TC-006-A3 文本长度
解码后 256 字符 → `OK`；257 字符 → `ERR toolong`；base64 段 > 2048 → `ERR toolong`。
实际：`text_boundary_256_ok` / `text_large_err_toolong` / `text_b64_too_long` 通过　结果：P

### TC-006-A4 PAIR 限速
同一 IP 连续 5 次错误码 → 第 6 次起（含正确码）`ERR locked`。
实际：`pair_fail_1..5` / `pair_locked_after_5` / `pair_correct_while_locked` 通过　结果：P

### TC-006-A5 回归
现有协议用例全部继续通过。
实际：`powershell -File tests\protocol\run.ps1` 92 项 + Web API 38 项全绿　结果：P

## 手工用例

### TC-006-01 真机回归（AC-6）
步骤：加固后的 APK 全量跑一遍 SPEC-001 手工清单按键/文本/应用项。
预期：全部照常工作。
实际：（待填）　结果：N

### TC-006-02 错误文案（FR-6-6）
步骤：分别触发 badcode / locked / toolong / auth / exec。
预期：手机端有对应可理解文案。
实际：（待填）　结果：N
