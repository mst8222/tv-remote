# SPEC-026 手工验收清单：直连方向键焦点边界翻页

环境：H96 Max V56（Android 12，当贝桌面 4.1.7），手机端直连该盒子。

前置：`adb -s 192.168.3.125:5555 shell settings put secure enabled_accessibility_services com.koco.tvremoteserver/com.koco.tvremoteserver.TvRemoteAccessibilityService`

| 编号 | 步骤 | 预期 | 结果 |
|---|---|---|---|
| AC-1 | 盒子回到当贝首页，手机遥控按“下”方向键 | 切换到应用列表页 | 未通过（2026-10-22 H96 实测：不翻页。该机不执行无障碍合成手势，点按与滑动均无输入） |
| AC-2 | 在当贝应用列表按“上”方向键 | 返回当贝首页 | 未通过（同上，需在能执行合成手势的机型复测） |
| AC-3 | 在当贝首页直接按“上”方向键 | 页面不动，顶部快捷栏不展开 | 待执行 |
| AC-4 | 打开 Kodi（或任一播放器），方向键上下左右移动焦点，并在列表首/尾继续按方向键 | 焦点移动正常，到边界无异常动作（不滑动翻页） | 待执行 |
| AC-5 | 在项目根目录执行 `powershell -File tests\protocol\run.ps1` | 全部用例通过 | 通过（196/196） |

## 实测记录（2026-10-22）

- 页面判定可用：首页 dump 无 `layout_second_screen_recycler_view`，应用列表页有此 id；
  两页都在 `activity_main_view_pager` 内。
- 真实触摸可翻页：`adb shell input swipe 1024 1000 1024 200`（首页→列表）、
  `input swipe 1024 691 1024 1306`（列表→首页），2000ms 慢速滑动同样有效。
- 合成手势无效：`dispatchGesture` 返回 true，但
  1) 光标移动到应用列表首项后 MCLICK 点击不打开应用；
  2) 光标移动到首页托盘图标后 MCLICK 不打开应用；
  3) 65%→13%（300ms / 2000ms）竖直滑动不翻页。
  说明该固件既不投递合成点按也不投递合成滑动，不是滑动参数问题。
- 键盘对照：`input keyevent 20` 在首页连按可进应用列表，`input keyevent 19` 在列表连按可回首页；
  次数取决于焦点所在行，桌面的翻页由按键逻辑驱动。
- 列表页按 BACK 不回首页（桌面 BACK 只处理第二屏/日历），不能用 BACK 替代翻页。
