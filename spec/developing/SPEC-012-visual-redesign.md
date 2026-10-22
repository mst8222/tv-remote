# SPEC-012 界面视觉重设计与应用动态化

## 元信息
- 状态：developing
- 创建：2026-10-21
- 更新：2026-10-21
- 优先级：high

## 变更记录
- 2026-10-21 创建（用户反馈 SPEC-008 界面离现代美观差得远；应用按钮为硬编码不隐藏）
- 2026-10-21 应用按钮动态化需协议扩展，随本 spec 一并实现
- 2026-10-21 视觉规范 v2：用户给参考图（大圆盘十字遥控 / 圆形图标键 / 音量胶囊 / 青环 OK）
  要求综合重设计，主卡改为「遥控器」单卡（大圆盘 + 圆形图标键 + 音量胶囊），
  原「方向导航/系统按键」两卡合并；首版实现真机确认电源 U+23FB 缺字形，回退文字
- 2026-10-21 双端实现完成并真机验证：手机端大圆盘/光环 OK/圆形图标键/音量胶囊/
  应用卡动态化（ADB 与直连双通道）全部成型；盒子端卡片化。期间定位并修复
  AdbClient 隐藏缺陷（见实现映射与项目记忆）：adbd 旧流残留消息导致 exec 输出错位
  一条，pm list 一直返回空——按流 id 过滤后 5 条命令实测全部正确。
  run.ps1 44/44 全绿（首跑 discover_no_broadcast_null 撞真实盒子广播为已知环境
  flaky，重跑通过）。剩余：AC-4 语言切换后标题、AC-5 平板档留用户手工；
  视觉审美最终确认留用户。
- 2026-10-21 用户反馈：扫描状态和结果应放在一个扫描弹窗里，而不是跟着切换的
  组件（状态行）。增补 FR-12-6：点扫描立即弹窗，弹窗内实时显示状态与结果，
  关闭即停发现；重写 startScan（showScanResult 删除）。
- 2026-10-21 用户反馈：弹窗仍是原生样式（白底灰框，与深色界面割裂）。增补
  FR-12-7：新增自绘 Sheet 弹窗容器（深色卡片/圆角/渐变主按钮/危险红字），
  替换全部 7 处 AlertDialog。真机验证设备选择/扫描/手动添加三处通过；
  run.ps1 44/44 全绿。
- 2026-10-21 真机验证同步发现并修复：应用快捷卡的名称被裁掉（权重参数 w()
  高度写死为 keyH），新增高度自适应 wc() 后正常显示。
- 2026-10-21 用户按截图提出 4 处布局修正（FR-12-8）：顶栏固定；十字键在圆盘上
  严格对称（根因：盘容器为 MATCH_PARENT 宽于盘面，左右键落在盘外）；系统键行
  与音量行改为四列等宽网格逐列对齐（顺带修复圆键被 48dp 行高裁切）；应用单元
  改为图标 + 完整名称。Note20 真机实测：四向键中心距盘心上下 242/243px、
  左右 242/243px，两行四列中心偏差 ≤1px。
- 2026-10-21 设备身份纠正：控制器手机为三星 Note20（SM-N9810 / RFCN803AF2L），
  三星 Fold3（SM-F926U1 / 192.168.3.38，横屏 1920x1080）为被控盒子。
- 2026-10-21 增补 FR-12-9 / AC-8：切换盒子时探测「直连/ADB/蓝牙」三段能力，
  明确不支持（端口拒绝/无蓝牙特性/手机 HID 不可用）的段默认隐藏，连接错误不隐藏。
- 2026-10-21 模拟器（AVD Medium_Phone_API_36.1）验证并修复：平板档内容短于视口时
  ScrollView fillViewport 会把最后一张卡（应用卡）拉伸到视口底部（uiautomator 实测
  应用卡高 977px 而内容仅 180px）。去掉 fillViewport 后恢复，卡片按内容高度收尾。
  同时用 wm size/density 覆盖验证紧凑档（sw320dp）与平板档（sw800dp）布局不破。
- 2026-10-21 用户反馈音量胶囊左侧顶到卡片边缘、与返回圆键不齐：胶囊左端按
  四列网格内缩 (列宽-圆键直径)/2，左边缘与返回圆键左边缘重合；右端仍在第三列右界，
  静音圆键保持第四列。Note20 像素实测：圆键左 120px = 胶囊左 120px，电源/静音
  右边缘同为 957px；紧凑档 70/70、平板档 375/375 与 1224/1224 同样对齐。
  手机桌面名称同步改为中文「TV遥控器」/英文「TV Remote」。
- 2026-10-21 SPEC-013 移除 ADB 控制：模式栏取消三段与能力探测，FR-12-9/AC-8
  作废（用户明确要求按钮常显）；应用卡改只走直连 LIST 单通道；ADB 相关文案删除。

## 背景与目标

SPEC-008 重设计后真机效果仍显呆板：全部为等宽深灰圆角块、无分组层级、
无图标语言、按钮间距松散。另外应用快捷按钮硬编码 6 个包名（v1.0 遗留），
盒子未安装也不隐藏，点击报错。

目标：
1. 视觉全面升级为现代深色设计（分组卡片、图标化按键、渐变强调、紧凑十字键）。
2. 应用按钮只显示盒子已安装的（协议新增 LIST 命令）。

## 视觉规范（实现依据，纯代码绘制，无 XML/图标库）

### 配色（复用现有常量名，值可调）
- BG `#0A0E14`（页面底，比原 #0B0F1A 更纯净）
- SURFACE `#141A24`（分组卡片）
- SURFACE2 `#1C2430`（卡片内按键）
- ACCENT `#4F7CFF` → OK 键与选中态用 `GradientDrawable` 竖直渐变
  `#5B8CFF → #3E6AF0`，圆角 999
- TXT `#EAEFF7` / SUB `#8A94A6`（次级）/ DIM `#232C3A`（描边/填充）
- 电源键文字 `#FF6B6B`；状态成功点 `#3DDC84` / 失败点 `#FF5A5A`

### 结构（自上而下，v2 —— 综合用户参考图：大圆盘十字遥控 / 圆形图标键 / 音量胶囊）
1. 头部：设备名 17sp medium + ▾；右侧「中/EN」胶囊（DIM 底 999 圆角）。
   状态行改为胶囊徽标：8dp 圆点（成功/失败色）+ 12sp 文字，无边框。
2. 模式分段控件：整体一张 DIM 底 999 圆角胶囊，内含 直连/ADB/蓝牙 三段，
   选中段 ACCENT 渐变底白字，未选中透明底 SUB 字；扫描/添加为右侧 SUB 色文字按钮。
   1、2 合为固定顶栏，不随下方卡片区滚动。
3. 分组卡片：SURFACE 底、20dp 圆角、内边距 14dp，卡片间距 10dp，页面左右边距 14dp；
   卡片左上角 11sp SUB 分组标题（遥控器 / 文字输入 / 应用快捷；
   英文 Remote / Input / Apps）。
4. 遥控器主卡（v2 核心，参考图综合）：
   - 大圆盘：直径 ~240dp（compact 200 / tablet 280，取内容宽与上限较小值）的整圆
     （DISC `#1B2432` 底，1dp `#2A3547` 描边），居中，是整个界面的视觉主角；
     容器与盘面严格同尺寸（d×d），四向键才能相对盘面精确定位；
   - 四向键：40dp 小圆键（SURFACE2 底，方向箭头 ▲◀▶▼ 16sp）嵌在大圆盘边缘
     内侧 14dp 处，四条边内边距相同 → 上下左右严格对称（曾因容器宽于盘面导致
     左右键落在盘外）；
   - 圆形图标键行与音量行共用四列等宽网格：←/−、⌂/音量、≡/+、电源/静音
     逐列上下对齐；行内均分，圆键行高度自适应避免圆键被压扁；
   - 中心 OK：84dp 圆形（compact 72 / tablet 96），深底 `#101720` +
     3dp ACCENT 光环描边（参考图青环 OK 的深色版），OK 白字加粗 17sp，
     按压 ripple；
   - 圆形图标键行：返回(←) 主页(⌂) 菜单(≡) 电源 四个 52dp 圆键
     （SURFACE2 底），等距横排，符号 18sp；电源真机缺 U+23FB 字形，
     用「电源」红字 11sp 回退（首版真机已确认）；
   - 音量行：[− 音量 +] 一条横向胶囊（SURFACE2 底，减/加 18sp 可点，
     中间「音量」12sp SUB 不可点）+ 右侧「静音」52dp 圆键。
5. 文字输入卡：输入框（SURFACE2 底 12dp 圆角，SUB 提示字）+ 右侧发送胶囊
   （ACCENT 渐变 999 圆角）同排。
6. 应用快捷卡：手机桌面式排列（每行 3 个），单元 = 56dp 圆形图标位（SURFACE2 底，
   应用名首字占位）+ 下方完整名称（12sp，最多两行，超出省略号）；无已装应用时整卡隐藏。
7. 多尺寸：沿用 initSizes() 三档（<360 紧凑 / 360-599 标准 / >=600 平板 520dp 居中），
   参数随新结构重算。

### 图标符号（Unicode，避免引入字体/图标库）
返回 ←、主页 ⌂、菜单 ≡、方向 ▲◀▶▼（真机已确认可渲染）。
电源 U+23FB 真机缺字形 → 回退文字「电源」（红色）。
若其他符号真机缺字形同样回退文字。

## 功能需求（FR）

- FR-12-1 按上述视觉规范重写两端界面（手机端 MainActivity、盒子端 MainActivity），
  盒子端同步升级为同语言卡片式视觉（配对码大字卡保留）。
- FR-12-2 应用快捷动态化：协议新增 `LIST <token>` 命令，盒子端返回
  `OK <pkg1>,<pkg2>,...`（`pm list packages -3` 第三方包名列表，逗号分隔）；
  手机端与内置候选表（设置/OK影视/飞牛TV/当贝桌面/当贝市场/Kodi）求交集后
  只渲染已装项；列表为空时隐藏应用卡。
- FR-12-3 LIST 结果按设备缓存（sp key `apps_<host>`），拉取失败用缓存，无缓存则
  隐藏应用卡并重试下次。
- FR-12-4 全部新增文案进 strings.xml 双语（分组标题等）。
- FR-12-5 语言切换、模式切换、多尺寸、ripple 触摸反馈等既有行为不回归。
- FR-12-6 扫描交互改为单弹窗：点「扫描」立即弹出扫描弹窗，弹窗内实时显示
  扫描状态（正在扫描… → 发现 N 台 / 未发现设备）与结果列表（发现一台加一行，
  点击行直接添加并连接）；关闭/取消弹窗即停止发现；状态行不再显示扫描过程，
  结果不再二次弹窗。
- FR-12-7 全部弹窗改用自绘样式（不使用原生 AlertDialog 外观）：深色 SURFACE
  卡片、22dp 圆角、60% 遮罩；标题 18sp 粗体，可选副标题 13sp SUB；
  列表项为圆角行（选中行 12% ACCENT 底 + 右侧 ACCENT 圆点）；输入框 SURFACE2
  12dp 圆角 48dp 高；底部动作行右对齐——主操作为 ACCENT 渐变胶囊白字 40dp 高，
  次操作为 SUB 文字按钮，危险操作为红字（删除当前）。覆盖设备选择、语言选择、
  手动添加、直连配对、蓝牙选择、蓝牙不可用提示、扫描共 7 处弹窗。
- FR-12-8 布局对齐：设备信息与模式栏为固定顶栏（不随卡片区滚动）；大圆盘容器与
  盘面同尺寸，四向键上下左右严格对称；系统键行与音量行共用四列等宽网格逐列对齐，
  音量胶囊左端内缩到返回圆键左边缘（不再顶到卡片内边距），右端仍止于第三列右界，
  静音圆键与电源键同列；应用快捷单元为图标 + 完整名称（最多两行）。
- FR-12-9 能力按钮按盒子能力隐藏：~~切换设备、新建连接、连接成功、进入后台再回到
  前台时，在独立探测线程（不与 ADB 握手串行）判定当前盒子是否「明确不支持」某段~~
  **（已取消，见变更记录；按 SPEC-013 与用户要求，模式按钮常显，不再探测隐藏）**
  - 直连：TCP 连 8899 被拒绝（ConnectException，未装或未启动盒子端服务）→ 隐藏
    「直连」段；连接成功 → 显示；超时/网络不可达 → 视为连接错误，保持显示。
  - 蓝牙：手机端 HID 注册失败（BtHid onSupport/onRegistered 回调失败）→ 隐藏
    「蓝牙」段；ADB 已连接时读取盒子 `pm list features`，无 `android.hardware.bluetooth`
    → 隐藏；读取失败或未连接 → 保持显示。
  - ADB：不同于直连，ADB 端口关闭/握手失败只属连接错误，段保持显示（由状态行报错）。
  隐藏后模式胶囊由剩余段均分；若当前模式恰被隐藏，自动回退到 ADB 模式。

## 非功能需求（NFR）

- NFR-12-1 不引入第三方库/XML 布局；APK 增量后仍 <200KB。
- NFR-12-2 协议扩展同步 MockBoxServer（copy 再改写）+ 协议测试用例（LIST 正常/
  错 token/空列表）。

## 验收标准

- AC-1 双端构建通过，run.ps1 全绿（新增 LIST 用例后 44 用例 0 失败）。
- AC-2 真机截图：分组卡片、渐变 OK 键、图标系统键、紧凑十字键呈现规范效果，
  无字形缺失方块。
- AC-3 手机连接盒子后应用卡只显示盒子已装应用（对照 `pm list packages -3` 实际输出）。
- AC-4 语言切换后新分组标题双语正确。
- AC-5 平板/紧凑档布局不破（沿用三档规则）。
- AC-6 全部弹窗为自绘深色卡片样式（无原生白底灰框）：真机打开设备选择、扫描、
  手动添加三处，均呈现 22dp 圆角 SURFACE 卡片 + 60% 遮罩、选中行淡蓝底 + 右侧
  蓝点、深色圆角输入框、右对齐动作行（主操作渐变胶囊白字 / 次操作 SUB / 删除红字）；
  语言选择、直连配对、蓝牙两处沿用同一 Sheet 容器。
- AC-7 布局对齐（FR-12-8）：滚动时顶栏不动；大圆盘四向键相对盘心上下左右等距；
  系统键四列与音量行四列（−/音量/+/静音）逐列中心对齐；应用单元显示图标 +
  完整名称（两行内）。
- AC-8 能力隐藏（FR-12-9）：**已取消**。按用户要求，模式按钮常显，改为
  AC-8'：冷启动与切换设备后「直连」「蓝牙」两段始终可见可点，连接失败仅提示原因
  （详见 SPEC-013 手工清单 TC-013-02）。

## 边界与已知限制

- 应用图标不做真实图标获取（需 PM 图标协议，后续扩展），用首字/符号占位。
- LIST 返回全部第三方包，手机端只渲染内置候选交集；候选外应用暂不支持自定义添加
  （后续 spec）。
- 盒子端视觉随本 spec 升级，但盒子 TV 端交互不变（仅展示）。
- 蓝牙隐藏的两条路径（手机无 HID 能力 / 盒子无蓝牙特性）当前环境无法构造：
  （能力隐藏机制已随 SPEC-013 取消，此段仅保留历史结论，不再验收）

## 实现映射（implemented 后填写）

（以下为当前实现落点，转 implemented 时确认无漂移）
- 协议 LIST：server/src/.../ServerService.java（case "LIST" + listPackages()，
  su root sh -c "pm list packages -3"）；app/src/.../LanClient.java list()；
  tests/protocol/MockBoxServer.java（packages 字段）+ ProtocolTest.java
  tc_list_ok/tc_list_wrong_token/tc_list_empty。
- 手机端视觉：app/src/.../MainActivity.java —— 常量（BG/SURFACE/SURFACE2/ACCENT/
  TXT/SUB/DIM/GREEN/RED/POWER）、initSizes() v2 三档、card() 分组卡片、
  buildRemote()/buildDpadDisc()/padKey()/okRing()（大圆盘 + 光环 OK）、
  buildSysRow()/circleKey()（圆形图标键，电源文字回退）、buildVolRow()/pillEnd()
  （音量胶囊 + 静音圆键）、rippleGrad()/accentGrad()（渐变胶囊）、
  APP_CANDIDATES/candidateLabel/parsePkgs/rebuildApps/appCell/ensureApps()
  （应用动态化 + apps_<host> 缓存，直连 LIST / ADB pm list 双通道）。
- 盒子端视觉：server/src/.../MainActivity.java —— 同套配色、card()/roundSurface()/
  buildStatusRow() 三卡（服务状态/配对码/连接地址），tick/checkRoot 逻辑未动。
- ADB 传输修复：app/src/.../AdbClient.java exec() 按流 id（a1==lid）过滤本流
  数据/关闭，别流残留消息回 OKAY 后丢弃；探针实测（echo/pm3 交错）修复前后
  输出错位 → 全部正确。
- 双语：app res values(+en) grp_remote/grp_input/grp_apps/volume；
  server res values(+en) ui_grp_status/ui_grp_pair/ui_grp_addr（删 ui_pair_label）。
- 自绘弹窗：app/src/.../MainActivity.java 内部类 Sheet（Dialog + 透明 Window
  + 60% 遮罩 + 22dp 圆角卡片；row()/field()/text()/action()/onDismiss()），
  覆盖 showDevicePicker/showLangDialog/showAddDialog/showLanDialog/蓝牙不可用/
  showBtDevicePicker/startScan；删除 AlertDialog 依赖。
- 应用卡名称修复：wc() 高度自适应权重参数（w() 固定 keyH 会裁掉名称）。
- 布局对齐（FR-12-8）：MainActivity.java —— buildUi() 改为 root 竖排
  （顶栏两行 + ScrollView 权重 1）；buildDpadDisc() 容器 new LinearLayout.LayoutParams(d, d)
  + plate MATCH_PARENT；buildSysRow()/buildVolRow() 共用四列等宽
  + pillCell() 胶囊三等分；音量胶囊左端按 contentWidth()/4 列宽减圆键直径取内缩量，
  以 leftMargin 内缩（右端固定 3 列宽 - 内缩，静音仍在第四列）；appCell() 名称
  setMaxLines(2) + ellipsize(END)。
- 能力隐藏（FR-12-9）：MainActivity.java —— probeCaps()/probeTcp()/applyCaps() +
  独立 probeWorker（避免排在 ADB 握手超时后）；capLan/capBt 三态（null 未知/
  FALSE 不支持）；btUnsupported 与 btFeatChecked 记录 HID 失败与已查特性的设备；
  调用点 onCreate / onResume / switchTo / addAndConnect / connectAsync 成功后。
- 平板档卡片拉伸修复：MainActivity.java buildUi() 去掉 ScrollView fillViewport
  （内容短于视口时最后一张卡被拉到视口底部，见变更记录实证）。

## 测试映射

- 手工：tests/manual/SPEC-012-visual-redesign.md
- 自动：powershell -File tests\protocol\run.ps1（含 LIST 新用例）
