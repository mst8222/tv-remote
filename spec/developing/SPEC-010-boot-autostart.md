# SPEC-010 断电重启自启动保障

## 元信息
- 状态：developing
- 创建：2026-10-21
- 更新：2026-10-21
- 优先级：high

## 变更记录
- 2026-10-21 创建
- 2026-10-21 移入 developing；AC-2/AC-3/AC-4/FR-10-4 真机验证通过，AC-1（断电冷启动）留待用户手工

## 背景与目标

盒子端 BootReceiver（BOOT_COMPLETED / QUICKBOOT_POWERON / LOCKED_BOOT_COMPLETED）
与 START_STICKY 前台服务已实现，v1.0 阶段验证过 reboot 后重启自愈，但从未 spec 化，
且「断电冷启动」场景未单独验收。本 spec 把自启动行为固化为需求并完成真机验收；
仅在验收失败时进行代码加固。

## 功能需求（FR）

- FR-10-1 盒子冷启动（断电后上电）后，ServerService 自动进入前台运行，
  TCP 8899 可接受连接，UDP 8898 恢复每 3 秒广播。
- FR-10-2 热重启（系统 reboot / QUICKBOOT_POWERON）后同样自动恢复。
- FR-10-3 服务进程被系统杀死后由 START_STICKY 自动重建。
- FR-10-4 自启动后无需重新配对：已配对手机保存的 token 继续有效，直连可直接使用。

## 非功能需求（NFR）

- NFR-10-1 上电到服务可用的等待时间不超过 2 分钟（受盒子开机速度影响）。
- NFR-10-2 自启动逻辑不依赖手机端任何操作。

## 验收标准

- AC-1 断电 - 上电 - 2 分钟内，手机直连模式直接按键控制成功（免重新配对）。
  【手工，真机断电】
- AC-2 adb reboot 后 2 分钟内 TCP 8899 恢复可连、UDP 8898 恢复广播。【脚本辅助】
- AC-3 adb shell 强杀服务进程后数秒内自动拉起（START_STICKY）。【脚本辅助】
- AC-4 协议回归 `run.ps1` 全绿（0 失败；用例数 43→41 因 SPEC-011 短连接改造删减 2 条旧断言）。

## 边界与已知限制

- 应用被 force-stop（系统设置里强行停止）后，Android 平台不再向其投递
  BOOT_COMPLETED，自启动失效——这是平台行为，不视为缺陷；操作指引：
  安装后手动打开一次「TV遥控服务」即可恢复自启。
- LOCKED_BOOT_COMPLETED 仅对 directBootAware receiver 在 FBE 设备可达；本盒子固件
  非 FBE，保留现状不做 directBootAware 改造（避免 device-protected storage 复杂度）。
- 部分 TV ROM 的「自启动管理 / 后台清理」可能拦截开机广播或杀后台；若验收失败，
  第一排查项为系统设置中允许自启动，结论写入 README 坑列表。

## 实现映射（implemented 后填写）

（待实现后填写）

## 测试映射

- 手工：tests/manual/SPEC-010-boot-autostart.md（AC-1 断电项为真机手工）
- 脚本辅助：AC-2 / AC-3 用 adb 与端口探测执行（步骤写在手工清单中）
