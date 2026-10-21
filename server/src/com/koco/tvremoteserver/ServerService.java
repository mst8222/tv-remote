package com.koco.tvremoteserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 盒子端的遥控服务：监听 TCP 8899，收到命令后经无障碍服务注入。
 * 不需要 root / 开发者选项 / 无线调试，开机自启（SPEC-014）。
 *
 * 协议（每行一条，\n 结尾）：
 *   PAIR <6位配对码>          -> OK <token>
 *   KEY <token> <keycode>     -> OK | ERR a11y | ERR cmd | ERR exec
 *   TEXT <token> <base64>     -> OK | ERR a11y | ERR exec
 *   APP <token> <包名>         -> OK | ERR exec
 *   LIST <token>              -> OK <包名|base64(应用名),逗号分隔>
 *   PING                      -> PONG
 */
public class ServerService extends Service {

    private static final String TAG = "TvRemoteServer";
    public static final int PORT = 8899;
    public static final int DISCOVERY_PORT = 8898;
    private static final String CH_ID = "tvremote";

    private ServerSocket server;
    private DatagramSocket udp;
    private volatile boolean running = true;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private SharedPreferences sp;

    @Override
    public void onCreate() {
        super.onCreate();
        sp = getSharedPreferences("srv", MODE_PRIVATE);
        ensurePairCode();
        startForeground(1, buildNotification(getString(R.string.ntf_running)));
        new Thread(this::serveTcp, "tcp-accept").start();
        new Thread(this::broadcastLoop, "udp-broadcast").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        try { if (udp != null) udp.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ---------------- 配对 ----------------

    private void ensurePairCode() {
        if (sp.getString("code", "").isEmpty()) {
            SecureRandom r = new SecureRandom();
            sp.edit().putString("code", String.format("%06d", r.nextInt(1000000)))
                    .putString("token", randomToken()).apply();
        }
    }

    public static String randomToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static String getPairCode(Context c) {
        return c.getSharedPreferences("srv", MODE_PRIVATE).getString("code", "------");
    }

    // ---------------- TCP ----------------

    private void serveTcp() {
        try {
            server = new ServerSocket(PORT);
            Log.i(TAG, "listening on " + PORT);
            while (running) {
                final Socket s = server.accept();
                pool.submit(() -> handle(s));
            }
        } catch (Exception e) {
            Log.e(TAG, "serveTcp", e);
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s;
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter out = new PrintWriter(sock.getOutputStream(), true)) {

            sock.setSoTimeout(30000);
            String line;
            while ((line = in.readLine()) != null) {
                String resp = dispatch(line.trim());
                out.println(resp);
                if (resp.startsWith("ERR bye")) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "client: " + e.getMessage());
        }
    }

    private String dispatch(String line) {
        try {
            if (line.equals("PING")) return "PONG";

            String[] p = line.split(" ", 3);
            if (p.length == 0) return "ERR empty";

            if (p[0].equals("PAIR")) {
                if (p.length < 2) return "ERR arg";
                if (p[1].equals(sp.getString("code", ""))) {
                    String tok = sp.getString("token", "");
                    if (tok.isEmpty()) {
                        tok = randomToken();
                        sp.edit().putString("token", tok).apply();
                    }
                    return "OK " + tok;
                }
                return "ERR badcode";
            }

            // 其余命令必须带 token
            if (p.length < 2 || !p[1].equals(sp.getString("token", ""))) {
                return "ERR auth";
            }

            switch (p[0]) {
                case "KEY": {
                    if (p.length < 3) return "ERR arg";
                    int code;
                    try {
                        code = Integer.parseInt(p[2].trim());
                    } catch (NumberFormatException e) {
                        return "ERR arg";
                    }
                    switch (TvRemoteAccessibilityService.key(this, code)) {
                        case TvRemoteAccessibilityService.RES_OK: return "OK";
                        case TvRemoteAccessibilityService.RES_NO_A11Y: return "ERR a11y";
                        case TvRemoteAccessibilityService.RES_UNSUPPORTED: return "ERR cmd";
                        default: return "ERR exec";
                    }
                }
                case "TEXT": {
                    if (p.length < 3) return "ERR arg";
                    String txt = new String(Base64.decode(p[2], Base64.NO_WRAP), "UTF-8");
                    switch (TvRemoteAccessibilityService.text(txt)) {
                        case TvRemoteAccessibilityService.RES_OK: return "OK";
                        case TvRemoteAccessibilityService.RES_NO_A11Y: return "ERR a11y";
                        default: return "ERR exec";
                    }
                }
                case "APP":
                    if (p.length < 3) return "ERR arg";
                    return launch(p[2]) ? "OK" : "ERR exec";
                case "LIST":
                    // SPEC-016：返回第三方应用「包名|base64(应用名)」，逗号分隔
                    return "OK " + listPackages();
                default:
                    return "ERR cmd";
            }
        } catch (Exception e) {
            return "ERR " + e.getClass().getSimpleName();
        }
    }

    // ---------------- 应用与包管理（PackageManager，SPEC-014） ----------------

    /** SPEC-016：列出第三方应用「包名|base64(应用名)」，逗号分隔；
     *  应用名用 UTF-8 + NO_WRAP Base64，避免中文/空格/逗号破坏单行协议 */
    private String listPackages() {
        PackageManager pm = getPackageManager();
        StringBuilder sb = new StringBuilder();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            String label = String.valueOf(pm.getApplicationLabel(ai));
            if (sb.length() > 0) sb.append(',');
            sb.append(ai.packageName).append('|').append(Base64.encodeToString(
                    label.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP));
        }
        return sb.toString();
    }

    /** 用 PackageManager 取 LAUNCHER 意图；Settings 特判为系统设置 action */
    private boolean launch(String pkg) {
        Intent i;
        if (pkg.equals("com.android.settings")) {
            // 该固件的 Settings 没有 LAUNCHER 入口，只能用 action 拉起
            i = new Intent(Settings.ACTION_SETTINGS);
        } else {
            i = getPackageManager().getLaunchIntentForPackage(pkg);
        }
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "launch fail: " + pkg, e);
            return false;
        }
    }

    // ---------------- UDP 广播（供手机自动发现） ----------------

    private void broadcastLoop() {
        try {
            udp = new DatagramSocket();
            udp.setBroadcast(true);
            String name = Build.MODEL == null ? "TVBox" : Build.MODEL;
            while (running) {
                String msg = "TVREMOTE|" + PORT + "|" + name;
                byte[] data = msg.getBytes("UTF-8");
                try {
                    udp.send(new DatagramPacket(data, data.length,
                            InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT));
                } catch (Exception ignored) {}
                Thread.sleep(3000);
            }
        } catch (Exception e) {
            Log.w(TAG, "broadcast: " + e.getMessage());
        }
    }

    // ---------------- 通知 ----------------

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CH_ID, getString(R.string.ntf_channel),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        return b.setContentTitle(getString(R.string.ntf_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat)
                .setOngoing(true)
                .build();
    }

    public static void start(Context c) {
        Intent i = new Intent(c, ServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }
}
