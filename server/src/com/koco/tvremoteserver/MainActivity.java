package com.koco.tvremoteserver;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
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
    static final int BG = 0xFF0A0E14, SURFACE = 0xFF141A24, ACCENT = 0xFF4F7CFF,
            TXT = 0xFFEAEFF7, SUB = 0xFF8A94A6, DIMTXT = 0xFF5A6472,
            GREEN = 0xFF3DDC84, RED = 0xFFFF5A5A;

    private TextView statusView, ipView, codeView, a11yView, updateView, updateBtn;
    /** SPEC-024：网页遥控二维码与其提示；qrKey 记录当前二维码对应的 IP + 配对码 */
    private ImageView qrView;
    private TextView qrTip;
    private String qrKey;
    /** SPEC-033：宽屏双栏只做一次高度对齐，避免每次布局都改参数 */
    private boolean fitted;
    private GradientDrawable dotBg;
    /** 检查到的待安装 Release；为空表示当前没有可下载的新版本 */
    private Updater.Release pendingUpdate;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        ServerService.start(this);
        tick();
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
        // 盒子界面不响应触摸，同时设为可聚焦，便于物理遥控导航后按 OK
        a11yView.setClickable(true);
        a11yView.setFocusable(true);
        a11yView.setOnClickListener(v -> openA11ySettings());
        statusCard.addView(a11yView);

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
        updateBtn.setText(R.string.ui_update_check);
        updateBtn.setTextSize(15);
        updateBtn.setTextColor(TXT);
        updateBtn.setGravity(Gravity.CENTER);
        updateBtn.setPadding(dp(22), dp(10), dp(22), dp(10));
        updateBtn.setBackground(roundButton(false));
        updateBtn.setClickable(true);
        updateBtn.setFocusable(true);
        updateBtn.setOnClickListener(v -> onUpdateAction());
        // 实测 H96 Android 12：可聚焦 TextView 不响应 OK/ENTER 的默认点击，
        // 遥控器按 OK 时由这里显式触发点击（实体遥控与按键注入都走这条路径）
        updateBtn.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP
                    && (keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
                v.performClick();
                return true;
            }
            return false;
        });
        updateBtn.setOnFocusChangeListener((v, focused) -> {
            updateBtn.setBackground(roundButton(focused));
            updateBtn.setTextColor(focused ? 0xFFFFFFFF : TXT);
        });
        updateCard.addView(updateBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

    // ---------------- OTA 更新（SPEC-017） ----------------

    /** 按钮语义：有待安装版本则下载安装，否则重新检查 */
    private void onUpdateAction() {
        if (pendingUpdate != null) {
            downloadUpdate(pendingUpdate);
        } else {
            checkUpdate();
        }
    }

    private void checkUpdate() {
        pendingUpdate = null;
        updateView.setText(R.string.ui_update_checking);
        updateBtn.setText(R.string.ui_update_check);
        setUpdateEnabled(false);
        new Thread(() -> {
            try {
                final Updater.Release r = Updater.fetchLatest(this);
                final String cur = Updater.currentVersion(this);
                final boolean newer = Updater.isNewer(r.tag, cur);
                runOnUiThread(() -> {
                    pendingUpdate = newer ? r : null;
                    if (newer) {
                        updateView.setText(getString(R.string.ui_update_found,
                                Updater.displayVersion(r.tag), cur));
                        updateBtn.setText(R.string.ui_update_download);
                    } else {
                        updateView.setText(getString(R.string.ui_update_latest, cur));
                        updateBtn.setText(R.string.ui_update_check);
                    }
                    setUpdateEnabled(true);
                });
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "" : e.getMessage();
                runOnUiThread(() -> {
                    updateView.setText(getString(R.string.ui_update_failed, msg));
                    updateBtn.setText(R.string.ui_update_check);
                    setUpdateEnabled(true);
                });
            }
        }).start();
    }

    private void downloadUpdate(final Updater.Release r) {
        updateView.setText(R.string.ui_update_downloading);
        setUpdateEnabled(false);
        new Thread(() -> {
            try {
                final File apk = Updater.download(this, r);
                runOnUiThread(() -> {
                    pendingUpdate = null;
                    updateBtn.setText(R.string.ui_update_check);
                    setUpdateEnabled(true);
                    boolean submitted = Updater.install(this, apk);
                    updateView.setText(submitted
                            ? R.string.ui_update_ready : R.string.update_perm_required);
                });
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "" : e.getMessage();
                runOnUiThread(() -> {
                    updateView.setText(getString(R.string.ui_update_failed, msg));
                    updateBtn.setText(R.string.ui_update_check);
                    setUpdateEnabled(true);
                });
            }
        }).start();
    }

    private void setUpdateEnabled(boolean on) {
        updateBtn.setEnabled(on);
        updateBtn.setAlpha(on ? 1f : 0.45f);
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
        a11yView.setTextColor(a11y ? GREEN : RED);
        h.postDelayed(this::tick, 3000);
    }

    /**
     * SPEC-024：按当前 IP 与配对码决定二维码显示；两者都未变化时不重建位图
     * （tick 每 3 秒一次）。二维码带 #pair=<配对码>，扫码后网页自动配对。
     */
    private void updateQr(String ip, String code) {
        if (ip.equals(getString(R.string.ui_no_network))) {
            qrView.setVisibility(View.GONE);
            qrTip.setVisibility(View.GONE);
            qrKey = null;
            return;
        }
        qrView.setVisibility(View.VISIBLE);
        qrTip.setVisibility(View.VISIBLE);
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

    /** SPEC-014：跳到系统无障碍设置，用户在那里开启本应用的服务 */
    private void openA11ySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
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
