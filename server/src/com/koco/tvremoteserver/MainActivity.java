package com.koco.tvremoteserver;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * 盒子端界面：显示服务状态、无障碍状态、配对码与本机地址（SPEC-008 / SPEC-014）。
 * 服务在 onCreate 时自动启动，之后开机也会自启。
 */
public class MainActivity extends Activity {

    // SPEC-012 配色：与手机端同套设计语言（深底 + 分组卡片 + 单一蓝色强调）
    static final int BG = 0xFF0A0E14, SURFACE = 0xFF141A24, SURFACE2 = 0xFF1C2430,
            ACCENT = 0xFF4F7CFF,
            TXT = 0xFFEAEFF7, SUB = 0xFF8A94A6, DIMTXT = 0xFF5A6472,
            GREEN = 0xFF3DDC84, RED = 0xFFFF5A5A;

    private TextView statusView, ipView, codeView, a11yView, updateView, updateBtn;
    /** SPEC-039：注入模式行与 ADB 调试状态行（均可聚焦点击） */
    private TextView injectView, adbView;
    /** 无障碍 / ADB 未开启时行尾的「去开启」按钮：可遥控聚焦，OK 直达对应系统设置 */
    private TextView a11yBtn, adbBtn;
    /** 显示异常自愈守护的状态行 */
    private TextView guardView;
    /** ADB 后台探测进行中，避免 tick 每 3 秒重复起线程 */
    private boolean probing;
    /** SPEC-024：网页遥控二维码与其提示；qrKey 记录当前二维码对应的 IP + 配对码 */
    private ImageView qrView;
    private TextView qrTip;
    /** 二维码下方三平台「添加到桌面」说明，跟随二维码一起显示/隐藏 */
    private TextView homeTip;
    private String qrKey;
    /** SPEC-033：宽屏双栏只做一次高度对齐，避免每次布局都改参数 */
    private boolean fitted;
    private GradientDrawable dotBg;
    /** 检查到的待安装 Release；为空表示当前没有可下载的新版本 */
    private Updater.Release pendingUpdate;
    private final Handler h = new Handler(Looper.getMainLooper());

    /** OTA 自动检查持久化：上次检查时间、待安装版本 tag、上次检查到的 tag（用于 diff） */
    private static final String SP_NAME = "ui";
    private static final String SP_LAST_CHECK = "ota_last_check_ms";
    private static final String SP_PENDING_TAG = "ota_pending_tag";
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000L; // 24 小时

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        ServerService.start(this);
        // 恢复上次检查缓存的待安装版本 tag（若尚未过期）
        restorePendingFromSp();
        tick();
        // 盒子界面只能遥控操作：进入即把焦点放到更新按钮，用户按 OK 立刻可用
        h.post(() -> updateBtn.requestFocus());
        // 启动时后台静默探测一次，失败静默不打扰
        autoCheckUpdate();
    }

    /** 从 SP 恢复上次检查到的待安装版本（仅 tag 相同且仍高于当前版本时才视为有效） */
    private void restorePendingFromSp() {
        SharedPreferences sp = getSharedPreferences(SP_NAME, MODE_PRIVATE);
        String savedTag = sp.getString(SP_PENDING_TAG, "");
        if (savedTag.isEmpty()) return;
        String cur = Updater.currentVersion(this);
        if (!Updater.isNewer(savedTag, cur)) return;
        // 只恢复 tag，等真正 fetchLatest 时才拿到完整的 Release（notes/sources）
        Updater.Release stub = new Updater.Release();
        stub.tag = savedTag;
        pendingUpdate = stub;
        updateView.setText(getString(R.string.ui_update_found,
                Updater.displayVersion(savedTag), cur));
        setUpdateButton(R.string.ui_update_download);
    }

    /** SPEC-043：更新按钮文案与图标同步切换 */
    private void setUpdateButton(int textRes) {
        updateBtn.setText(textRes);
        iconLeft(updateBtn,
                textRes == R.string.ui_update_download ? R.drawable.ic_download : R.drawable.ic_refresh,
                updateBtn.getCurrentTextColor());
    }

    /** SPEC-043：文字左侧图标 */
    private void iconLeft(TextView v, int res, int color) {
        Drawable d = getResources().getDrawable(res, getTheme()).mutate();
        d.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        d.setBounds(0, 0, dp(16), dp(16));
        v.setCompoundDrawables(d, null, null, null);
        v.setCompoundDrawablePadding(dp(6));
    }

    /** SPEC-043：只更新已有左侧图标的颜色，避免 tick 每秒重新分配 drawable */
    private void tintLeftIcon(TextView v, int color) {
        Drawable d = v.getCompoundDrawables()[0];
        if (d != null) d.setColorFilter(color, PorterDuff.Mode.SRC_IN);
    }

    /** SPEC-043：状态行文字与左侧图标同步变色 */
    private void setRowColor(TextView v, int color) {
        v.setTextColor(color);
        tintLeftIcon(v, color);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private ViewGroup buildUi() {
        // SPEC-033：电视/DeX 等宽屏用双栏布局，手机竖屏保持单栏
        final boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(48), dp(28), dp(48), dp(28));

        TextView title = new TextView(this);
        title.setText(getString(R.string.ui_title));
        title.setTextColor(TXT);
        title.setTextSize(26);
        root.addView(title);

        // 服务状态卡：圆点 + 运行状态 + 无障碍服务状态（可点击跳设置）
        LinearLayout statusCard = card(R.string.ui_grp_status);
        statusCard.addView(buildStatusRow(), lpRow(dp(12)));
        a11yView = new TextView(this);
        a11yView.setTextSize(13);
        a11yView.setTextColor(SUB);
        a11yView.setPadding(0, dp(8), 0, 0);
        iconLeft(a11yView, R.drawable.ic_accessibility, SUB); // SPEC-043
        // 盒子界面不响应触摸，同时设为可聚焦，便于物理遥控导航后按 OK
        a11yView.setClickable(true);
        a11yView.setFocusable(true);
        focusableRow(a11yView);
        a11yView.setOnClickListener(v -> openA11ySettings());
        bindKey(a11yView, a11yView::performClick);
        // 未开启时行尾出现「去开启」，直达系统无障碍服务列表
        a11yBtn = pill(R.string.ui_enable_now, this::openA11ySettings);
        statusCard.addView(rowWrap(a11yView, a11yBtn));

        // SPEC-039：注入模式（无障碍 / ADB）+ ADB 调试状态。
        // 盒子界面不响应触摸，同样设为可聚焦，用遥控导航后按 OK 触发
        injectView = buildRow(statusCard);
        iconLeft(injectView, R.drawable.ic_inject, SUB); // SPEC-043
        bindRow(injectView, this::onInjectToggle);
        adbView = new TextView(this);
        adbView.setTextSize(13);
        adbView.setTextColor(SUB);
        iconLeft(adbView, R.drawable.ic_adb, SUB); // SPEC-043
        bindRow(adbView, this::onAdbAction);
        // ADB 未连通时行尾出现「去开启」，与状态行动作一致（能自开则自开，否则跳开发者选项）
        adbBtn = pill(R.string.ui_enable_now, this::onAdbAction);
        statusCard.addView(rowWrap(adbView, adbBtn));
        // 显示异常自愈状态：只读展示，失败时提示人工处理
        guardView = buildRow(statusCard);

        // 配对码卡：大号蓝字 + 使用提示（二维码属于「连接地址」，见下）
        LinearLayout pairCard = card(R.string.ui_grp_pair);
        codeView = new TextView(this);
        // 窄屏卡片内宽约 270dp，64sp 六位数字会折行；48sp 可单行显示
        codeView.setTextSize(wide ? 64 : 48);
        codeView.setTextColor(ACCENT);
        codeView.setLetterSpacing(wide ? 0.25f : 0.18f);
        codeView.setGravity(Gravity.CENTER);
        pairCard.addView(codeView, lpRow(dp(12)));
        TextView pairHint = new TextView(this);
        pairHint.setText(R.string.ui_pair_hint);
        pairHint.setTextSize(13);
        pairHint.setTextColor(SUB);
        pairHint.setGravity(Gravity.CENTER);
        pairHint.setPadding(0, dp(10), 0, 0);
        pairCard.addView(pairHint);

        // 连接地址卡：IP / 端口 / 网页地址，二维码同属本卡（扫码即打开该地址）
        LinearLayout addrCard = card(R.string.ui_grp_addr);
        ipView = new TextView(this);
        ipView.setTextSize(15);
        ipView.setTextColor(TXT);
        ipView.setLineSpacing(dp(4), 1f);
        ipView.setPadding(0, dp(4), 0, 0);
        addrCard.addView(ipView, lpRow(0));

        // SPEC-024：扫码直接打开网页遥控，省去在手机上输入地址
        qrView = new ImageView(this);
        qrView.setVisibility(View.GONE);
        qrView.setPadding(dp(12), dp(12), dp(12), dp(12));
        qrView.setBackground(roundQrBg());
        qrTip = new TextView(this);
        qrTip.setText(R.string.ui_qr_tip);
        qrTip.setTextSize(13);
        qrTip.setTextColor(SUB);
        qrTip.setGravity(Gravity.CENTER);
        qrTip.setVisibility(View.GONE);

        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.topMargin = dp(16);
        qlp.gravity = Gravity.CENTER_HORIZONTAL;
        addrCard.addView(qrView, qlp);
        LinearLayout.LayoutParams qtp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qtp.topMargin = dp(10);
        addrCard.addView(qrTip, qtp);

        // 二维码下方：三平台「添加到手机桌面」操作说明，方便下次一键打开
        homeTip = new TextView(this);
        homeTip.setText(R.string.ui_home_tip);
        homeTip.setTextSize(12);
        homeTip.setTextColor(SUB);
        homeTip.setLineSpacing(dp(4), 1f);
        homeTip.setVisibility(View.GONE);
        LinearLayout.LayoutParams htp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        htp.topMargin = dp(12);
        addrCard.addView(homeTip, htp);

        // 版本更新卡（SPEC-017）：状态文字 + 检查更新/下载安装按钮，均可遥控聚焦
        LinearLayout updateCard = card(R.string.ui_grp_update);
        updateView = new TextView(this);
        updateView.setTextSize(14);
        updateView.setTextColor(TXT);
        updateView.setLineSpacing(dp(4), 1f);
        updateView.setPadding(0, dp(6), 0, dp(12));
        updateView.setText(getString(R.string.ui_update_current, Updater.currentVersion(this)));
        updateCard.addView(updateView, lpRow(0));

        updateBtn = new TextView(this);
        setUpdateButton(R.string.ui_update_check);
        updateBtn.setTextSize(15);
        updateBtn.setTextColor(TXT);
        updateBtn.setGravity(Gravity.CENTER);
        updateBtn.setPadding(dp(22), dp(10), dp(22), dp(10));
        updateBtn.setBackground(roundButton(false));
        updateBtn.setClickable(true);
        updateBtn.setFocusable(true);
        iconLeft(updateBtn, R.drawable.ic_refresh, TXT); // SPEC-043
        updateBtn.setOnClickListener(v -> onUpdateAction());
        // 实测 H96 Android 12：可聚焦 TextView 不响应 OK/ENTER 的默认点击，
        // 遥控器按 OK 时由这里显式触发点击（实体遥控与按键注入都走这条路径）
        bindKey(updateBtn, updateBtn::performClick);
        updateBtn.setOnFocusChangeListener((v, focused) -> {
            updateBtn.setBackground(roundButton(focused));
            updateBtn.setTextColor(focused ? 0xFFFFFFFF : TXT);
            tintLeftIcon(updateBtn, focused ? 0xFFFFFFFF : TXT); // SPEC-043
        });
        updateCard.addView(updateBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 遥控焦点链由 updateFocusChain 按行尾按钮的显示状态动态生成，
        // 不依赖默认的几何焦点搜索（宽屏双栏时容易跳错列）
        a11yView.setId(View.generateViewId());
        a11yBtn.setId(View.generateViewId());
        injectView.setId(View.generateViewId());
        adbView.setId(View.generateViewId());
        adbBtn.setId(View.generateViewId());
        updateBtn.setId(View.generateViewId());
        // 行内左右：文字行 <-> 行尾「去开启」
        a11yView.setNextFocusRightId(a11yBtn.getId());
        a11yBtn.setNextFocusLeftId(a11yView.getId());
        adbView.setNextFocusRightId(adbBtn.getId());
        adbBtn.setNextFocusLeftId(adbView.getId());
        updateFocusChain();

        TextView tip = new TextView(this);
        tip.setText(getString(R.string.ui_tip));
        tip.setTextSize(13);
        tip.setTextColor(DIMTXT);
        tip.setLineSpacing(dp(4), 1f);

        // SPEC-033：宽屏左右两栏（左：状态 + 配对码 + 版本更新，右：地址 + 二维码），
        // 两栏总高度对齐；首屏即可看到并扫描二维码
        if (wide) {
            LinearLayout cols = new LinearLayout(this);
            cols.setOrientation(LinearLayout.HORIZONTAL);
            final LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            addCard(left, statusCard, 0);
            addCard(left, pairCard, dp(16));
            addCard(left, updateCard, dp(16));
            final LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            addCard(right, addrCard, 0);
            cols.addView(left, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            rp.leftMargin = dp(24);
            cols.addView(right, rp);
            root.addView(cols, lpRow(dp(16)));
            root.addView(tip, lpRow(dp(16)));

            final ScrollView sv = new ScrollView(this);
            sv.setBackgroundColor(BG);
            sv.addView(root);
            // 首帧量出自然高度后再统一两栏高度（首次测量发生在 attach 之后）
            final LinearLayout fcols = cols;
            sv.post(() -> fitColumns(sv, root, title, fcols, left, right,
                    pairCard, addrCard, codeView, tip));
            return sv;
        }

        addCard(root, statusCard, dp(16));
        addCard(root, pairCard, dp(16));
        addCard(root, addrCard, dp(16));
        addCard(root, updateCard, dp(16));
        root.addView(tip, lpRow(dp(16)));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        return sv;
    }

    /**
     * SPEC-033：宽屏两栏总高度对齐。
     * 至少占满首屏可用高度（不足时允许滚动），两栏等高的剩余空间由配对码卡与地址卡吸收，
     * 使左右两栏底边平齐；二维码随地址卡置于首屏，不需要下拉。
     */
    private void fitColumns(ScrollView sv, LinearLayout root, View title, LinearLayout cols,
                            LinearLayout left, LinearLayout right, LinearLayout pairCard,
                            LinearLayout addrCard, TextView codeView, TextView tip) {
        if (fitted) return;
        int avail = sv.getHeight() - root.getPaddingTop() - root.getPaddingBottom()
                - title.getHeight() - dp(16) - tip.getHeight() - dp(16);
        int target = Math.max(avail, Math.max(left.getHeight(), right.getHeight()));
        if (target <= 0) return;
        fitted = true;
        cols.getLayoutParams().height = target;
        left.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
        right.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
        // 配对码与地址卡吸收剩余高度，左右两栏底边平齐
        fill(pairCard);
        fill(addrCard);
        // 配对码在卡片剩余空间里垂直居中
        fill(codeView);
        cols.requestLayout();
    }

    /** 让卡片在所在栏中占满剩余高度 */
    private void fill(View card) {
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) card.getLayoutParams();
        p.height = 0;
        p.weight = 1f;
        card.setLayoutParams(p);
    }


    /** SPEC-012 分组卡片：SURFACE 底 24dp 圆角，左上 12sp SUB 分组标题 */
    private LinearLayout card(int titleRes) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(roundSurface());
        c.setPadding(dp(24), dp(20), dp(24), dp(24));
        TextView t = new TextView(this);
        t.setText(getString(titleRes));
        t.setTextColor(SUB);
        t.setTextSize(12);
        c.addView(t);
        return c;
    }

    private GradientDrawable roundSurface() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(SURFACE);
        g.setCornerRadius(dp(24));
        return g;
    }

    /** 更新按钮底色：聚焦时用强调蓝，便于遥控导航时看清落点 */
    private GradientDrawable roundButton(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(focused ? ACCENT : 0xFF1C2430);
        g.setCornerRadius(dp(999));
        return g;
    }

    /** 二维码白底容器：深色界面上给扫码枪留出足够对比与静区 */
    private GradientDrawable roundQrBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFFFFFFF);
        g.setCornerRadius(dp(12));
        return g;
    }

    /** 卡片内子项的宽参数 */
    private LinearLayout.LayoutParams lpRow(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    private void addCard(LinearLayout root, View card, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        root.addView(card, p);
    }

    /** 状态行：10dp 圆点 + 状态文字 */
    private View buildStatusRow() {
        LinearLayout srow = new LinearLayout(this);
        srow.setOrientation(LinearLayout.HORIZONTAL);
        srow.setGravity(Gravity.CENTER_VERTICAL);
        dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(GREEN);
        View dot = new View(this);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dlp.rightMargin = dp(8);
        dot.setLayoutParams(dlp);
        srow.addView(dot);
        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setTextColor(GREEN);
        srow.addView(statusView);
        return srow;
    }

    // ---------------- SPEC-039 注入模式与 ADB 调试 ----------------

    /** 状态卡内的可点击文字行：13sp，行间留 8dp */
    private TextView buildRow(LinearLayout parent) {
        TextView v = new TextView(this);
        v.setTextSize(13);
        v.setTextColor(SUB);
        v.setPadding(0, dp(8), 0, 0);
        parent.addView(v);
        return v;
    }

    /**
     * 盒子界面不响应触摸：设为可聚焦，并在遥控 OK/ENTER 时显式触发点击
     * （实测 H96 Android 12 可聚焦 TextView 不自动响应 OK）。
     */
    private void bindRow(final TextView v, final Runnable action) {
        v.setClickable(true);
        v.setFocusable(true);
        focusableRow(v);
        v.setOnClickListener(x -> action.run());
        bindKey(v, action);
    }

    /** 可聚焦行统一内边距 + 焦点底色：否则遥控导航时看不出当前选中的是哪一行 */
    private void focusableRow(final TextView v) {
        v.setPadding(dp(10), dp(7), dp(10), dp(7));
        v.setBackground(rowBg(false));
        v.setOnFocusChangeListener((x, focused) -> v.setBackground(rowBg(focused)));
    }

    private GradientDrawable rowBg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(focused ? SURFACE2 : 0x00000000);
        g.setCornerRadius(dp(8));
        return g;
    }

    /** 状态行 + 行尾按钮：文字占满剩余宽度，按钮靠右并垂直居中 */
    private View rowWrap(TextView row, TextView btn) {
        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.HORIZONTAL);
        w.setGravity(Gravity.CENTER_VERTICAL);
        w.addView(row, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = dp(8);
        w.addView(btn, bp);
        return w;
    }

    /** 行尾小按钮（默认隐藏）：遥控可聚焦，OK/ENTER 显式触发点击 */
    private TextView pill(int textRes, Runnable action) {
        TextView v = new TextView(this);
        v.setText(textRes);
        v.setTextSize(12);
        v.setTextColor(ACCENT);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), dp(4), dp(12), dp(4));
        v.setBackground(pillBg(false));
        v.setClickable(true);
        v.setFocusable(true);
        v.setVisibility(View.GONE);
        v.setOnClickListener(x -> action.run());
        bindKey(v, action);
        v.setOnFocusChangeListener((x, focused) -> {
            v.setBackground(pillBg(focused));
            v.setTextColor(focused ? 0xFFFFFFFF : ACCENT);
        });
        return v;
    }

    private GradientDrawable pillBg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(focused ? ACCENT : SURFACE2);
        g.setCornerRadius(dp(999));
        return g;
    }

    /**
     * 按行尾按钮当前是否显示重建上下焦点链：
     * 无障碍 → 去开启 → 注入模式 → ADB 调试 → 去开启 → 检查更新。
     */
    private void updateFocusChain() {
        TextView[] chain = {a11yView, a11yBtn, injectView, adbView, adbBtn, updateBtn};
        TextView prev = null;
        for (TextView v : chain) {
            if (v.getVisibility() != View.VISIBLE) continue;
            if (prev != null) {
                prev.setNextFocusDownId(v.getId());
                v.setNextFocusUpId(prev.getId());
            }
            prev = v;
        }
    }

    /** 通用辅助：盒子端 TextView 需显式监听 OK/ENTER 才会触发 onClick */
    private void bindKey(View v, Runnable action) {
        v.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP
                    && (keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
                action.run();
                return true;
            }
            return false;
        });
    }

    /** 切换注入模式；切到 ADB 后立即后台握手一次，状态由 tick 反映 */
    private void onInjectToggle() {
        final boolean next = !AdbShell.enabled(this);
        AdbShell.setEnabled(this, next);
        if (next) probeAdb();
        tick();
    }

    /**
     * ADB 调试行点击：
     *   已连接 → 打开开发者选项（便于排查）
     *   有 WRITE_SECURE_SETTINGS → 直接打开 ADB 调试（首次连接需电视确认）
     *   没有权限 → 引导到开发者选项，由用户手动打开
     */
    private void onAdbAction() {
        if (AdbShell.isConnected()) {
            openDevSettings();
            return;
        }
        if (!AdbShell.enabled(this)) AdbShell.setEnabled(this, true);
        if (AdbShell.hasSecureWrite(this) && AdbShell.enableAdb(this)) {
            adbView.setText(R.string.ui_adb_on);
            setRowColor(adbView, GREEN);
            probeAdb();
            return;
        }
        adbView.setText(R.string.ui_adb_manual);
        setRowColor(adbView, RED);
        openDevSettings();
    }

    /** 后台握手一次（adbd 由系统重启后需等 1 秒左右） */
    private void probeAdb() {
        if (probing) return;
        probing = true;
        new Thread(() -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) {
            }
            AdbShell.probe(MainActivity.this);
            probing = false;
            h.post(MainActivity.this::tick);
        }, "adb-probe").start();
    }

    /** SPEC-039：跳系统开发者选项；个别电视没有该页面时退回设置首页 */
    private void openDevSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        }
    }

    /** 按当前模式与连接状态刷新两行文字 */
    private void updateInjectRows(boolean adbMode) {
        injectView.setText(adbMode ? R.string.ui_inject_adb : R.string.ui_inject_a11y);
        injectView.setTextColor(adbMode ? ACCENT : SUB);
        tintLeftIcon(injectView, adbMode ? ACCENT : SUB); // SPEC-043
        if (!adbMode) {
            adbView.setText(R.string.ui_adb_off);
            setRowColor(adbView, SUB);
            return;
        }
        boolean connected = AdbShell.isConnected();
        adbView.setText(connected ? R.string.ui_adb_connected : R.string.ui_adb_disconnected);
        setRowColor(adbView, connected ? GREEN : RED);
    }

    // ---------------- OTA 更新（SPEC-017 / SPEC-027 / SPEC-030） ----------------

    /** 按钮语义：有待安装版本则下载安装，否则重新检查 */
    private void onUpdateAction() {
        if (pendingUpdate != null) {
            downloadUpdate(pendingUpdate);
        } else {
            checkUpdate();
        }
    }

    /** SPEC-027：启动后后台静默探测，有新版本时点亮更新卡；失败静默不打扰 */
    private void autoCheckUpdate() {
        new Thread(() -> {
            try {
                final Updater.Release r = Updater.fetchLatest(this);
                final String cur = Updater.currentVersion(this);
                final boolean newer = Updater.isNewer(r.tag, cur);
                runOnUiThread(() -> {
                    setLastCheckNow();
                    if (newer) {
                        pendingUpdate = r;
                        updateView.setText(getString(R.string.ui_update_found,
                                Updater.displayVersion(r.tag), cur));
                        setUpdateButton(R.string.ui_update_download);
                        savePendingTag(r.tag);
                        focusUpdateIfIdle();
                    }
                });
            } catch (Exception ignored) {
                // 静默失败，下次 tick 定时检查时仍会重试
                setLastCheckNow();
            }
        }).start();
    }

    private void checkUpdate() {
        pendingUpdate = null;
        updateView.setText(R.string.ui_update_checking);
        setUpdateButton(R.string.ui_update_check);
        setUpdateEnabled(false);
        new Thread(() -> {
            try {
                final Updater.Release r = Updater.fetchLatest(this);
                final String cur = Updater.currentVersion(this);
                final boolean newer = Updater.isNewer(r.tag, cur);
                runOnUiThread(() -> {
                    setLastCheckNow();
                    pendingUpdate = newer ? r : null;
                    if (newer) {
                        updateView.setText(getString(R.string.ui_update_found,
                                Updater.displayVersion(r.tag), cur));
                        setUpdateButton(R.string.ui_update_download);
                        savePendingTag(r.tag);
                    } else {
                        updateView.setText(getString(R.string.ui_update_latest, cur));
                        setUpdateButton(R.string.ui_update_check);
                        clearPendingTag();
                    }
                    setUpdateEnabled(true);
                });
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "" : e.getMessage();
                runOnUiThread(() -> {
                    setLastCheckNow();
                    updateView.setText(getString(R.string.ui_update_failed, msg));
                    setUpdateButton(R.string.ui_update_check);
                    setUpdateEnabled(true);
                });
            }
        }).start();
    }

    private void downloadUpdate(final Updater.Release r) {
        updateView.setText(R.string.ui_update_downloading);
        setUpdateEnabled(false);
        // 开始下载时清除 SP 缓存的 pending tag，下载完成后若要重新检查会再次 fetchLatest
        clearPendingTag();
        new Thread(() -> {
            try {
                final File apk = Updater.download(this, r);
                runOnUiThread(() -> {
                    pendingUpdate = null;
                    setUpdateButton(R.string.ui_update_check);
                    setUpdateEnabled(true);
                    boolean submitted = Updater.install(this, apk);
                    updateView.setText(submitted
                            ? R.string.ui_update_ready : R.string.update_perm_required);
                });
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "" : e.getMessage();
                runOnUiThread(() -> {
                    updateView.setText(getString(R.string.ui_update_failed, msg));
                    setUpdateButton(R.string.ui_update_check);
                    setUpdateEnabled(true);
                });
            }
        }).start();
    }

    private void setUpdateEnabled(boolean on) {
        updateBtn.setEnabled(on);
        updateBtn.setAlpha(on ? 1f : 0.45f);
    }

    /** 后台探测到新版本时，若用户还没在界面上导航，就把焦点移到下载按钮，起到提醒作用 */
    private void focusUpdateIfIdle() {
        View cur = getCurrentFocus();
        if (cur == null || cur == updateBtn) updateBtn.requestFocus();
    }

    // ---------------- OTA SP 读写辅助 ----------------

    private SharedPreferences sp() {
        return getSharedPreferences(SP_NAME, MODE_PRIVATE);
    }

    private void setLastCheckNow() {
        sp().edit().putLong(SP_LAST_CHECK, System.currentTimeMillis()).apply();
    }

    private boolean shouldTickCheck() {
        long last = sp().getLong(SP_LAST_CHECK, 0);
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS;
    }

    private void savePendingTag(String tag) {
        sp().edit().putString(SP_PENDING_TAG, tag).apply();
    }

    private void clearPendingTag() {
        sp().edit().remove(SP_PENDING_TAG).apply();
    }

    private void tick() {
        String code = ServerService.getPairCode(this);
        codeView.setText(code);
        String ip = localIp();
        ipView.setText(getString(R.string.ui_local_addr, ip,
                ServerService.PORT, ServerService.DISCOVERY_PORT,
                ServerService.WEB_HTTP_PORT, ServerService.WEB_HTTPS_PORT));
        updateQr(ip, code);
        statusView.setText(getString(R.string.ui_running));
        boolean a11y = TvRemoteAccessibilityService.isReady();
        a11yView.setText(getString(a11y ? R.string.ui_a11y_ok : R.string.ui_a11y_missing));
        setRowColor(a11yView, a11y ? GREEN : RED);
        // SPEC-039：注入模式与 ADB 连接状态；ADB 模式下未连通时在后台重试
        boolean adbMode = AdbShell.enabled(this);
        updateInjectRows(adbMode);
        // 未开启时才显示行尾「去开启」，并根据显隐重建遥控焦点链
        a11yBtn.setVisibility(a11y ? View.GONE : View.VISIBLE);
        adbBtn.setVisibility(AdbShell.isConnected() ? View.GONE : View.VISIBLE);
        updateFocusChain();
        if (adbMode && !AdbShell.isConnected() && !probing) probeAdb();
        updateGuardRow();
        // SPEC-027：盒子长时间前台驻留也会 24h 重新探测一次 OTA
        if (shouldTickCheck() && pendingUpdate == null) autoCheckUpdate();
        h.postDelayed(this::tick, 3000);
    }

    /** 显示异常自愈守护的状态：正常/自愈中/需人工处理 */
    private void updateGuardRow() {
        String size = DisplayGuard.stateSize();
        switch (DisplayGuard.state()) {
            case DisplayGuard.ST_NORMAL:
                guardView.setText(getString(R.string.ui_guard_ok, size));
                guardView.setTextColor(GREEN);
                break;
            case DisplayGuard.ST_HEALING:
                guardView.setText(getString(R.string.ui_guard_healing, size));
                guardView.setTextColor(ACCENT);
                break;
            case DisplayGuard.ST_MANUAL:
                guardView.setText(getString(R.string.ui_guard_manual, size));
                guardView.setTextColor(RED);
                break;
            default:
                guardView.setText(R.string.ui_guard_wait);
                guardView.setTextColor(SUB);
                break;
        }
    }

    /**
     * SPEC-024：按当前 IP 与配对码决定二维码显示；两者都未变化时不重建位图
     * （tick 每 3 秒一次）。二维码带 #pair=<配对码>，扫码后网页自动配对。
     */
    private void updateQr(String ip, String code) {
        if (ip.equals(getString(R.string.ui_no_network))) {
            qrView.setVisibility(View.GONE);
            qrTip.setVisibility(View.GONE);
            homeTip.setVisibility(View.GONE);
            qrKey = null;
            return;
        }
        qrView.setVisibility(View.VISIBLE);
        qrTip.setVisibility(View.VISIBLE);
        homeTip.setVisibility(View.VISIBLE);
        String key = ip + "\n" + code;
        if (key.equals(qrKey)) return;
        qrKey = key;
        qrView.setImageBitmap(qrBitmap("http://" + ip + ":" + ServerService.WEB_HTTP_PORT
                + "/#pair=" + code));
    }

    /** 生成白底黑块二维码位图：四周 4 模块静区，按整数倍缩放保证边缘清晰 */
    private Bitmap qrBitmap(String text) {
        boolean[][] mod = QrEncoder.encode(text);
        int quiet = 4;
        int n = mod.length + quiet * 2;
        // SPEC-033：宽屏 220dp（右栏宽约 360dp，含白底留白仍能放下），
        // 并限制不超过屏幕宽/高的五分之二，保证低分辨率电视与手机竖屏下也能完整显示
        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        int target = Math.min(dp(wide ? 220 : 240),
                getResources().getDisplayMetrics().heightPixels * 2 / 5);
        target = Math.min(target, getResources().getDisplayMetrics().widthPixels * 2 / 5);
        int scale = Math.max(1, target / n);
        Bitmap bmp = Bitmap.createBitmap(n * scale, n * scale, Bitmap.Config.ARGB_8888);
        // createBitmap 默认按 160dpi 标记，高密度屏上会被 ImageView 按比例缩小；
        // 改为与屏幕同密度，二维码按算出的像素尺寸 1:1 显示
        bmp.setDensity(getResources().getDisplayMetrics().densityDpi);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFFFFFFFF);
        Paint p = new Paint();
        p.setColor(0xFF000000);
        for (int y = 0; y < mod.length; y++) {
            for (int x = 0; x < mod.length; x++) {
                if (!mod[y][x]) continue;
                int left = (x + quiet) * scale;
                int top = (y + quiet) * scale;
                c.drawRect(left, top, left + scale, top + scale, p);
            }
        }
        return bmp;
    }

    /**
     * SPEC-014：跳到系统无障碍设置，用户在那里开启本应用的服务。
     * 本固件同时装有 com.android.settings 与 com.android.tv.settings 两个处理者，
     * 不指定包名会先弹「选择要使用的应用」；TV 版页面为空壳，故固定走 com.android.settings
     * （其无障碍页首个条目即本应用，遥控按一次 OK 就进入开关页）。
     */
    private void openA11ySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.setPackage("com.android.settings");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private String localIp() {
        try {
            List<NetworkInterface> ifs = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : ifs) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (ip != null && !ip.startsWith("127.")) return ip;
                    }
                }
            }
        } catch (Exception ignored) {}
        return getString(R.string.ui_no_network);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
    }
}
