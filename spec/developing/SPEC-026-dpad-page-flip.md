# SPEC-026 直连方向键焦点边界翻页

## 元信息
- 状态：developing
- 创建：2026-10-22
- 更新：2026-10-22
- 优先级：medium

## 变更记录
- 2026-10-22 创建：当贝桌面首页与应用列表之间方向键翻页在直连模式失效。
- 2026-10-22 转入 developing：按“合成竖直滑动”方案实现，按桌面页面判定是否翻页。
- 2026-10-22 实测修正：焦点遍历拿不到桌面的真实选中项，改为按当前挂载的页面直接判定；
  增加 `flagReportViewIds` 才能读到 `activity_main_view_pager` 等资源 id。
- 2026-10-22 实测结论：H96 Max V56 定制固件不执行无障碍合成手势（`dispatchGesture`
  返回 true 但不产生任何输入，点按托盘图标、点击列表项、竖直滑动均无效果），
  该机型直连无法翻页；达到 AC-1/AC-2 需要在能正常执行合成手势的机型上验收。

## 背景与目标

当贝桌面首页（第一屏）与应用列表位于同一个 ViewPager（`activity_main_view_pager`）
的两页中。物理遥控在焦点到达边界时由桌面的按键逻辑驱动翻页；直连模式只能通过无障碍
注入，而：

- 无障碍无法生成 KeyEvent，`performGlobalAction(GLOBAL_ACTION_DPAD_*)` 在 H96 上返回 false；
- 桌面的焦点顺序与 `focusSearch` 的空间顺序不一致，向下会先跳进隐藏的顶部快捷栏；
- ViewPager 未实现 `ACTION_SCROLL_FORWARD/BACKWARD`（action 被接受但不换页）。

目标：在不 root、不修改桌面 APK 的前提下，用合成触摸滑动补齐“焦点边界翻页”。

## 功能需求（FR）

- FR-26-1 当贝首页按“下”：合成竖直滑动（手指上滑）翻到应用列表页。
- FR-26-2 当贝应用列表页按“上”：合成竖直滑动（手指下滑）翻回首页。
- FR-26-3 页面判定以当前挂载的节点为准：应用列表页存在
  `:id/layout_second_screen_recycler_view`；不存在即视为首页。
  首页按“上”、列表页按“下”、左右方向键均不翻页，保持原有焦点遍历行为。
- FR-26-4 滑动参数：横向取根节点中心；下方向键纵向 65%→13%，上方向键纵向 45%→85%；
  时长 300ms。起点避开首页底部应用托盘（托盘在 73%~89% 高度，
  从托盘图标起滑会被桌面当成点击而打开应用）。

## 非功能需求（NFR）

- NFR-26-1 不新增权限，不依赖 root，不修改桌面 APK。
- NFR-26-2 手势复用现有 `dispatchGesture` 通道发送，不阻塞协议线程。
- NFR-26-3 非当贝桌面、普通应用（无 `activity_main_view_pager`）方向键导航行为不变。

## 验收标准

- [ ] AC-1 当贝首页按“下”，进入应用列表。（H96 不可验收：该机不执行合成手势）
- [ ] AC-2 当贝应用列表首行按“上”，返回首页。（H96 不可验收：同上）
- [ ] AC-3 当贝首页按“上”不翻页、不展开顶部快捷栏。
- [ ] AC-4 应用内（Kodi 或播放器）方向键移动焦点、到边界行为与改动前一致。
- [x] AC-5 `powershell -File tests\protocol\run.ps1` 回归全绿（196/196）。

## 边界与已知限制

- H96 Max V56（Android 12 定制固件）不执行无障碍合成手势：`dispatchGesture` 返回 true，
  但点按、滑动都不产生输入。该机型直连无法翻页，也无法用点按/长按替代，
  只能走蓝牙 HID（真实按键由桌面自己处理）。
- 同一限制使 SPEC-021 飞鼠、SPEC-023 触控板的手势回退在 H96 上无效；
  这两处在 H96 只能依赖节点动作（`ACTION_CLICK` / `ACTION_LONG_CLICK` / `ACTION_SCROLL`）。
- 翻页是“一按即翻”，不会像物理遥控那样先在页内移动焦点到边界，再翻页。
- 只识别当贝的资源 id；其他桌面不启用本逻辑。
- 蓝牙 HID 模式发送真实按键，由桌面自身翻页，不使用本逻辑。

## 实现映射

- `server/src/com/koco/tvremoteserver/TvRemoteAccessibilityService.java`
  - `moveFocus`：按 `activity_main_view_pager` + `layout_second_screen_recycler_view` 判定页面与翻页方向。
  - `swipePage`：合成竖直滑动。
  - `findByIdSuffix`：深度优先按资源 id 后缀查找，深度上限 60（当贝图标卡片层级深）。
- `server/res/xml/accessibility_service.xml`：`accessibilityFlags` 增加 `flagReportViewIds`，
  否则 `getViewIdResourceName()` 恒为 null，页面判定不成立。

## 测试映射

- 手工：`tests/manual/SPEC-026-dpad-page-flip.md`
- 自动：`tests/protocol/run.ps1`（协议回归，确认无回归）
