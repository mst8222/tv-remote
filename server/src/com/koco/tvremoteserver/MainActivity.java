package com.koco.tvremoteserver;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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

    private TextView statusView, ipView, codeView, a11yView;
    private GradientDrawable dotBg;
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(48), dp(36), dp(48), dp(36));

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
        addCard(root, statusCard, dp(16));

        // 配对码卡：11sp 分组标题 + 大号蓝字
        LinearLayout pairCard = card(R.string.ui_grp_pair);
        codeView = new TextView(this);
        codeView.setTextSize(64);
        codeView.setTextColor(ACCENT);
        codeView.setLetterSpacing(0.25f);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(0, dp(16), 0, dp(20));
        pairCard.addView(codeView, lpRow(0));
        addCard(root, pairCard, dp(16));

        // 连接地址卡
        LinearLayout addrCard = card(R.string.ui_grp_addr);
        ipView = new TextView(this);
        ipView.setTextSize(15);
        ipView.setTextColor(TXT);
        ipView.setLineSpacing(dp(4), 1f);
        ipView.setPadding(0, dp(4), 0, 0);
        addrCard.addView(ipView, lpRow(0));
        addCard(root, addrCard, dp(20));

        TextView tip = new TextView(this);
        tip.setText(getString(R.string.ui_tip));
        tip.setTextSize(13);
        tip.setTextColor(DIMTXT);
        tip.setLineSpacing(dp(4), 1f);
        root.addView(tip);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        return sv;
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

    private void tick() {
        codeView.setText(ServerService.getPairCode(this));
        ipView.setText(getString(R.string.ui_local_addr, localIp(),
                ServerService.PORT, ServerService.DISCOVERY_PORT));
        statusView.setText(getString(R.string.ui_running));
        boolean a11y = TvRemoteAccessibilityService.isReady();
        a11yView.setText(getString(a11y ? R.string.ui_a11y_ok : R.string.ui_a11y_missing));
        a11yView.setTextColor(a11y ? GREEN : RED);
        h.postDelayed(this::tick, 3000);
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
