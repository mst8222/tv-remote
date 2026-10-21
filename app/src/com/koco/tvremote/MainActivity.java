package com.koco.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final int BG = 0xFF0B0F14, BTN = 0xFF182029, ACCENT = 0xFF2563EB,
            TXT = 0xFFE6EDF3, MUTED = 0xFF7D8590, DIM = 0xFF111820;

    private AdbClient adb;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView devName;
    private SharedPreferences sp;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private NsdManager.DiscoveryListener discovery2;
    private BtHid bt;
    private boolean btMode = false;
    private Button modeBtn;
    private LanClient lan;
    private boolean lanMode = false;
    private String lanHost = "";
    private String lanToken = "";

    /** 设备表，每项 {名称, IP, 端口} —— 可扫描添加，持久化保存 */
    private final List<String[]> devices = new ArrayList<>();
    private int cur = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("tvremote", MODE_PRIVATE);
        loadDevices();
        lanHost = sp.getString("lanHost", "");
        lanToken = sp.getString("lanToken", "");
        try {
            adb = new AdbClient(KeyConst.PRIV);
        } catch (Exception e) {
            Toast.makeText(this, "密钥初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        setContentView(buildUi());
        connectAsync();
    }

    private String curHost() { return devices.get(cur)[1]; }

    private int curPort() { return Integer.parseInt(devices.get(cur)[2]); }

    // ---------------- 设备持久化 ----------------

    private void loadDevices() {
        devices.clear();
        String raw = sp.getString("devs", "");
        if (!raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                String[] p = line.split("\\|");
                if (p.length >= 3 && !p[0].isEmpty()) devices.add(new String[]{p[0], p[1], p[2]});
            }
        }
        if (devices.isEmpty()) {
            devices.add(new String[]{"荐片TV · RK3566", "192.168.3.125", "5555"});
            devices.add(new String[]{"三星 Fold3 · 安卓13", "192.168.3.38", "5555"});
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
            sb.append(d[0]).append('|').append(d[1]).append('|').append(d[2]).append('\n');
        }
        sp.edit().putString("devs", sb.toString().trim())
                .putString("cur", devices.isEmpty() ? "" : devices.get(cur)[0]).apply();
    }

    // ---------------- UI ----------------

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(round(DIM, 10, 0));
        bar.setPadding(dp(12), dp(9), dp(6), dp(9));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        devName = new TextView(this);
        devName.setText(devices.get(cur)[0] + "  ▾");
        devName.setTextColor(TXT);
        devName.setTextSize(13);
        info.addView(devName);

        status = new TextView(this);
        status.setText("正在连接 " + curHost() + " …");
        status.setTextColor(MUTED);
        status.setTextSize(11);
        info.addView(status);

        info.setOnClickListener(v -> showDevicePicker());
        bar.addView(info);

        modeBtn = flat("ADB", MUTED);
        modeBtn.setOnClickListener(v -> toggleMode());
        bar.addView(modeBtn);

        Button lanBtn = flat("直连", MUTED);
        lanBtn.setOnClickListener(v -> showLanDialog());
        lanBtn.setOnLongClickListener(v -> {
            // 长按 = 清除配对信息，重新配对（盒子 IP 变了时用）
            lanHost = "";
            lanToken = "";
            lan = null;
            lanMode = false;
            sp.edit().remove("lanHost").remove("lanToken").apply();
            Toast.makeText(this, "已清除配对，请重新配对", Toast.LENGTH_SHORT).show();
            showLanDialog();
            return true;
        });
        bar.addView(lanBtn);

        Button scan = flat("扫描", MUTED);
        scan.setOnClickListener(v -> startScan());
        bar.addView(scan);

        Button add = flat("添加", MUTED);
        add.setOnClickListener(v -> showAddDialog());
        bar.addView(add);

        root.addView(bar, lp(-1, -2, 0, 0, 0, dp(12)));

        LinearLayout dpad = new LinearLayout(this);
        dpad.setOrientation(LinearLayout.VERTICAL);
        dpad.setGravity(Gravity.CENTER_HORIZONTAL);
        addDpadRow(dpad, false, 21, 19);
        addDpadRow(dpad, true, 21, 23);
        addDpadRow(dpad, false, 21, 20);
        root.addView(dpad, lp(-1, -2, 0, 0, 0, dp(10)));

        LinearLayout r1 = row();
        r1.addView(key("返回", 4), w(1));
        r1.addView(key("主页", 3), w(1));
        r1.addView(key("菜单", 82), w(1));
        root.addView(r1, lp(-1, -2, 0, 0, 0, dp(8)));

        LinearLayout r2 = row();
        r2.addView(key("音量+", 24), w(1));
        r2.addView(key("音量−", 25), w(1));
        r2.addView(key("静音", 164), w(1));
        r2.addView(key("电源", 26), w(1));
        root.addView(r2, lp(-1, -2, 0, 0, 0, dp(10)));

        LinearLayout r3 = new LinearLayout(this);
        r3.setOrientation(LinearLayout.HORIZONTAL);
        EditText et = new EditText(this);
        et.setHint("输入文字（搜索/账号）");
        et.setHintTextColor(0xFF5A6472);
        et.setTextColor(TXT);
        et.setTextSize(15);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setBackground(round(DIM, 12, 0x1F2A35));
        et.setPadding(dp(12), 0, dp(12), 0);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        r3.addView(et);

        Button send = new Button(this);
        send.setText("发送");
        send.setTextColor(Color.WHITE);
        send.setTextSize(15);
        send.setAllCaps(false);
        send.setBackground(round(ACCENT, 12, 0));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, dp(48));
        slp.leftMargin = dp(8);
        send.setLayoutParams(slp);
        send.setOnClickListener(v -> {
            String t = et.getText().toString().trim();
            if (t.isEmpty()) return;
            doText(t);
            et.setText("");
        });
        r3.addView(send);
        root.addView(r3, lp(-1, -2, 0, 0, 0, dp(12)));

        LinearLayout r4 = row();
        r4.addView(app("设置", "com.android.settings"), w(1));
        r4.addView(app("OK影视", "com.fongmi.android.tv"), w(1));
        r4.addView(app("飞牛TV", "com.trim.tv"), w(1));
        root.addView(r4, lp(-1, -2, 0, 0, 0, dp(8)));

        LinearLayout r5 = row();
        r5.addView(app("当贝桌面", "com.dangbei.tvlauncher"), w(1));
        r5.addView(app("当贝市场", "com.dangbeimarket"), w(1));
        r5.addView(app("Kodi", "org.xbmc.kodi19DV"), w(1));
        root.addView(r5, lp(-1, -2, 0, 0, 0, 0));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        return sv;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams w(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), weight);
        p.rightMargin = dp(8);
        return p;
    }

    private LinearLayout.LayoutParams lp(int ww, int hh, int l, int t, int r, int bb) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ww, hh);
        p.leftMargin = l; p.topMargin = t; p.rightMargin = r; p.bottomMargin = bb;
        return p;
    }

    private void addDpadRow(LinearLayout parent, boolean center, int leftCode, int midCode) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_HORIZONTAL);

        if (center) {
            r.addView(navBtn("◀", leftCode), sq());
            r.addView(okBtn(), sq());
            r.addView(navBtn("▶", 22), sq());
        } else {
            r.addView(new View(this), sq());
            r.addView(navBtn(midCode == 19 ? "▲" : "▼", midCode), sq());
            r.addView(new View(this), sq());
        }
        parent.addView(r);
    }

    private LinearLayout.LayoutParams sq() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(78), dp(78));
        p.setMargins(dp(5), dp(5), dp(5), dp(5));
        return p;
    }

    private View navBtn(String label, int code) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(TXT);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(BTN, 18, 0x263140));
        t.setOnClickListener(v -> press(code));
        return t;
    }

    private View okBtn() {
        TextView t = new TextView(this);
        t.setText("OK");
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(ACCENT, 40, 0));
        t.setOnClickListener(v -> press(23));
        return t;
    }

    private Button key(String label, int code) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(TXT);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setBackground(round(BTN, 14, 0x263140));
        b.setOnClickListener(v -> press(code));
        return b;
    }

    private Button app(String label, String pkg) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(TXT);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackground(round(BTN, 14, 0x263140));
        b.setOnClickListener(v -> doApp(pkg));
        return b;
    }

    private Button flat(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(color);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private GradientDrawable round(int fill, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(1, stroke);
        return g;
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
        shell("input keyevent " + code);
    }

    /** ADB keycode → 蓝牙 HID usage 映射 */
    private void btPress(int code) {
        if (bt == null || !bt.isReady()) {
            setStatus("蓝牙未就绪，点顶部「蓝牙」重连", false);
            return;
        }
        switch (code) {
            case 19: bt.sendKey(BtHid.K_UP); break;
            case 20: bt.sendKey(BtHid.K_DOWN); break;
            case 21: bt.sendKey(BtHid.K_LEFT); break;
            case 22: bt.sendKey(BtHid.K_RIGHT); break;
            case 23: bt.sendKey(BtHid.K_ENTER); break;
            case 4: bt.sendKey(BtHid.K_ESC); break;
            case 3: bt.sendKey(BtHid.K_HOME); break;
            case 82: bt.sendKey(0x65); break;
            case 24: bt.sendConsumer(BtHid.C_VOL_UP); break;
            case 25: bt.sendConsumer(BtHid.C_VOL_DOWN); break;
            case 164: bt.sendConsumer(BtHid.C_MUTE); break;
            case 26: bt.sendConsumer(0x30); break;
            default: break;
        }
    }

    private void shell(final String cmd) {
        worker.submit(() -> {
            try {
                if (!adb.isConnected()) adb.connect(curHost(), curPort(), 4000);
                adb.exec(cmd, 6000);
                setStatus("已连接 " + curHost(), true);
            } catch (Exception e) {
                setStatus("未连接：" + shortMsg(e), false);
            }
        });
    }

    private void connectAsync() {
        worker.submit(() -> {
            try {
                adb.connect(curHost(), curPort(), 5000);
                String model = adb.exec("getprop ro.product.model", 4000).trim();
                setStatus("已连接 · " + model, true);
            } catch (Exception e) {
                setStatus("连接失败：" + shortMsg(e), false);
            }
        });
    }

    private String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        return m.length() > 26 ? m.substring(0, 26) : m;
    }

    private void setStatus(final String s, final boolean ok) {
        runOnUiThread(() -> {
            status.setText(s);
            status.setTextColor(ok ? 0xFF3FB950 : 0xFFF85149);
        });
    }

    // ---------------- 设备管理 ----------------

    private void showDevicePicker() {
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            String[] d = devices.get(i);
            names[i] = (i == cur ? "● " : "○ ") + d[0] + "   (" + d[1] + ":" + d[2] + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("选择设备（长按列表项可删除）")
                .setItems(names, (d, which) -> switchTo(which))
                .setNeutralButton("删除当前", (d, w) -> removeCurrent())
                .setNegativeButton("取消", null)
                .show();
    }

    private void switchTo(int idx) {
        cur = idx;
        devName.setText(devices.get(cur)[0] + "  ▾");
        setStatus("正在切换…", true);
        saveDevices();
        adb.close();
        connectAsync();
    }

    private void removeCurrent() {
        if (devices.size() <= 1) {
            Toast.makeText(this, "至少保留一个设备", Toast.LENGTH_SHORT).show();
            return;
        }
        devices.remove(cur);
        cur = 0;
        devName.setText(devices.get(cur)[0] + "  ▾");
        saveDevices();
        adb.close();
        connectAsync();
    }

    private void showAddDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        EditText name = new EditText(this);
        name.setHint("设备名称（随便起）");
        name.setTextColor(TXT);
        box.addView(name);

        EditText addr = new EditText(this);
        addr.setHint("IP:端口，例如 192.168.3.125:5555");
        addr.setTextColor(TXT);
        addr.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(addr);

        new AlertDialog.Builder(this)
                .setTitle("手动添加设备")
                .setView(box)
                .setPositiveButton("添加并连接", (d, w) -> {
                    String a = addr.getText().toString().trim();
                    if (a.isEmpty()) return;
                    String[] parts = a.split(":");
                    String h = parts[0];
                    int p = 5555;
                    if (parts.length > 1) {
                        try { p = Integer.parseInt(parts[1]); } catch (Exception ignored) {}
                    }
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) n = h;
                    addAndConnect(n, h, p);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void addAndConnect(String name, String ip, int port) {
        for (int i = 0; i < devices.size(); i++) {
            String[] d = devices.get(i);
            if (d[1].equals(ip) && d[2].equals(String.valueOf(port))) {
                switchTo(i);
                return;
            }
        }
        devices.add(new String[]{name, ip, String.valueOf(port)});
        cur = devices.size() - 1;
        devName.setText(devices.get(cur)[0] + "  ▾");
        saveDevices();
        adb.close();
        connectAsync();
    }

    // ---------------- 局域网直连（盒子端 App，不需开发者选项） ----------------

    private void showLanDialog() {
        // 已经配对过：直接进入直连模式，不再要求输入配对码
        if (!lanHost.isEmpty() && !lanToken.isEmpty()) {
            lan = new LanClient(lanHost, LanClient.DEFAULT_PORT, lanToken);
            lanMode = true;
            btMode = false;
            modeBtn.setText("直连");
            setStatus("直连 · " + lanHost + "（已配对）", true);
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText ip = new EditText(this);
        ip.setHint("盒子 IP，例如 192.168.3.125");
        ip.setText(lanHost.isEmpty() ? "192.168.3.125" : lanHost);
        ip.setTextColor(TXT);
        box.addView(ip);

        final EditText code = new EditText(this);
        code.setHint("6 位配对码（电视屏幕上显示的）");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setTextColor(TXT);
        box.addView(code);

        new AlertDialog.Builder(this)
                .setTitle("局域网直连")
                .setMessage("盒子端装好「TV遥控服务」后，电视上会显示 6 位配对码。\n"
                        + "配对一次长期有效，全程不需要开发者选项。")
                .setView(box)
                .setPositiveButton("配对并连接", (d, w) ->
                        doPair(ip.getText().toString().trim(), code.getText().toString().trim()))
                .setNeutralButton("自动发现", (d, w) -> doDiscover())
                .setNegativeButton("取消", null)
                .show();
    }

    private void doPair(final String h, final String c) {
        if (h.isEmpty() || c.isEmpty()) {
            setStatus("IP 和配对码都要填", false);
            return;
        }
        setStatus("正在配对 " + h + " …", true);
        worker.submit(() -> {
            try {
                String tok = LanClient.pair(h, LanClient.DEFAULT_PORT, c, 5000);
                lanHost = h;
                lanToken = tok;
                lan = new LanClient(h, LanClient.DEFAULT_PORT, tok);
                sp.edit().putString("lanHost", h).putString("lanToken", tok).apply();
                lanMode = true;
                btMode = false;
                runOnUiThread(() -> {
                    modeBtn.setText("直连");
                    setStatus("已配对 · " + h, true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("配对失败：" + shortMsg(e), false));
            }
        });
    }

    private void doDiscover() {
        setStatus("正在监听盒子广播…", true);
        worker.submit(() -> {
            final String ip = LanClient.discover(4500);
            runOnUiThread(() -> {
                if (ip != null) {
                    lanHost = ip;
                    setStatus("发现盒子 " + ip + "，请填配对码", true);
                    showLanDialog();
                } else {
                    setStatus("没发现盒子，确认盒子端服务在运行", false);
                }
            });
        });
    }

    private void lanPress(final int code) {
        if (lan == null) {
            setStatus("还没配对，点顶部「直连」", false);
            return;
        }
        worker.submit(() -> {
            try {
                lan.key(code);
                setStatus("直连 · " + lanHost, true);
            } catch (Exception e) {
                setStatus("直连失败：" + shortMsg(e), false);
            }
        });
    }

    private void doText(final String t) {
        if (lanMode && lan != null) {
            worker.submit(() -> {
                try { lan.text(t); } catch (Exception e) { setStatus("直连失败：" + shortMsg(e), false); }
            });
        } else {
            shell("input text \"" + t.replace(" ", "%s").replace("\"", "") + "\"");
        }
    }

    private void doApp(final String pkg) {
        if (lanMode && lan != null) {
            worker.submit(() -> {
                try { lan.launch(pkg); } catch (Exception e) { setStatus("直连失败：" + shortMsg(e), false); }
            });
        } else {
            shell("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
        }
    }

    // ---------------- 蓝牙模式 ----------------

    private void toggleMode() {
        if (!btMode) {
            startBt();
        } else {
            btMode = false;
            modeBtn.setText("ADB");
            setStatus("已切换回 ADB 模式", true);
            connectAsync();
        }
    }

    private void startBt() {
        setStatus("正在初始化蓝牙 HID…", true);
        bt = new BtHid(this);
        bt.start(new BtHid.StateListener() {
            @Override
            public void onSupport(final boolean ok, final String reason) {
                runOnUiThread(() -> {
                    if (!ok) {
                        bt = null;
                        setStatus("蓝牙不可用：" + reason, false);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("这台手机不能做蓝牙遥控器")
                                .setMessage(reason + "\n\n两种选择：\n"
                                        + "· 继续用 ADB 模式（需要开着无线调试）\n"
                                        + "· 买个 2.4G 飞鼠遥控器（30 元左右，插上即用）")
                                .setPositiveButton("知道了", null)
                                .show();
                        return;
                    }
                    btMode = true;
                    modeBtn.setText("蓝牙");
                    showBtDevicePicker();
                });
            }

            @Override
            public void onRegistered(final boolean ok) {
                runOnUiThread(() -> {
                    setStatus(ok ? "蓝牙 HID 已注册" : "蓝牙注册失败", ok);
                    if (ok) {
                        btMode = true;
                        modeBtn.setText("蓝牙");
                        showBtDevicePicker();
                    }
                });
            }

            @Override
            public void onConnectionChanged(final BluetoothDevice d, final boolean connected) {
                runOnUiThread(() -> {
                    String n = (d != null && d.getName() != null) ? d.getName() : "设备";
                    setStatus(connected ? "蓝牙已连接 · " + n : "蓝牙已断开", connected);
                    if (!connected) btMode = false;
                });
            }
        });
    }

    private void showBtDevicePicker() {
        Set<BluetoothDevice> bonded = bt != null ? bt.bondedDevices() : null;
        if (bonded == null || bonded.isEmpty()) {
            setStatus("没有已配对的蓝牙设备", false);
            new AlertDialog.Builder(this)
                    .setTitle("还没有配对设备")
                    .setMessage("请先在系统「设置 → 蓝牙」里和盒子配对，然后再回来点「蓝牙」。")
                    .setPositiveButton("去蓝牙设置", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                        } catch (Exception ignored) {}
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        final List<BluetoothDevice> list = new ArrayList<>(bonded);
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            BluetoothDevice d = list.get(i);
            String n = d.getName();
            names[i] = (n == null ? d.getAddress() : n) + "\n" + d.getAddress();
        }
        new AlertDialog.Builder(this)
                .setTitle("选择已配对的盒子")
                .setItems(names, (dd, which) -> bt.connect(list.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- mDNS 扫描 ----------------

    /** 每个服务类型要独立的 listener，否则报 listener already in use */
    private NsdManager.DiscoveryListener makeDiscovery(final List<String[]> hits, final Set<String> seen) {
        return new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String t) {}
            public void onDiscoveryStopped(String t) {}
            public void onStopDiscoveryFailed(String t, int e) {}
            public void onServiceLost(NsdServiceInfo i) {}

            public void onStartDiscoveryFailed(String t, int err) {
                setStatus("扫描启动失败(" + err + ")", false);
            }

            public void onServiceFound(NsdServiceInfo info) {
                try {
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        public void onResolveFailed(NsdServiceInfo i, int e) {}
                        public void onServiceResolved(NsdServiceInfo i) {
                            String ip = i.getHost() != null ? i.getHost().getHostAddress() : "?";
                            String key = ip + ":" + i.getPort();
                            if (seen.add(key)) {
                                hits.add(new String[]{
                                        i.getServiceName()
                                                .replace("_adb-tls-connect._tcp.", "")
                                                .replace("_adb._tcp.", ""),
                                        ip, String.valueOf(i.getPort())});
                            }
                        }
                    });
                } catch (Exception ignored) {}
            }
        };
    }

    private void startScan() {
        setStatus("正在扫描局域网…", true);
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        final Set<String> seen = new LinkedHashSet<>();
        final List<String[]> hits = new ArrayList<>();

        discovery = makeDiscovery(hits, seen);
        discovery2 = makeDiscovery(hits, seen);

        try {
            nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, discovery);
            nsd.discoverServices("_adb._tcp", NsdManager.PROTOCOL_DNS_SD, discovery2);
        } catch (Exception e) {
            setStatus("扫描失败：" + shortMsg(e), false);
            return;
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {}
            try { nsd.stopServiceDiscovery(discovery2); } catch (Exception ignored) {}
            showScanResult(hits);
        }, 9000);
    }

    private void showScanResult(final List<String[]> hits) {
        if (hits.isEmpty()) {
            setStatus("扫描完成：未发现设备", false);
            new AlertDialog.Builder(this)
                    .setTitle("没有扫描到设备")
                    .setMessage("可能原因：\n"
                            + "· 设备的「无线调试」没打开（ADB 方案必须开）\n"
                            + "· 手机和设备不在同一 WiFi\n"
                            + "· 路由器开启了 AP 隔离\n\n"
                            + "可以点右上角「添加」手动填 IP:端口。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        setStatus("扫描到 " + hits.size() + " 台设备", true);
        String[] items = new String[hits.size()];
        for (int i = 0; i < hits.size(); i++) {
            String[] h = hits.get(i);
            items[i] = h[0] + "\n" + h[1] + ":" + h[2];
        }
        new AlertDialog.Builder(this)
                .setTitle("扫描到 " + hits.size() + " 台（点击添加）")
                .setItems(items, (d, which) -> {
                    String[] h = hits.get(which);
                    addAndConnect(h[0], h[1], Integer.parseInt(h[2]));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (nsd != null && discovery != null) nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {}
        if (bt != null) bt.stop();
        if (adb != null) adb.close();
        worker.shutdownNow();
    }
}
