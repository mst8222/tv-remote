package com.koco.tvremote;

import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 手机端主界面（纯 Java 构建，无 XML layout，SPEC-008 重新设计）。
 * 布局自上而下：头部（设备名+状态徽标）→ 模式选择器（直连/蓝牙）→
 * 十字导航 → 系统键两行 → 文字输入 → 应用快捷。
 * SPEC-013：ADB 控制方案已移除，按键只走直连或蓝牙 HID。
 */
public class MainActivity extends Activity {

    // SPEC-012 配色：纯净深底 + 分组卡片 + 单一蓝色强调
    static final int BG = 0xFF0A0E14, SURFACE = 0xFF141A24, SURFACE2 = 0xFF1C2430,
            ACCENT = 0xFF4F7CFF, TXT = 0xFFEAEFF7, SUB = 0xFF8A94A6, DIM = 0xFF232C3A,
            GREEN = 0xFF3DDC84, RED = 0xFFFF5A5A, POWER = 0xFFFF6B6B;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** SPEC-019：应用图标内存缓存，键为「设备 IP|包名」，避免切设备串图 */
    private final Map<String, Bitmap> iconCache = new HashMap<>();
    private TextView status;
    private TextView devName;
    /** SPEC-027：启动探测到新版本时顶栏的「有更新」标签 */
    private TextView updateBadge;
    /** SPEC-027：启动探测缓存的 Release，点标签时直接复用，避免二次请求 */
    private Updater.Release pendingUpdate;
    private SharedPreferences sp;
    private BtHid bt;
    private BtHid.StateListener btListener;
    private boolean btMode = false;
    private TextView modeLan, modeBt;
    private GradientDrawable dotBg;
    private LanClient lan;
    private boolean lanMode = false;
    // SPEC-018：飞鼠（仅蓝牙 HID），陀螺仪角速度换算为鼠标相对位移
    private boolean airMouse = false;
    private SensorManager sensorManager;
    private Sensor gyro;
    private TextView mouseBtn;
    /** 飞鼠圆键直径（标题行右侧，SPEC-018） */
    private int mouseD;
    /** 飞鼠键右缩进：使右边缘与系统键行第 4 列（电源键）右边缘对齐 */
    private int mouseInset;
    private long okDownAt = 0;
    /** 直连长按是否已在按住期间发出，避免抬手再发短按 */
    private boolean okLongFired = false;
    /** 按下达到长按阈值立即发 KEYLONG（真机手指轻微移动时父容器会拦截，不能只等 ACTION_UP） */
    private final Runnable okLongTask = () -> {
        if (!okLongFired) {
            okLongFired = true;
            lanPressLong(23);
        }
    };
    /** 与 Android 系统长按阈值一致 */
    private static final long LONG_PRESS_MS = 500;
    /** 陀螺仪死区（rad/s），抑制手抖 */
    private static final float GYRO_DEAD_ZONE = 0.05f;
    /** 每 20ms 角速度 -> 位移像素的换算系数（1 rad/s 约 600 px/s） */
    private static final float GYRO_SCALE = 12f;
    /** 飞鼠调试日志节流时间 */
    private long lastGyroLog = 0;
    // SPEC-012：应用卡动态挂载/重建用
    private LinearLayout column;
    private View appsCard;

    // SPEC-023：输入方式（方向盘 / 触控板）
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean inputTouchpad = false;
    /** 方向盘与触控板共用的正方形边长（像素） */
    private int inputD;
    private View dpadDisc;
    private TouchPadView touchpadView;
    private TextView inputDpad, inputTouchpadBtn;
    /** 触控板专用直连长连接；蓝牙模式为 null */
    private TouchSession touchSession;

    /** SPEC-011：直连配对按设备 IP 持久化，控制目标恒为当前设备 */
    private String tokenOf(String host) { return sp.getString("lan_" + host, ""); }

    private void saveToken(String host, String tok) {
        sp.edit().putString("lan_" + host, tok).apply();
    }

    /** SPEC-015：按设备记忆最后成功使用的连接方式与蓝牙目标 */
    private String modeOf(String host) { return sp.getString("mode_" + host, "lan"); }

    private void saveMode(String host, String mode) {
        sp.edit().putString("mode_" + host, mode).apply();
    }

    private String btAddrOf(String host) { return sp.getString("bt_" + host, ""); }

    private void saveBtAddr(String host, String addr) {
        sp.edit().putString("bt_" + host, addr).apply();
    }

    private void clearDevicePrefs(String host) {
        sp.edit().remove("mode_" + host).remove("bt_" + host).apply();
    }

    /** SPEC-009：解析生效语言（system/zh/en）。
     *  system 取系统主语言 LocaleList.getDefault()：该机应用资源里的 localeList
     *  顺序与系统不一致（应用内为 [en_US, zh_CN-Hans, de_BE]，系统为
     *  [zh_CN-Hans, de_BE, en_US]），不能用应用 Configuration 判断系统语言。 */
    @Override
    protected void attachBaseContext(Context base) {
        String lang = base.getSharedPreferences("tvremote", MODE_PRIVATE)
                .getString("lang", "system");
        Locale loc;
        if ("zh".equals(lang)) {
            loc = Locale.SIMPLIFIED_CHINESE;
        } else if ("en".equals(lang)) {
            loc = Locale.ENGLISH;
        } else {
            Locale sys = LocaleList.getDefault().get(0);
            if ("zh".equals(sys.getLanguage())) loc = sys;
            else if ("en".equals(sys.getLanguage())) loc = Locale.ENGLISH;
            else loc = Locale.SIMPLIFIED_CHINESE;  // 其他系统语言回落中文默认资源
        }
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(loc);
        super.attachBaseContext(base.createConfigurationContext(cfg));
    }

    /** 设备表，每项 {名称, IP, 端口} —— 可扫描添加，持久化保存 */
    private final List<String[]> devices = new ArrayList<>();
    private int cur = 0;

    // ---------------- 多尺寸档位（SPEC-008 FR-8-13） ----------------

    private boolean compact, tablet;
    private int outer, padD, arrowLen, arrowText, okD, okText, sysLen, sysIcon, keyH, appIcon, appText;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("tvremote", MODE_PRIVATE);
        loadDevices();
        setContentView(buildUi());
        updateModeUi();
        // SPEC-015：恢复当前设备上次成功使用的连接方式（新设备默认直连）
        applyDeviceMode();
        // SPEC-023：恢复输入方式（方向盘 / 触控板）
        setInputMode("touchpad".equals(sp.getString("input", "dpad")));
        // SPEC-027：每次启动后台静默探测更新，有新版本时点亮顶栏标签
        autoCheckUpdate();
    }

    /** 回到前台时恢复被系统注销的蓝牙 HID 注册（SPEC-005 FR-5-7） */
    @Override
    protected void onResume() {
        super.onResume();
        if (btMode && bt != null && btListener != null && !bt.isReady()) {
            bt.start(btListener);
        }
        // 直连会话在 onPause 已关闭，回前台重新建立
        if (inputTouchpad && lanMode && touchSession == null) startTouchpad();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // SPEC-023：离开界面立即释放按压并关闭直连会话，避免光标/触摸卡住
        stopTouchpad();
    }

    private String curHost() { return devices.get(cur)[1]; }

    // ---------------- 设备持久化 ----------------

    private void loadDevices() {
        devices.clear();
        // SPEC-011 一次性迁移：旧版全局配对键转存为按 IP 的键后删除
        String oldHost = sp.getString("lanHost", "");
        String oldTok = sp.getString("lanToken", "");
        if (!oldHost.isEmpty() && !oldTok.isEmpty()) {
            sp.edit().putString("lan_" + oldHost, oldTok)
                    .remove("lanHost").remove("lanToken").apply();
        }
        String raw = sp.getString("devs", "");
        if (!raw.isEmpty()) {
            // SPEC-013：设备表只保留 名称|IP；旧版三段（名称|IP|端口）忽略端口
            for (String line : raw.split("\n")) {
                String[] p = line.split("\\|");
                if (p.length >= 2 && !p[0].isEmpty() && !p[1].isEmpty()) {
                    devices.add(new String[]{p[0], p[1]});
                }
            }
        }
        if (devices.isEmpty()) {
            devices.add(new String[]{"荐片TV · RK3566", "192.168.3.125"});
            devices.add(new String[]{"三星 Fold3 · 安卓13", "192.168.3.38"});
            saveDevices();
        }
        String saved = sp.getString("cur", "");
        if (!saved.isEmpty()) {
            for (int i = 0; i < devices.size(); i++) {
                if (devices.get(i)[0].equals(saved)) { cur = i; break; }
            }
        }
        if (cur >= devices.size()) cur = 0;
    }

    private void saveDevices() {
        StringBuilder sb = new StringBuilder();
        for (String[] d : devices) {
            sb.append(d[0]).append('|').append(d[1]).append('\n');
        }
        sp.edit().putString("devs", sb.toString().trim())
                .putString("cur", devices.isEmpty() ? "" : devices.get(cur)[0]).apply();
    }

    // ---------------- UI 构建 ----------------

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** 按屏幕宽度取三档尺寸参数：小屏 <360 / 标准 / 平板 >=600（SPEC-012 v2 结构） */
    private void initSizes() {
        int sw = getResources().getConfiguration().screenWidthDp;
        compact = sw < 360;
        tablet = sw >= 600;
        outer = compact ? 10 : tablet ? 24 : 14;        // 页面与卡片外边距
        padD = compact ? 200 : tablet ? 280 : 240;      // 遥控大圆盘直径
        arrowLen = compact ? 36 : tablet ? 46 : 40;     // 四向小圆键直径
        arrowText = compact ? 14 : tablet ? 18 : 16;    // 方向箭头字号
        okD = compact ? 72 : tablet ? 96 : 84;          // 中心 OK 直径
        okText = compact ? 15 : tablet ? 19 : 17;       // OK 字号
        sysLen = compact ? 46 : tablet ? 56 : 52;       // 圆形图标键/静音直径
        sysIcon = compact ? 16 : tablet ? 20 : 18;      // 图标键符号字号
        keyH = compact ? 44 : tablet ? 52 : 48;         // 输入行/音量胶囊高
        appIcon = compact ? 48 : tablet ? 64 : 56;      // 应用圆形图标位直径
        appText = compact ? 11 : tablet ? 13 : 12;      // 应用名字号
        // SPEC-023：方向盘与触控板共用同一边长，取可用宽度与设计值中的较小者
        int avail = getResources().getDisplayMetrics().widthPixels - dp(outer * 2) - dp(28);
        if (tablet) avail = Math.min(avail, dp(520) - dp(28));
        inputD = Math.min(dp(padD), avail);
    }

    private View buildUi() {
        initSizes();
        // 顶栏（设备信息 + 模式栏）固定在顶部，不随卡片区滚动
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(outer), dp(outer), dp(outer), 0);
        root.addView(buildHeader(), lp(-1, -2, 0, 0, 0, dp(10)));
        root.addView(buildModeBar(), lp(-1, -2, 0, 0, 0, dp(12)));

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(card(R.string.grp_remote, buildAirKey(), buildRemote()), lp(-1, -2, 0, 0, 0, dp(10)));
        column.addView(card(R.string.grp_input, buildInput()), lp(-1, -2, 0, 0, 0, dp(10)));
        // SPEC-017：关于卡放在应用卡之前，应用卡仍恒在列尾（rebuildApps 会重挂）
        column.addView(card(R.string.grp_about, buildAbout()), lp(-1, -2, 0, 0, 0, dp(10)));
        // 应用卡按缓存载荷先渲染，随后 ensureApps 异步刷新（SPEC-016 FR-16-6）
        rebuildApps(parseApps(sp.getString("apps_" + curHost(), "")));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        // 不开 fillViewport：内容短于视口时会把最后一张卡拉伸到底（平板档实测）
        sv.setClipToPadding(false);
        sv.setPadding(0, 0, 0, dp(outer));
        if (tablet) {
            // 平板：内容区最大 520dp 居中，避免按键过宽
            FrameLayout wrap = new FrameLayout(this);
            int maxW = Math.min(dp(520),
                    getResources().getDisplayMetrics().widthPixels - dp(outer * 2));
            wrap.addView(column, new FrameLayout.LayoutParams(
                    maxW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
            sv.addView(wrap);
        } else {
            sv.addView(column);
        }
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    /** SPEC-012 分组卡片：SURFACE 底 20dp 圆角，左上 11sp SUB 分组标题 + 内容 */
    private View card(int titleRes, View content) {
        return card(titleRes, null, content);
    }

    /** 带标题行右侧附加按键的卡片（SPEC-018：遥控器卡右上角飞鼠键） */
    private View card(int titleRes, View titleExtra, View content) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(SURFACE, 20, 0));
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(getString(titleRes));
        t.setTextColor(SUB);
        t.setTextSize(11);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (titleExtra != null) {
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(mouseD), dp(mouseD));
            elp.rightMargin = mouseInset;
            head.addView(titleExtra, elp);
        }
        c.addView(head, lp(-1, -2, 0, 0, 0, dp(titleExtra == null ? 10 : 6)));
        c.addView(content, lp(-1, -2, 0, 0, 0, 0));
        return c;
    }

    /** 头部：设备名（点击弹选择器）+ 状态徽标（彩色圆点+状态文字） */
    private View buildHeader() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        devName = new TextView(this);
        devName.setText(devices.get(cur)[0] + "  ▾");
        devName.setTextColor(TXT);
        devName.setTextSize(17);
        info.addView(devName);

        LinearLayout srow = new LinearLayout(this);
        srow.setOrientation(LinearLayout.HORIZONTAL);
        srow.setGravity(Gravity.CENTER_VERTICAL);
        srow.setPadding(0, dp(2), 0, 0);

        dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(SUB);
        View dot = new View(this);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dlp.rightMargin = dp(6);
        dot.setLayoutParams(dlp);
        srow.addView(dot);

        status = new TextView(this);
        status.setTextColor(SUB);
        status.setTextSize(12);
        srow.addView(status);

        info.addView(srow);
        head.addView(info);
        info.setOnClickListener(v -> showDevicePicker());

        // SPEC-027：「有更新」标签，仅启动探测到更高版本时显示，点击直接看更新说明
        updateBadge = new TextView(this);
        updateBadge.setText(R.string.update_badge);
        updateBadge.setTextColor(Color.WHITE);
        updateBadge.setTextSize(11);
        updateBadge.setGravity(Gravity.CENTER);
        updateBadge.setPadding(dp(9), dp(4), dp(9), dp(4));
        updateBadge.setBackground(round(ACCENT, 999, 0));
        updateBadge.setVisibility(View.GONE);
        updateBadge.setOnClickListener(v -> {
            if (pendingUpdate != null) {
                showUpdateSheet(pendingUpdate, Updater.currentVersion(this));
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(10);
        head.addView(updateBadge, blp);

        // 语言切换入口（SPEC-009 FR-9-3）：固定「中/EN」不随语言变化，一眼可辨
        TextView langBtn = new TextView(this);
        langBtn.setText("中/EN");
        langBtn.setTextColor(SUB);
        langBtn.setTextSize(12);
        langBtn.setGravity(Gravity.CENTER);
        langBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        langBtn.setBackground(ripple(DIM, 999, 0));
        langBtn.setOnClickListener(v -> showLangDialog());
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = dp(10);
        head.addView(langBtn, llp);
        return head;
    }

    /** SPEC-009 FR-9-3/4：语言三选一对话框，选择后持久化并重建 Activity 立即生效 */
    private void showLangDialog() {
        String cur = sp.getString("lang", "system");
        String[] vals = {"system", "zh", "en"};
        String[] opts = {getString(R.string.lang_system),
                getString(R.string.lang_zh), getString(R.string.lang_en)};
        int checked = "zh".equals(cur) ? 1 : "en".equals(cur) ? 2 : 0;
        Sheet sheet = new Sheet(getString(R.string.lang_title), null);
        for (int i = 0; i < opts.length; i++) {
            final String v = vals[i];
            sheet.row(opts[i], null, i == checked, () -> {
                sp.edit().putString("lang", v).apply();
                recreate();
            });
        }
        sheet.action(getString(R.string.cancel), false, SUB).show();
    }

    /** 模式栏：两段胶囊选择器（直连/蓝牙）+ 扫描 + 添加（SPEC-013 FR-13-2） */
    private View buildModeBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(round(DIM, 999, 0));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        seg.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        modeLan = segBtn(getString(R.string.mode_lan));
        modeLan.setOnClickListener(v -> selectMode("lan"));
        // 长按直连段 = 清除当前设备的配对信息，重新配对（盒子重置或换盒子时用）
        modeLan.setOnLongClickListener(v -> {
            sp.edit().remove("lan_" + curHost()).apply();
            lan = null;
            lanMode = false;
            updateModeUi();
            Toast.makeText(this, getString(R.string.pairing_cleared), Toast.LENGTH_SHORT).show();
            showLanDialog();
            return true;
        });
        modeBt = segBtn(getString(R.string.mode_bt));
        modeBt.setOnClickListener(v -> selectMode("bt"));

        LinearLayout.LayoutParams sp1 = new LinearLayout.LayoutParams(0, dp(34), 1f);
        sp1.rightMargin = dp(3);
        seg.addView(modeLan, sp1);
        seg.addView(modeBt, new LinearLayout.LayoutParams(0, dp(34), 1f));
        bar.addView(seg);

        bar.addView(flat(getString(R.string.scan), v -> startScan()));
        bar.addView(flat(getString(R.string.add), v -> showAddDialog()));
        return bar;
    }

    // ---------------- 遥控器主卡（SPEC-012 v2：大圆盘十字 + 圆形图标键 + 音量胶囊） ----------------

    /** 遥控器主卡内容：方向盘/触控板二选一 + 四个圆形图标键 + [− 音量 +] 胶囊与静音圆键 */
    private View buildRemote() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(buildInputToggle(), lp(-1, -2, 0, 0, 0, dp(10)));

        FrameLayout host = new FrameLayout(this);
        dpadDisc = buildDpadDisc();
        // 方向盘保持原直径居中；触控板铺满卡片内容宽度，与系统键行等宽
        host.addView(dpadDisc, new FrameLayout.LayoutParams(inputD, inputD, Gravity.CENTER));
        touchpadView = buildTouchpad();
        host.addView(touchpadView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        box.addView(host, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, inputD));

        box.addView(buildSysRow(), lp(-1, -2, 0, dp(14), 0, 0));
        box.addView(buildVolRow(), lp(-1, -2, 0, dp(12), 0, 0));
        return box;
    }

    /** SPEC-023：方向盘 / 触控板分段切换（与大圆盘同宽区域内居中） */
    private View buildInputToggle() {
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(round(DIM, 999, 0));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));

        inputDpad = segBtn(getString(R.string.input_dpad));
        inputDpad.setOnClickListener(v -> setInputMode(false));
        inputTouchpadBtn = segBtn(getString(R.string.input_touchpad));
        inputTouchpadBtn.setOnClickListener(v -> setInputMode(true));

        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(34), 1f);
        p1.rightMargin = dp(3);
        seg.addView(inputDpad, p1);
        seg.addView(inputTouchpadBtn, new LinearLayout.LayoutParams(0, dp(34), 1f));

        LinearLayout wrap = row();
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.addView(seg, new LinearLayout.LayoutParams(
                compact ? dp(160) : tablet ? dp(240) : dp(200), dp(40)));
        return wrap;
    }

    private TouchPadView buildTouchpad() {
        TouchPadView v = new TouchPadView(this, inputSink, 500, 100);
        v.setHintLines(getString(R.string.touchpad_hint_top),
                getString(R.string.touchpad_hint_bottom));
        v.setVisibility(View.GONE);
        return v;
    }

    /** 触控板手势出口：按当前连接方式分发，点击时再取模式，避免构建期绑定 */
    private final TouchPadView.Sink inputSink = new TouchPadView.Sink() {
        @Override
        public void move(int dx, int dy) {
            if (btMode) {
                if (bt != null) bt.sendMouseMove(dx, dy, 127);
            } else if (touchSession != null) {
                touchSession.move(dx, dy);
            }
        }

        @Override
        public void tap() {
            if (btMode) {
                tapBt();
            } else if (touchSession != null) {
                touchSession.tap();
            } else {
                touchpadNoChannel();
            }
        }

        @Override
        public void longTap() {
            if (btMode) {
                tapBt();
            } else if (touchSession != null) {
                touchSession.longTap();
            } else {
                touchpadNoChannel();
            }
        }

        @Override
        public void press() {
            if (btMode) {
                if (bt != null) bt.sendMouseButton(BtHid.MOUSE_LEFT);
            } else if (touchSession != null) {
                touchSession.press();
            }
        }

        @Override
        public void release() {
            if (btMode) {
                if (bt != null) bt.sendMouseButton(0);
            } else if (touchSession != null) {
                touchSession.release();
            }
        }

        @Override
        public void scroll(int steps) {
            if (btMode) {
                if (bt != null) bt.sendMouseScroll(steps);
            } else if (touchSession != null) {
                touchSession.scroll(steps);
            }
        }

        @Override
        public boolean armLongPress() {
            // 蓝牙：到点立即按下左键，盒子端从按下时刻自行计长按；直连只进入待定
            if (!btMode || bt == null || !bt.isReady()) return false;
            return bt.sendMouseButton(BtHid.MOUSE_LEFT);
        }
    };

    /** 蓝牙单击：按下后 50ms 抬起 */
    private void tapBt() {
        if (bt == null || !bt.isReady()) {
            setStatus(getString(R.string.bt_not_ready), false);
            return;
        }
        bt.sendMouseButton(BtHid.MOUSE_LEFT);
        ui.postDelayed(() -> {
            if (btMode && bt != null) bt.sendMouseButton(0);
        }, 50);
    }

    private void touchpadNoChannel() {
        if (btMode) setStatus(getString(R.string.touchpad_need_bt), false);
        else if (lanMode) setStatus(getString(R.string.touchpad_need_lan), false);
        else setStatus(getString(R.string.select_mode_first), false);
    }

    /** 切换输入方式：触控板互斥飞鼠，方向盘保留原按键布局 */
    private void setInputMode(boolean touchpad) {
        inputTouchpad = touchpad;
        sp.edit().putString("input", touchpad ? "touchpad" : "dpad").apply();
        if (touchpad) stopAirMouse(); // FR-23-5：进触控板先关飞鼠
        if (touchpadView != null) touchpadView.setVisibility(touchpad ? View.VISIBLE : View.GONE);
        if (dpadDisc != null) dpadDisc.setVisibility(touchpad ? View.GONE : View.VISIBLE);
        updateInputToggle();
        if (touchpad) startTouchpad();
        else stopTouchpad();
    }

    private void updateInputToggle() {
        if (inputDpad == null) return;
        applySeg(inputDpad, !inputTouchpad);
        applySeg(inputTouchpadBtn, inputTouchpad);
    }

    /** 进入触控板：蓝牙无需会话，直连建立专用长连接并点亮光标 */
    private void startTouchpad() {
        stopTouchpad();
        if (touchpadView != null) {
            touchpadView.setTiming(btMode ? 350 : 500, btMode ? 40 : 100);
        }
        if (btMode) {
            if (bt == null || !bt.isReady() || bt.connectedDevice() == null) {
                setStatus(getString(R.string.touchpad_need_bt), false);
            }
            return;
        }
        if (lanMode && lan != null && !tokenOf(curHost()).isEmpty()) {
            touchSession = new TouchSession(curHost(), LanClient.DEFAULT_PORT, tokenOf(curHost()),
                    msg -> setStatus(Errors.friendly(this, new Exception(msg)), false));
            touchSession.showCursor();
            return;
        }
        setStatus(getString(R.string.touchpad_need_lan), false);
    }

    /** 退出触控板：先释放按压（reset 内发 release），再隐藏光标并关闭会话 */
    private void stopTouchpad() {
        if (touchpadView != null) touchpadView.reset();
        if (touchSession != null) {
            touchSession.hide();
            touchSession.close();
            touchSession = null;
        }
    }

    /** 大圆盘：整圆盘面 + 四向小圆键嵌盘缘 + 中心 OK 光环圈（参考图综合）。
     *  容器与盘面严格同尺寸（d x d），四个方向键按相同内边距定位，
     *  保证四键在环状背景的上下左右绝对对称。 */
    private View buildDpadDisc() {
        FrameLayout pad = new FrameLayout(this);
        final int d = inputD;
        pad.setLayoutParams(new LinearLayout.LayoutParams(d, d));

        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(0xFF1B2432);
        disc.setStroke(1, 0xFF2A3547);
        View plate = new View(this);
        plate.setBackground(disc);
        pad.addView(plate, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int m = dp(14);
        padKey(pad, "▲", 19, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, m, 0, 0);
        padKey(pad, "▼", 20, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, m);
        padKey(pad, "◀", 21, Gravity.LEFT | Gravity.CENTER_VERTICAL, m, 0, 0, 0);
        padKey(pad, "▶", 22, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, m, 0);

        TextView ok = new TextView(this);
        ok.setText("OK");
        ok.setTextColor(Color.WHITE);
        ok.setTypeface(null, android.graphics.Typeface.BOLD);
        ok.setTextSize(okText);
        ok.setGravity(Gravity.CENTER);
        ok.setBackground(okRing());
        // SPEC-018：OK 区分短按/长按。蓝牙发按下/抬起（盒子自行判定长按），
        // 直连按住达到 500ms 立即发 KEYLONG、提前抬手发 KEY；飞鼠模式下 OK 为鼠标左键。
        ok.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // 按住期间禁止外层滚动容器拦截，否则手指轻微移动会变成 ACTION_CANCEL
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    okDownAt = SystemClock.uptimeMillis();
                    okLongFired = false;
                    okDown();
                    if (lanMode && !airMouse) ui.postDelayed(okLongTask, LONG_PRESS_MS);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    return true;
                case MotionEvent.ACTION_UP:
                    ui.removeCallbacks(okLongTask);
                    if (!okLongFired) okUp(SystemClock.uptimeMillis() - okDownAt);
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    ui.removeCallbacks(okLongTask);
                    if (!okLongFired) okCancel();
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return true;
            }
        });
        pad.addView(ok, new FrameLayout.LayoutParams(dp(okD), dp(okD), Gravity.CENTER));
        return pad;
    }

    /** 四向小圆键（叠放在大圆盘上，gravity + margin 定位到盘缘）。
     *  圆底用 #2E3A4C：比盘面 #1B2432 亮一档，保留按键轮廓感 */
    private void padKey(FrameLayout pad, String arrow, int code, int gravity,
                        int l, int t, int r, int b) {
        TextView k = new TextView(this);
        k.setText(arrow);
        k.setTextColor(TXT);
        k.setTextSize(arrowText);
        k.setGravity(Gravity.CENTER);
        k.setBackground(ripple(0xFF2E3A4C, arrowLen / 2f, 0));
        k.setOnClickListener(v -> press(code));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(arrowLen), dp(arrowLen), gravity);
        p.setMargins(l, t, r, b);
        pad.addView(k, p);
    }

    /** 中心 OK 光环圈：深底 + 3dp ACCENT 描边（参考图青环 OK 的深色版） */
    private RippleDrawable okRing() {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0xFF101720);
        ring.setStroke(dp(3), ACCENT);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), ring, mask);
    }

    /** 圆形图标键行：返回 ← / 主页 ⌂ / 菜单 ≡ / 电源（红字回退）。
     *  与音量行共用四列网格：每列等宽，按键在列内居中 */
    private View buildSysRow() {
        LinearLayout r = row();
        r.addView(cell(circleKey("←", 4, sysIcon, TXT)), gridCol());
        r.addView(cell(circleKey("⌂", 3, sysIcon, TXT)), gridCol());
        r.addView(cell(circleKey("≡", 82, sysIcon, TXT)), gridCol());
        r.addView(cell(circleKey(getString(R.string.key_power), 26, 11, POWER)), gridCol());
        return r;
    }

    /** 音量行：[− 音量 +] 横胶囊 + 静音圆键占第四列。
     *  胶囊左端内缩到返回圆键左边缘（与输入行同一起点），右端仍止于第三列右界，
     *  静音圆键与电源键保持逐列对齐 */
    private View buildVolRow() {
        LinearLayout r = row();
        int col = contentWidth() / 4;
        int inset = Math.max(0, (col - dp(sysLen)) / 2);

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(ripple(SURFACE2, keyH / 2f, 0));
        pill.addView(pillEnd("−", 25), pillCell());
        TextView mid = new TextView(this);
        mid.setText(getString(R.string.volume));
        mid.setTextColor(SUB);
        mid.setTextSize(12);
        mid.setGravity(Gravity.CENTER);
        pill.addView(mid, pillCell());
        pill.addView(pillEnd("+", 24), pillCell());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                col * 3 - inset, dp(keyH));
        plp.leftMargin = inset;
        r.addView(pill, plp);

        r.addView(cell(circleKey(getString(R.string.key_mute), 164, 11, TXT)), gridCol());
        return r;
    }

    // ---------------- 飞鼠开关（SPEC-018） ----------------

    /** 遥控器卡右上角的飞鼠圆键：与电源键同直径，右边缘对齐电源键（参考实体遥控器） */
    private View buildAirKey() {
        // 电源键是系统键行四列中的第 4 列，圆键在列内居中，
        // 因此右边缘到卡片内容右界的距离 = (列宽 - 圆键直径) / 2
        mouseD = sysLen;
        mouseInset = Math.max(0, (contentWidth() / 4 - dp(mouseD)) / 2);
        TextView b = new TextView(this);
        b.setText("↖");
        b.setTextColor(TXT);
        b.setTextSize(sysIcon + 2);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(SURFACE2, mouseD / 2f, 0));
        b.setOnClickListener(v -> toggleAirMouse());
        mouseBtn = b;
        return b;
    }

    private void toggleAirMouse() {
        Log.i("BtHid", "toggleAirMouse airMouse=" + airMouse + " btMode=" + btMode
                + " ready=" + (bt != null && bt.isReady()) + " dev=" + (bt == null ? null : bt.connectedDevice()));
        if (airMouse) {
            stopAirMouse();
            setStatus(getString(R.string.air_off), true);
            return;
        }
        // 直连没有移动系统光标的能力，飞鼠只在蓝牙 HID 模式可用
        if (!btMode) {
            setStatus(getString(R.string.air_need_bt), false);
            return;
        }
        // 已注册 HID 但未连上盒子时，sendReport 没有目标，飞鼠会静默不动
        if (bt == null || !bt.isReady() || bt.connectedDevice() == null) {
            setStatus(getString(R.string.bt_not_ready), false);
            return;
        }
        if (sensorManager == null) {
            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
            gyro = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }
        if (gyro == null) {
            setStatus(getString(R.string.air_no_sensor), false);
            return;
        }
        if (!sensorManager.registerListener(gyroListener, gyro, SensorManager.SENSOR_DELAY_GAME)) {
            setStatus(getString(R.string.air_no_sensor), false);
            return;
        }
        // SPEC-023：触控板与飞鼠互斥，开飞鼠时自动切回方向盘
        if (inputTouchpad) setInputMode(false);
        airMouse = true;
        updateMouseBtn();
        setStatus(getString(R.string.air_on), true);
    }

    private void stopAirMouse() {
        airMouse = false;
        if (sensorManager != null) sensorManager.unregisterListener(gyroListener);
        if (bt != null) bt.releaseAll(); // 补发释放，避免左键卡在按下状态
        updateMouseBtn();
    }

    private void updateMouseBtn() {
        if (mouseBtn == null) return;
        mouseBtn.setBackground(airMouse ? rippleGrad(999) : ripple(SURFACE2, mouseD / 2f, 0));
        mouseBtn.setTextColor(airMouse ? Color.WHITE : TXT);
    }

    /**
     * 陀螺仪 -> 鼠标位移：手握着手机左右摆动主要是绕屏幕竖直轴（Y）转动，
     * 上下点头是绕水平轴（X）转动；Z（绕屏幕法线）只在平放打转时才有值，
     * 不能用作水平位移（实测用 Z 时正常握持转动光标不动）。
     */
    private final SensorEventListener gyroListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            if (!airMouse || bt == null) return;
            float gy = e.values[1];
            float gx = e.values[0];
            if (Math.abs(gy) < GYRO_DEAD_ZONE) gy = 0;
            if (Math.abs(gx) < GYRO_DEAD_ZONE) gx = 0;
            int dx = Math.round(-gy * GYRO_SCALE);
            int dy = Math.round(gx * GYRO_SCALE);
            if (dx != 0 || dy != 0) {
                boolean ok = bt.sendMouseMove(dx, dy);
                long t = SystemClock.uptimeMillis();
                if (t - lastGyroLog > 500) {
                    lastGyroLog = t;
                    Log.i("BtHid", "gyro x=" + gx + " y=" + gy + " dx=" + dx + " dy=" + dy + " ok=" + ok);
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    /** 卡片内容区宽度（像素）：页面可用宽 - 卡片左右内边距 14dp×2（card() 的 padding） */
    private int contentWidth() {
        int w = getResources().getDisplayMetrics().widthPixels - dp(outer * 2);
        if (tablet) w = Math.min(w, dp(520));
        return w - dp(28);
    }

    /** 四列网格中的一列：等宽、高度自适应（圆键 52dp 不被压扁） */
    private LinearLayout.LayoutParams gridCol() {
        return new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    /** 胶囊内部等分格（宽度按权重、高度撑满胶囊） */
    private LinearLayout.LayoutParams pillCell() {
        return new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    /** 胶囊端键（− / +）：18sp 可点 */
    private TextView pillEnd(String s, int code) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(TXT);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(v -> press(code));
        return t;
    }

    /** 圆形按键（图标/短文字），surrounding cell 居中 */
    private TextView circleKey(String label, int code, int textSize, int color) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(color);
        t.setTextSize(textSize);
        t.setGravity(Gravity.CENTER);
        t.setBackground(ripple(SURFACE2, sysLen / 2f, 0));
        t.setOnClickListener(v -> press(code));
        return t;
    }

    /** 把定宽圆键包进居中 cell（等宽分布用） */
    private FrameLayout cell(TextView key) {
        FrameLayout c = new FrameLayout(this);
        c.addView(key, new FrameLayout.LayoutParams(dp(sysLen), dp(sysLen), Gravity.CENTER));
        return c;
    }

    /** 文字输入区（SPEC-012）：SURFACE2 输入框 + ACCENT 渐变发送胶囊 */
    private View buildInput() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);

        final EditText et = new EditText(this);
        et.setHint(getString(R.string.input_hint));
        et.setHintTextColor(SUB);
        et.setTextColor(TXT);
        et.setTextSize(15);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setBackground(round(SURFACE2, 12, 0));
        et.setPadding(dp(12), 0, dp(12), 0);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, dp(keyH), 1f));
        r.addView(et);

        TextView send = new TextView(this);
        send.setText(getString(R.string.send));
        send.setTextColor(Color.WHITE);
        send.setTextSize(15);
        send.setGravity(Gravity.CENTER);
        send.setBackground(rippleGrad(999));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(76), dp(keyH));
        slp.leftMargin = dp(8);
        send.setLayoutParams(slp);
        send.setOnClickListener(v -> {
            String t = et.getText().toString().trim();
            if (t.isEmpty()) return;
            doText(t);
            et.setText("");
        });
        r.addView(send);
        return r;
    }

    // ---------------- 应用快捷动态化（SPEC-016） ----------------

    /** 解析 LIST 载荷为 {应用名, 包名}：每项「包名|base64(应用名)」，
     *  旧缓存的纯包名条目按包名显示，首次刷新后自然被替换 */
    private List<String[]> parseApps(String raw) {
        List<String[]> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String t : raw.split(",")) {
            t = t.trim();
            if (t.isEmpty()) continue;
            int bar = t.indexOf('|');
            String pkg = bar < 0 ? t : t.substring(0, bar);
            String label = pkg;
            if (bar > 0 && bar < t.length() - 1) {
                try {
                    label = new String(Base64.getDecoder().decode(t.substring(bar + 1)),
                            StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ignored) {}
            }
            if (label.isEmpty()) label = pkg;
            out.add(new String[]{label, pkg});
        }
        Collator col = Collator.getInstance(Locale.CHINA);
        out.sort((a, b) -> col.compare(a[0], b[0]));
        return out;
    }

    /** 重建应用卡：首项固定「设置」，其余为盒子全部第三方应用（SPEC-016 FR-16-3/4/5） */
    private void rebuildApps(List<String[]> apps) {
        if (column == null) return;
        if (appsCard != null) {
            column.removeView(appsCard);
            appsCard = null;
        }
        List<String[]> visible = new ArrayList<>();
        visible.add(new String[]{getString(R.string.app_settings), "com.android.settings"});
        visible.addAll(apps);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < visible.size(); i += 3) {
            LinearLayout r = row();
            for (int j = i; j < i + 3; j++) {
                if (j < visible.size()) {
                    r.addView(appCell(visible.get(j)[0], visible.get(j)[1]), wc(1));
                } else {
                    r.addView(new View(this), wc(1)); // 补位保持每行 3 列
                }
            }
            grid.addView(r, lp(-1, -2, 0, 0, 0, rowGap(i, visible.size())));
        }
        appsCard = card(R.string.grp_apps, grid);
        column.addView(appsCard); // 应用卡恒在列尾
    }

    /** 行间距：非最后一行 10dp */
    private int rowGap(int i, int size) {
        return i + 3 < size ? dp(10) : 0;
    }

    /** 应用按钮：图标位（首字占位 → 异步替换真实图标）+ 下方完整名称（手机桌面式两行以内） */
    private View appCell(String label, String pkg) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout slot = new FrameLayout(this);
        slot.setOnClickListener(v -> doApp(pkg));

        TextView icon = new TextView(this);
        icon.setText(label.substring(0, 1));
        icon.setTextColor(TXT);
        icon.setTextSize(17);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(ripple(SURFACE2, appIcon / 2f, 0));
        slot.addView(icon, lp(-1, -1, 0, 0, 0, 0));

        ImageView img = new ImageView(this);
        img.setVisibility(View.GONE);
        slot.addView(img, lp(-1, -1, 0, 0, 0, 0));
        loadIcon(pkg, slot, icon, img);

        cell.addView(slot, lp(dp(appIcon), dp(appIcon), 0, 0, 0, dp(8)));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(TXT);
        name.setTextSize(appText);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        cell.addView(name, lp(-1, -2, 0, 0, 0, 0));
        return cell;
    }

    /** SPEC-019：图标懒加载，命中缓存直接显示；失败保持首字占位，不影响点击与名称 */
    private void loadIcon(final String pkg, final View slot, final TextView ph, final ImageView img) {
        final String key = curHost() + "|" + pkg;
        Bitmap cached = iconCache.get(key);
        if (cached != null) {
            img.setImageBitmap(cached);
            img.setVisibility(View.VISIBLE);
            ph.setVisibility(View.GONE);
            return;
        }
        if (!lanMode || lan == null) return; // 蓝牙模式没有应用/图标通道
        final LanClient client = lan;
        worker.submit(() -> {
            byte[] png;
            try {
                png = client.icon(pkg);
            } catch (Exception e) {
                Log.w("tvremote", "icon pull failed: " + pkg, e);
                return;
            }
            final Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bmp == null) return;
            runOnUiThread(() -> {
                iconCache.put(key, bmp);
                if (slot.getParent() == null) return; // 卡片已重建，丢弃本次结果
                img.setImageBitmap(bmp);
                img.setVisibility(View.VISIBLE);
                ph.setVisibility(View.GONE);
            });
        });
    }

    /** 异步拉取盒子已装包名（仅直连 LIST，SPEC-013 FR-13-6），成功写缓存并重建应用卡 */
    private void ensureApps() {
        final String host = curHost();
        worker.submit(() -> {
            String raw = null;
            if (lanMode && lan != null) {
                try {
                    raw = lan.list();
                } catch (Exception e) {
                    // 拉取失败走缓存（FR-12-3），无缓存则应用卡保持隐藏
                    android.util.Log.w("tvremote", "ensureApps pull failed", e);
                }
            }
            if (raw != null) {
                sp.edit().putString("apps_" + host, raw).apply();
                android.util.Log.i("tvremote", "apps pull ok, len=" + raw.length()
                        + " count=" + parseApps(raw).size());
            } else {
                raw = sp.getString("apps_" + host, "");
                android.util.Log.i("tvremote", "apps pull failed, cache count="
                        + parseApps(raw).size());
            }
            final List<String[]> apps = parseApps(raw);
            runOnUiThread(() -> rebuildApps(apps));
        });
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams w(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(keyH), weight);
        p.rightMargin = dp(6);
        return p;
    }

    /** 等宽但高度自适应的权重参数（图标 + 下方名称这类纵向单元用） */
    private LinearLayout.LayoutParams wc(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        p.rightMargin = dp(6);
        return p;
    }

    private LinearLayout.LayoutParams lp(int ww, int hh, int l, int t, int r, int bb) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ww, hh);
        p.leftMargin = l; p.topMargin = t; p.rightMargin = r; p.bottomMargin = bb;
        return p;
    }

    private TextView segBtn(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView flat(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(SUB);
        t.setTextSize(13);
        t.setPadding(dp(10), dp(6), dp(4), dp(6));
        t.setOnClickListener(l);
        return t;
    }

    /** 实心圆角 + ripple 触摸反馈（API 21+，minSdk 26 可用） */
    private RippleDrawable ripple(int fill, float radiusDp, int stroke) {
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(radiusDp));
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                round(fill, radiusDp, stroke), mask);
    }

    /** ACCENT 渐变底（#5B8CFF→#3E6AF0）+ ripple，radiusDp 取直径一半即为圆 */
    private RippleDrawable rippleGrad(float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(radiusDp));
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                accentGrad(radiusDp), mask);
    }

    private GradientDrawable accentGrad(float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF5B8CFF, 0xFF3E6AF0});
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable round(int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(1, stroke);
        return g;
    }

    // ---------------- 关于与 OTA 更新（SPEC-017） ----------------

    /** 关于卡：当前版本 + 检查更新入口 */
    private View buildAbout() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);

        TextView ver = new TextView(this);
        ver.setText(getString(R.string.update_current, Updater.currentVersion(this)));
        ver.setTextColor(SUB);
        ver.setTextSize(13);
        l.addView(ver, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btn = new TextView(this);
        btn.setText(R.string.update_check);
        btn.setTextColor(TXT);
        btn.setTextSize(13);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(ripple(SURFACE2, 999, 0));
        btn.setPadding(dp(14), dp(7), dp(14), dp(7));
        btn.setOnClickListener(v -> checkUpdate());
        l.addView(btn);
        return l;
    }

    /** 读取 GitHub 最新 Release，发现新版本时弹更新说明 */
    private void checkUpdate() {
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show();
        worker.submit(() -> {
            try {
                final Updater.Release r = Updater.fetchLatest(this);
                final String cur = Updater.currentVersion(this);
                final boolean newer = Updater.isNewer(r.tag, cur);
                runOnUiThread(() -> {
                    if (newer) {
                        showUpdateBadge(r);
                        showUpdateSheet(r, cur);
                    } else {
                        hideUpdateBadge();
                        Toast.makeText(this, getString(R.string.update_latest,
                                Updater.displayVersion(cur)), Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "" : e.getMessage();
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.update_failed, msg), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** SPEC-027：启动时后台静默探测，只有发现更高版本才点亮「有更新」标签 */
    private void autoCheckUpdate() {
        worker.submit(() -> {
            try {
                final Updater.Release r = Updater.fetchLatest(this);
                if (!Updater.isNewer(r.tag, Updater.currentVersion(this))) return;
                runOnUiThread(() -> showUpdateBadge(r));
            } catch (Exception ignored) {
                // 启动探测静默失败：不打扰遥控主流程，用户仍可在「关于」卡手动重试
            }
        });
    }

    private void showUpdateBadge(Updater.Release r) {
        pendingUpdate = r;
        updateBadge.setVisibility(View.VISIBLE);
    }

    private void hideUpdateBadge() {
        pendingUpdate = null;
        updateBadge.setVisibility(View.GONE);
    }

    private void showUpdateSheet(final Updater.Release r, String curVersion) {
        Sheet sheet = new Sheet(
                getString(R.string.update_found_title, Updater.displayVersion(r.tag)),
                getString(R.string.update_current, curVersion));
        String notes = r.notes == null ? "" : r.notes.trim();
        if (!notes.isEmpty()) {
            sheet.text(notes.length() > 600 ? notes.substring(0, 600) + "…" : notes);
        }
        sheet.action(getString(R.string.update_later), false, SUB)
                .action(getString(R.string.update_download), true, Color.WHITE,
                        () -> downloadUpdate(r))
                .show();
    }

    /** 后台下载 APK，完成后拉起系统安装确认 */
    private void downloadUpdate(final Updater.Release r) {
        Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show();
        worker.submit(() -> {
            try {
                final File apk = Updater.download(this, r);
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.update_install_hint, Toast.LENGTH_SHORT).show();
                    Updater.install(this, apk);
                });
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "" : e.getMessage();
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.update_failed, msg), Toast.LENGTH_LONG).show());
            }
        });
    }

    // ---------------- 自绘弹窗（SPEC-012 FR-12-7：不使用原生 AlertDialog 外观） ----------------

    /**
     * 弹窗容器：深色 SURFACE 卡片 + 22dp 圆角 + 暗遮罩。
     * 结构 = 标题（可选副标题）→ 内容区 → 底部动作行（右对齐）。
     * 用法：new Sheet(标题, 副标题).row(...).action(...).show()
     */
    private class Sheet {
        private final Dialog dialog;
        private final LinearLayout body;
        private final LinearLayout actions;

        Sheet(String title, String subtitle) {
            dialog = new Dialog(MainActivity.this);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(round(SURFACE, 22, 0));
            card.setPadding(dp(20), dp(18), dp(20), dp(16));

            TextView t = new TextView(MainActivity.this);
            t.setText(title);
            t.setTextColor(TXT);
            t.setTextSize(18);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            card.addView(t, lp(-1, -2, 0, 0, 0, dp(subtitle == null ? 6 : 8)));

            if (subtitle != null) {
                TextView s = new TextView(MainActivity.this);
                s.setText(subtitle);
                s.setTextColor(SUB);
                s.setTextSize(13);
                s.setLineSpacing(dp(3), 1f);
                card.addView(s, lp(-1, -2, 0, 0, 0, dp(6)));
            }

            body = new LinearLayout(MainActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            card.addView(body, lp(-1, -2, 0, dp(4), 0, 0));

            actions = new LinearLayout(MainActivity.this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            card.addView(actions, lp(-1, -2, 0, dp(16), 0, 0));

            FrameLayout root = new FrameLayout(MainActivity.this);
            root.setPadding(dp(24), dp(24), dp(24), dp(24));
            int maxW = Math.min(getResources().getDisplayMetrics().widthPixels - dp(48),
                    dp(tablet ? 520 : 420));
            root.addView(card, new FrameLayout.LayoutParams(maxW,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            dialog.setContentView(root);

            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setDimAmount(0.6f);
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT);
            }
        }

        LinearLayout body() { return body; }

        /** 列表行：标题 + 可选副标题 + 选中圆点（选中行 12% ACCENT 底） */
        Sheet row(String title, String sub, boolean checked, Runnable onClick) {
            LinearLayout r = new LinearLayout(MainActivity.this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackground(checked ? round(0x1F4F7CFF, 14, 0) : ripple(DIM, 14, 0));
            r.setPadding(dp(14), dp(12), dp(12), dp(12));

            LinearLayout txt = new LinearLayout(MainActivity.this);
            txt.setOrientation(LinearLayout.VERTICAL);
            TextView n = new TextView(MainActivity.this);
            n.setText(title);
            n.setTextColor(TXT);
            n.setTextSize(15);
            txt.addView(n);
            if (sub != null) {
                TextView sd = new TextView(MainActivity.this);
                sd.setText(sub);
                sd.setTextColor(SUB);
                sd.setTextSize(12);
                txt.addView(sd, lp(-2, -2, 0, dp(3), 0, 0));
            }
            r.addView(txt, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (checked) {
                TextView dot = new TextView(MainActivity.this);
                dot.setText("●");
                dot.setTextColor(ACCENT);
                dot.setTextSize(12);
                r.addView(dot);
            }
            r.setOnClickListener(v -> {
                dialog.dismiss();
                if (onClick != null) onClick.run();
            });
            body.addView(r, lp(-1, -2, 0, 0, 0, dp(8)));
            return this;
        }

        /** 输入框：SURFACE2 底 12dp 圆角 48dp 高 */
        Sheet field(EditText et) {
            et.setBackground(round(SURFACE2, 12, 0));
            et.setTextColor(TXT);
            et.setHintTextColor(SUB);
            et.setTextSize(15);
            et.setSingleLine(true);
            et.setPadding(dp(12), 0, dp(12), 0);
            body.addView(et, lp(-1, dp(48), 0, 0, 0, dp(10)));
            return this;
        }

        /** 说明文字（多行） */
        Sheet text(String s) {
            TextView t = new TextView(MainActivity.this);
            t.setText(s);
            t.setTextColor(SUB);
            t.setTextSize(13);
            t.setLineSpacing(dp(3), 1f);
            body.addView(t, lp(-1, -2, 0, dp(2), 0, dp(6)));
            return this;
        }

        /** 底部动作：主操作=ACCENT 渐变胶囊白字；其余传 SUB 或红色 */
        Sheet action(String label, boolean primary, int color, Runnable onClick) {
            TextView b = new TextView(MainActivity.this);
            b.setText(label);
            b.setTextSize(14);
            b.setGravity(Gravity.CENTER);
            if (primary) {
                b.setTextColor(Color.WHITE);
                b.setTypeface(null, android.graphics.Typeface.BOLD);
                b.setBackground(rippleGrad(999));
                b.setPadding(dp(20), 0, dp(20), 0);
            } else {
                b.setTextColor(color);
                b.setPadding(dp(14), 0, dp(14), 0);
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            p.leftMargin = dp(4);
            b.setLayoutParams(p);
            b.setOnClickListener(v -> {
                dialog.dismiss();
                if (onClick != null) onClick.run();
            });
            actions.addView(b);
            return this;
        }

        Sheet action(String label, boolean primary, int color) {
            return action(label, primary, color, null);
        }

        Sheet onDismiss(Runnable r) {
            dialog.setOnDismissListener(d -> r.run());
            return this;
        }

        void show() { dialog.show(); }

        void dismiss() { dialog.dismiss(); }
    }

    // ---------------- 模式切换（SPEC-013：只有直连 / 蓝牙） ----------------

    /** 模式选择入口：lan=直连（当前设备未配对则弹配对框），bt=蓝牙 HID */
    private void selectMode(String m) {
        if (m.equals("lan")) {
            if (!tokenOf(curHost()).isEmpty()) enableLan();
            else showLanDialog();
        } else {
            startBt();
        }
    }

    /** 当前设备已配对：直接启用直连模式，控制目标 = 当前设备 */
    private void enableLan() {
        stopTouchpad(); // SPEC-023：先释放按压再切换直连目标
        if (btMode && bt != null) bt.disconnect();
        stopAirMouse(); // SPEC-018：飞鼠只在蓝牙模式可用
        lan = new LanClient(curHost(), LanClient.DEFAULT_PORT, tokenOf(curHost()));
        lanMode = true;
        btMode = false;
        saveMode(curHost(), "lan");
        updateModeUi();
        setStatus(getString(R.string.lan_paired_status, curHost()), true);
        ensureApps();
        if (inputTouchpad) startTouchpad();
    }

    /** SPEC-015：切换/启动设备时，按该设备上次成功使用的方式恢复连接 */
    private void applyDeviceMode() {
        String mode = modeOf(curHost());
        stopTouchpad(); // SPEC-023：切设备前先停止触控板，避免命令发到新设备
        stopAirMouse(); // SPEC-018：切设备时退出飞鼠，避免对着旧盒子移动光标
        if (btMode && bt != null) bt.disconnect();
        lan = null;
        lanMode = false;
        btMode = false;
        updateModeUi();
        if ("bt".equals(mode)) {
            startBt();
            return;
        }
        if (!tokenOf(curHost()).isEmpty()) enableLan();
        else setStatus(getString(R.string.lan_not_paired), false);
    }

    // ---------------- 模式高亮（模式按钮常显，SPEC-013 FR-13-7） ----------------

    /** 按当前模式高亮两段选择器 */
    private void updateModeUi() {
        if (modeLan == null) return;
        applySeg(modeLan, lanMode);
        applySeg(modeBt, btMode);
    }

    private void applySeg(TextView t, boolean on) {
        t.setBackground(on ? rippleGrad(999) : null);
        t.setTextColor(on ? Color.WHITE : SUB);
    }

    // ---------------- 遥控逻辑 ----------------

    private void press(int code) {
        if (lanMode) {
            lanPress(code);
            return;
        }
        if (btMode) {
            btPress(code);
            return;
        }
        setStatus(getString(R.string.select_mode_first), false);
    }

    // ---------------- OK 键按下/抬起（SPEC-018） ----------------

    private void okDown() {
        if (airMouse) {
            if (bt != null) bt.sendMouseButton(BtHid.MOUSE_LEFT);
            return;
        }
        if (btMode) {
            // 蓝牙：按下即发送并保持，抬起才释放，盒子端可按按住时长判定长按
            if (bt == null || !bt.isReady()) {
                setStatus(getString(R.string.bt_not_ready), false);
                return;
            }
            btSend(bt.sendKeyDown(BtHid.K_ENTER));
        }
        // 直连：协议只有一次性命令，抬手时再按按住时长选 KEY 或 KEYLONG
    }

    private void okUp(long heldMs) {
        if (airMouse) {
            if (bt != null) bt.sendMouseButton(0);
            return;
        }
        if (btMode) {
            if (bt != null) bt.sendKeyUp();
            return;
        }
        if (lanMode) {
            if (heldMs >= LONG_PRESS_MS) lanPressLong(23);
            else lanPress(23);
            return;
        }
        setStatus(getString(R.string.select_mode_first), false);
    }

    private void okCancel() {
        if (airMouse) {
            if (bt != null) bt.sendMouseButton(0);
            return;
        }
        if (btMode && bt != null) bt.sendKeyUp();
    }

    /** 手机 keycode → 蓝牙 HID usage 映射 */
    private void btPress(int code) {
        if (bt == null || !bt.isReady()) {
            setStatus(getString(R.string.bt_not_ready), false);
            return;
        }
        switch (code) {
            case 19: btSend(bt.sendKey(BtHid.K_UP)); break;
            case 20: btSend(bt.sendKey(BtHid.K_DOWN)); break;
            case 21: btSend(bt.sendKey(BtHid.K_LEFT)); break;
            case 22: btSend(bt.sendKey(BtHid.K_RIGHT)); break;
            case 23: btSend(bt.sendKey(BtHid.K_ENTER)); break;
            // SPEC-005：Home/Back 走消费者控制，手机与电视盒子都识别为系统动作
            case 4: btSend(bt.sendConsumer(BtHid.C_BACK)); break;
            case 3: btSend(bt.sendConsumer(BtHid.C_HOME)); break;
            case 82: btSend(bt.sendKey(0x65)); break;
            case 24: btSend(bt.sendConsumer(BtHid.C_VOL_UP)); break;
            case 25: btSend(bt.sendConsumer(BtHid.C_VOL_DOWN)); break;
            case 164: btSend(bt.sendConsumer(BtHid.C_MUTE)); break;
            case 26: btSend(bt.sendConsumer(0x30)); break;
            default: break;
        }
    }

    /** 蓝牙发送失败时明确提示，避免按键看起来没反应 */
    private void btSend(boolean ok) {
        if (!ok) setStatus(getString(R.string.bt_send_failed), false);
    }

    private void setStatus(final String s, final boolean ok) {
        runOnUiThread(() -> {
            status.setText(s);
            status.setTextColor(ok ? GREEN : RED);
            if (dotBg != null) dotBg.setColor(ok ? GREEN : RED);
        });
    }

    // ---------------- 设备管理 ----------------

    private void showDevicePicker() {
        Sheet sheet = new Sheet(getString(R.string.device_picker_title), null);
        for (int i = 0; i < devices.size(); i++) {
            final int idx = i;
            final String[] d = devices.get(i);
            // SPEC-007 FR-7-4：未配对的盒子在列表中明确标注
            String sub = tokenOf(d[1]).isEmpty() ? getString(R.string.scan_unpaired, d[1]) : d[1];
            sheet.row(d[0], sub, i == cur, () -> switchTo(idx));
        }
        sheet.action(getString(R.string.delete_current), false, RED,
                        () -> removeCurrent())
                .action(getString(R.string.cancel), false, SUB)
                .show();
    }

    private void switchTo(int idx) {
        cur = idx;
        devName.setText(devices.get(cur)[0] + "  ▾");
        saveDevices();
        // SPEC-015：连接方式与蓝牙目标跟随设备，恢复该设备上次成功使用的方式
        applyDeviceMode();
    }

    /** SPEC-011：定位该 IP 的设备；不在列表则新增（名称取 IP），并切换为当前设备 */
    private void focusDevice(String ip) {
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i)[1].equals(ip)) {
                cur = i;
                devName.setText(devices.get(cur)[0] + "  ▾");
                saveDevices();
                        return;
            }
        }
        devices.add(new String[]{ip, ip});
        cur = devices.size() - 1;
        devName.setText(devices.get(cur)[0] + "  ▾");
        saveDevices();
    }

    private void removeCurrent() {
        if (devices.size() <= 1) {
            Toast.makeText(this, getString(R.string.keep_one_device), Toast.LENGTH_SHORT).show();
            return;
        }
        String removed = devices.get(cur)[1];
        clearDevicePrefs(removed);
        devices.remove(cur);
        cur = 0;
        devName.setText(devices.get(cur)[0] + "  ▾");
        saveDevices();
        applyDeviceMode();
    }

    private void showAddDialog() {
        final EditText name = new EditText(this);
        name.setHint(getString(R.string.add_name_hint));

        final EditText addr = new EditText(this);
        addr.setHint(getString(R.string.add_addr_hint));
        addr.setInputType(InputType.TYPE_CLASS_TEXT);

        new Sheet(getString(R.string.add_title), null)
                .field(name)
                .field(addr)
                .action(getString(R.string.cancel), false, SUB)
                .action(getString(R.string.add_positive), true, SUB, () -> {
                    String h = addr.getText().toString().trim();
                    if (h.isEmpty()) return;
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) n = h;
                    addAndConnect(n, h);
                })
                .show();
    }

    private void addAndConnect(String name, String ip) {
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i)[1].equals(ip)) {
                switchTo(i);
                break;
            }
        }
        if (cur < 0 || !devices.get(cur)[1].equals(ip)) {
            devices.add(new String[]{name, ip});
            cur = devices.size() - 1;
            devName.setText(devices.get(cur)[0] + "  ▾");
            saveDevices();
        }
        if (!tokenOf(ip).isEmpty()) enableLan();
        else showLanDialog();
    }

    // ---------------- 局域网直连（盒子端 App，不需开发者选项） ----------------

    private void showLanDialog() {
        // 当前设备已经配对过：直接进入直连模式，不再要求输入配对码
        if (!tokenOf(curHost()).isEmpty()) {
            enableLan();
            return;
        }

        final EditText ip = new EditText(this);
        ip.setHint(getString(R.string.lan_ip_hint));
        ip.setText(curHost());

        final EditText code = new EditText(this);
        code.setHint(getString(R.string.lan_code_hint));
        code.setInputType(InputType.TYPE_CLASS_NUMBER);

        new Sheet(getString(R.string.lan_title), getString(R.string.lan_msg))
                .field(ip)
                .field(code)
                .action(getString(R.string.lan_discover), false, SUB, () -> doDiscover())
                .action(getString(R.string.cancel), false, SUB)
                .action(getString(R.string.lan_pair), true, SUB, () ->
                        doPair(ip.getText().toString().trim(), code.getText().toString().trim()))
                .show();
    }

    private void doPair(final String h, final String c) {
        if (h.isEmpty() || c.isEmpty()) {
            setStatus(getString(R.string.ip_code_required), false);
            return;
        }
        setStatus(getString(R.string.pairing, h), true);
        worker.submit(() -> {
            try {
                String tok = LanClient.pair(h, LanClient.DEFAULT_PORT, c, 5000);
                saveToken(h, tok);
                runOnUiThread(() -> {
                    // 配对哪台就控制哪台：把该 IP 定位/新增为当前设备，直连立即可用
                    focusDevice(h);
                    stopTouchpad(); // SPEC-023：先释放按压再切换直连目标
                    if (btMode && bt != null) bt.disconnect();
                    lan = new LanClient(h, LanClient.DEFAULT_PORT, tok);
                    lanMode = true;
                    btMode = false;
                    saveMode(h, "lan");
                    updateModeUi();
                    setStatus(getString(R.string.paired, h), true);
                    ensureApps();
                    if (inputTouchpad) startTouchpad();
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus(Errors.friendly(this, e), false));
            }
        });
    }

    private void doDiscover() {
        setStatus(getString(R.string.discovering), true);
        worker.submit(() -> {
            final String ip = LanClient.discover(4500);
            runOnUiThread(() -> {
                if (ip != null) {
                    focusDevice(ip);
                    setStatus(getString(R.string.discovered, ip), true);
                    showLanDialog();
                } else {
                    setStatus(getString(R.string.discover_none), false);
                }
            });
        });
    }

    private void lanPress(final int code) {
        if (lan == null) {
            setStatus(getString(R.string.lan_not_paired), false);
            return;
        }
        worker.submit(() -> {
            try {
                lan.key(code);
                setStatus(getString(R.string.lan_status, curHost()), true);
            } catch (Exception e) {
                setStatus(Errors.friendly(this, e), false);
            }
        });
    }

    /** SPEC-018：直连长按，盒端执行 ACTION_LONG_CLICK */
    private void lanPressLong(final int code) {
        if (lan == null) {
            setStatus(getString(R.string.lan_not_paired), false);
            return;
        }
        worker.submit(() -> {
            try {
                lan.keyLong(code);
                setStatus(getString(R.string.lan_status, curHost()), true);
            } catch (Exception e) {
                setStatus(Errors.friendly(this, e), false);
            }
        });
    }

    private void doText(final String t) {
        if (lanMode && lan != null) {
            worker.submit(() -> {
                try {
                    lan.text(t);
                    setStatus(getString(R.string.lan_status, curHost()), true);
                } catch (Exception e) {
                    setStatus(Errors.friendly(this, e), false);
                }
            });
        } else {
            // SPEC-013 FR-13-6：文字注入只有直连支持
            setStatus(getString(R.string.direct_only), false);
        }
    }

    private void doApp(final String pkg) {
        if (lanMode && lan != null) {
            worker.submit(() -> {
                try {
                    lan.launch(pkg);
                    setStatus(getString(R.string.lan_status, curHost()), true);
                } catch (Exception e) {
                    setStatus(Errors.friendly(this, e), false);
                }
            });
        } else {
            // SPEC-013 FR-13-6：远程启动应用只有直连支持
            setStatus(getString(R.string.direct_only), false);
        }
    }

    // ---------------- 蓝牙模式 ----------------

    /** SPEC-022：不支持 HID 时给出浏览器替代路径，地址取当前设备 IP */
    private String webAddrText() {
        String host = curHost();
        if (host == null || host.isEmpty()) return getString(R.string.bt_web_addr_unknown);
        return getString(R.string.bt_web_addr, host);
    }

    /** 进入蓝牙模式：注册 HID；已记住盒子则直接重连，否则弹设备选择（SPEC-005 FR-5-7/5-8） */
    private void startBt() {
        stopTouchpad(); // SPEC-023：先释放按压再切蓝牙
        if (touchpadView != null) touchpadView.setTiming(350, 40);
        // Android 8.x 没有 HID Device API：点击时明确告知，模式按钮仍常显
        if (Build.VERSION.SDK_INT < 28) {
            setStatus(getString(R.string.bt_not_supported), false);
            new Sheet(getString(R.string.bt_unsupported_title),
                    getString(R.string.bt_unsupported_msg,
                            getString(R.string.bt_not_supported), webAddrText()))
                    .action(getString(R.string.ok_got_it), true, SUB)
                    .show();
            return;
        }
        if (bt == null) bt = new BtHid(this);
        if (btListener == null) btListener = new BtHid.StateListener() {
            @Override
            public void onSupport(final boolean ok, final String reason) {
                runOnUiThread(() -> {
                    if (!ok) {
                        setStatus(getString(R.string.bt_unavailable, reason), false);
                        new Sheet(getString(R.string.bt_unsupported_title),
                                getString(R.string.bt_unsupported_msg, reason, webAddrText()))
                                .action(getString(R.string.ok_got_it), true, SUB)
                                .show();
                        return;
                    }
                    lanMode = false;
                    btMode = true;
                    updateModeUi();
                    showBtDevicePicker();
                });
            }

            @Override
            public void onRegistered(final boolean ok) {
                runOnUiThread(() -> {
                    setStatus(getString(ok ? R.string.bt_registered : R.string.bt_register_failed), ok);
                    if (ok) {
                        lanMode = false;
                        btMode = true;
                        updateModeUi();
                        connectSavedBtOrPick();
                    }
                    // 注册被系统注销（退后台）不算手机不支持，重新点蓝牙即可再注册
                });
            }

            @Override
            public void onConnectionChanged(final BluetoothDevice d, final boolean connected) {
                runOnUiThread(() -> {
                    String n = (d != null && d.getName() != null)
                            ? d.getName() : getString(R.string.bt_device);
                    if (connected && d != null) {
                        lanMode = false;
                        btMode = true;
                        updateModeUi();
                        // SPEC-015：连接成功才记录，避免失败覆盖该设备上次可用的方式
                        saveMode(curHost(), "bt");
                        saveBtAddr(curHost(), d.getAddress());
                        setStatus(getString(R.string.bt_connected, n), true);
                        return;
                    }
                    btMode = false;
                    stopTouchpad(); // SPEC-023：蓝牙断开时释放触控板按压
                    stopAirMouse(); // SPEC-018：蓝牙断开后退出飞鼠
                    updateModeUi();
                    // 切设备时主动断开旧目标，不覆盖新设备的直连状态文案
                    if (!lanMode) setStatus(getString(R.string.bt_disconnected, n), false);
                });
            }
        };
        if (bt.isReady()) {
            // 已注册：切换设备时直接切到该设备的蓝牙目标，不重复注册
            lanMode = false;
            btMode = true;
            updateModeUi();
            connectSavedBtOrPick();
            return;
        }
        setStatus(getString(R.string.bt_initializing), true);
        bt.start(btListener);
    }

    /** 注册成功后优先连接该设备记住的蓝牙目标；没有记录则弹选择框 */
    private void connectSavedBtOrPick() {
        String addr = btAddrOf(curHost());
        if (!addr.isEmpty() && bt != null) {
            Set<BluetoothDevice> bonded = bt.bondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    if (d.getAddress().equals(addr)) {
                        bt.connect(d);
                        setStatus(getString(R.string.bt_connecting, d.getName() == null
                                ? addr : d.getName()), true);
                        return;
                    }
                }
            }
        }
        showBtDevicePicker();
    }

    private void showBtDevicePicker() {
        Set<BluetoothDevice> bonded = bt != null ? bt.bondedDevices() : null;
        if (bonded == null || bonded.isEmpty()) {
            setStatus(getString(R.string.bt_none_found), false);
            new Sheet(getString(R.string.bt_none_title), getString(R.string.bt_none_msg))
                    .action(getString(R.string.bt_discoverable), false, ACCENT,
                            this::requestDiscoverable)
                    .action(getString(R.string.bt_open_settings), true, SUB, () -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                        } catch (Exception ignored) {}
                    })
                    .show();
            return;
        }
        final List<BluetoothDevice> list = new ArrayList<>(bonded);
        Sheet sheet = new Sheet(getString(R.string.bt_pick_title), null);
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            BluetoothDevice d = list.get(i);
            String n = d.getName();
            sheet.row(n == null ? d.getAddress() : n, d.getAddress(), false, () -> {
                // SPEC-015：蓝牙目标记录到当前设备，切回该设备时自动连接
                saveBtAddr(curHost(), list.get(idx).getAddress());
                bt.connect(list.get(idx));
                setStatus(getString(R.string.bt_connecting, n == null ? d.getAddress() : n), true);
            });
        }
        sheet.action(getString(R.string.cancel), false, SUB).show();
    }

    /** SPEC-005 FR-5-9：让盒子能搜到本机，以 HID 设备身份完成配对 */
    private void requestDiscoverable() {
        try {
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
            startActivity(i);
            Toast.makeText(this, getString(R.string.bt_discoverable_hint),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            setStatus(Errors.friendly(this, e), false);
        }
    }

    // ---------------- UDP 扫描（SPEC-013 FR-13-5：盒子端 8898 广播） ----------------

    private boolean scanning = false;

    /** 点「扫描」立即弹窗：状态行 + 结果列表同框；扫描完成后一次列出全部盒子 */
    private void startScan() {
        if (scanning) return;
        scanning = true;

        final Sheet sheet = new Sheet(getString(R.string.scan_title), null);
        final TextView stv = new TextView(this);
        stv.setText(getString(R.string.scanning));
        stv.setTextColor(SUB);
        stv.setTextSize(13);
        sheet.body().addView(stv, lp(-1, -2, 0, dp(2), 0, dp(12)));

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sheet.body().addView(list, lp(-1, -2, 0, 0, 0, 0));
        sheet.action(getString(R.string.cancel), false, SUB);
        sheet.onDismiss(() -> scanning = false).show();

        worker.submit(() -> {
            final List<String[]> hits = LanClient.discoverAll(4000);
            runOnUiThread(() -> {
                if (!scanning) return;
                stv.setText(hits.isEmpty()
                        ? getString(R.string.scan_done_none)
                        : getString(R.string.scan_found, hits.size()));
                list.removeAllViews();
                for (final String[] h : hits) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setBackground(ripple(DIM, 14, 0));
                    row.setPadding(dp(14), dp(12), dp(14), dp(12));
                    TextView name = new TextView(this);
                    // SPEC-007 FR-7-4：未配对的盒子在发现列表中明确标注
                    name.setText(tokenOf(h[1]).isEmpty()
                            ? getString(R.string.scan_unpaired, h[0]) : h[0]);
                    name.setTextColor(TXT);
                    name.setTextSize(15);
                    row.addView(name);
                    TextView addr = new TextView(this);
                    addr.setText(getString(R.string.scan_row_tap, h[1]));
                    addr.setTextColor(SUB);
                    addr.setTextSize(12);
                    row.addView(addr, lp(-2, -2, 0, dp(3), 0, 0));
                    row.setOnClickListener(v -> {
                        sheet.dismiss();
                        addAndConnect(h[0], h[1]);
                    });
                    list.addView(row, lp(-1, -2, 0, 0, 0, dp(8)));
                }
                if (hits.isEmpty()) {
                    // 空结果时补一行可操作的原因说明（SPEC-008 错误文案要求）
                    TextView hint = new TextView(this);
                    hint.setText(getString(R.string.scan_none_msg));
                    hint.setTextColor(SUB);
                    hint.setTextSize(12);
                    hint.setLineSpacing(dp(3), 1f);
                    sheet.body().addView(hint, lp(-1, -2, 0, 0, 0, dp(4)));
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopTouchpad(); // SPEC-023：退出时释放按压并关闭直连会话
        stopAirMouse(); // SPEC-018：退出界面停止陀螺仪并释放鼠标按键
        if (bt != null) bt.stop();
        worker.shutdownNow();
    }
}
