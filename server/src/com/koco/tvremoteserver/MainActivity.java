package com.koco.tvremoteserver;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * 盒子端界面：显示本机地址、端口、配对码和服务状态。
 * 服务在 onCreate 时自动启动，之后开机也会自启。
 */
public class MainActivity extends Activity {

    private TextView statusView, ipView, codeView, rootView;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        ServerService.start(this);
        checkRoot();
        tick();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private ViewGroup buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0F14);
        root.setPadding(dp(32), dp(24), dp(32), dp(24));

        TextView title = new TextView(this);
        title.setText("TV 遥控服务");
        title.setTextColor(0xFFE6EDF3);
        title.setTextSize(26);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setTextColor(0xFF3FB950);
        statusView.setPadding(0, dp(8), 0, dp(4));
        root.addView(statusView);

        rootView = new TextView(this);
        rootView.setTextSize(15);
        rootView.setTextColor(0xFF7D8590);
        rootView.setPadding(0, 0, 0, dp(20));
        root.addView(rootView);

        TextView lbl = new TextView(this);
        lbl.setText("手机 App 配对码");
        lbl.setTextSize(16);
        lbl.setTextColor(0xFF7D8590);
        root.addView(lbl);

        codeView = new TextView(this);
        codeView.setTextSize(64);
        codeView.setTextColor(0xFF2563EB);
        codeView.setLetterSpacing(0.2f);
        codeView.setPadding(0, dp(4), 0, dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF111820);
        g.setCornerRadius(dp(16));
        codeView.setBackground(g);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(0, dp(16), 0, dp(16));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT);
        codeView.setLayoutParams(clp);
        root.addView(codeView);

        ipView = new TextView(this);
        ipView.setTextSize(15);
        ipView.setTextColor(0xFF7D8590);
        ipView.setPadding(0, dp(16), 0, 0);
        root.addView(ipView);

        TextView tip = new TextView(this);
        tip.setText("在手机 App 里选择「局域网直连」，输入上面 6 位配对码即可。\n"
                + "之后每次开机服务都会自动启动，无需任何设置。");
        tip.setTextSize(13);
        tip.setTextColor(0xFF5A6472);
        tip.setPadding(0, dp(20), 0, 0);
        tip.setLineSpacing(dp(4), 1f);
        root.addView(tip);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0B0F14);
        sv.addView(root);
        return sv;
    }

    private void tick() {
        codeView.setText(ServerService.getPairCode(this));
        ipView.setText("本机地址：" + localIp() + "   端口：" + ServerService.PORT
                + "\n发现端口：" + ServerService.DISCOVERY_PORT);
        statusView.setText("● 服务运行中");
        h.postDelayed(this::tick, 3000);
    }

    private void checkRoot() {
        new Thread(() -> {
            String out = "";
            try {
                Process p = new ProcessBuilder("/system/xbin/su", "root", "sh", "-c", "id")
                        .redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String l;
                    while ((l = r.readLine()) != null) out += l;
                }
                p.waitFor();
            } catch (Exception ignored) {}
            final boolean ok = out.contains("uid=0");
            runOnUiThread(() -> {
                rootView.setText(ok ? "root 权限：已获得（可注入按键）"
                        : "root 权限：未获得 —— 按键会失败，请检查设备是否已 root");
                rootView.setTextColor(ok ? 0xFF3FB950 : 0xFFF85149);
            });
        }).start();
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
        return "未连接网络";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
    }
}
