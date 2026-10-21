# SPEC-010 断电重启自启动保障 — 手工验收清单

- 对应 spec：spec/developing/SPEC-010-boot-autostart.md
- 结果标记：P 通过 / F 失败 / N 未执行
- 盒子：192.168.3.125:5555（RK3566）

## 用例

### TC-010-01 热重启自恢复（AC-2，脚本辅助）
步骤：
1. `adb connect 192.168.3.125:5555` 后 `adb -s 192.168.3.125:5555 shell reboot`；
2. 等待盒子开机完成；
3. 探测 TCP 8899 可连（`PING` 返回 `PONG`），监听 UDP 8898 广播。
预期：2 分钟内 TCP 恢复、UDP 广播恢复。
实际：reboot 后 32 秒 adb 在线，再等 20 秒（总约 52 秒 < 2 分钟）：TCP 8899 可连成功；
UDP 8898 收到广播 `TVREMOTE|8899|H96_Max_V56` 来自 192.168.3.125。　结果：P

### TC-010-02 服务强杀自拉起（AC-3，脚本辅助）
步骤：
1. `adb shell am force-stop com.koco.tvremoteserver`（或 kill 服务进程）；
2. 数秒后探测 TCP 8899。
预期：START_STICKY 自动重建服务，8899 恢复可连。
实际：`su root sh -c 'kill 24816'` 强杀服务进程，8 秒后 `pidof` 返回新 pid 8355，
START_STICKY 自动拉起。注：本机 `am kill` 传参打印 help 未生效，改用 root kill。　结果：P

### TC-010-03 断电冷启动自恢复（AC-1，真机手工）
步骤：
1. 盒子断电；
2. 重新上电，等待开机完成（电视出画面）；
3. 2 分钟内用手机直连模式直接按 OK 键。
预期：无需打开盒子端 App、无需重新配对，按键立即生效；UDP 发现与 TCP 均正常。
实际：（待填）　结果：N（无法远程物理断电，留用户执行）

### TC-010-04 免重新配对（FR-10-4，脚本辅助）
步骤：reboot 后读取盒子端 `shared_prefs/srv.xml` 中 token，PC 用该 token 发 `KEY` 命令。
预期：token 与重启前一致（sp 持久化），KEY 响应 OK。
实际：reboot 后 token=ca22a5376438a432fcadefe739a36ec6，`KEY <token> 25` 响应 OK，
免重新配对验证通过。　结果：P

### TC-010-05 协议回归（AC-4）
步骤：`powershell -File tests\protocol\run.ps1`。
预期：43 用例 0 失败。
实际：43/43 全部通过（本会话 SPEC-009 验收时执行）。　结果：P

## 失败排查指引

若 TC-010-01/03 失败：
1. `adb shell dumpsys activity services ServerService` 看服务是否被拉起；
2. 查系统设置是否有「自启动管理」并允许本应用；
3. 确认测试前 App 未被 force-stop（force-stop 后 BOOT_COMPLETED 不投递，平台行为）；
4. logcat 过滤 `TvRemoteServer` 与 `BOOT_COMPLETED`。
