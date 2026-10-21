package com.koco.tvremoteserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
 * 盒子端的遥控服务：监听 TCP 8899，收到命令后用 su 注入按键。
 * 不依赖开发者选项 / 无线调试，开机自启。
 *
 * 协议（每行一条，\n 结尾）：
 *   PAIR <6位配对码>          -> OK <token>
 *   KEY <token> <keycode>     -> OK
 *   TEXT <token> <base64>     -> OK
 *   APP <token> <包名>         -> OK
 *   PING                      -> PONG
 */
public class ServerService extends Service {

    private static final String TAG = "TvRemoteServer";
    public static final int PORT = 8899;
    public static final int DISCOVERY_PORT = 8898;
    private static final String CH_ID = "tvremote";
    private static final String SU = "/system/xbin/su";

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
        startForeground(1, buildNotification("遥控服务运行中"));
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
                case "KEY":
                    if (p.length < 3) return "ERR arg";
                    return keyevent(p[2]) ? "OK" : "ERR exec";
                case "TEXT": {
                    if (p.length < 3) return "ERR arg";
                    String txt = new String(Base64.decode(p[2], Base64.NO_WRAP), "UTF-8");
                    return text(txt) ? "OK" : "ERR exec";
                }
                case "APP":
                    if (p.length < 3) return "ERR arg";
                    return launch(p[2]) ? "OK" : "ERR exec";
                default:
                    return "ERR cmd";
            }
        } catch (Exception e) {
            return "ERR " + e.getClass().getSimpleName();
        }
    }

    // ---------------- 命令执行（su） ----------------

    private boolean exec(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) { /* 丢弃输出 */ }
            }
            return p.waitFor() == 0;
        } catch (Exception e) {
            Log.e(TAG, "exec fail", e);
            return false;
        }
    }

    /** 注意：本机 su 是 toybox 版，语法为 su [WHO [COMMAND...]]，不能�� su -c；
     *  monkey 是 shell 脚本，必须经 sh -c 才能执行 */
    private boolean keyevent(String code) {
        return exec(new String[]{SU, "root", "sh", "-c", "input keyevent " + code});
    }

    private boolean text(String t) {
        String safe = t.replace(" ", "%s").replace("'", "");
        return exec(new String[]{SU, "root", "sh", "-c", "input text '" + safe + "'"});
    }

    private boolean launch(String pkg) {
        String cmd;
        if (pkg.equals("com.android.settings")) {
            // 该固件的 Settings 没有 LAUNCHER 入口，只能用 action 拉起
            cmd = "am start -a android.settings.SETTINGS";
        } else {
            cmd = "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1";
        }
        return exec(new String[]{SU, "root", "sh", "-c", cmd});
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
            NotificationChannel ch = new NotificationChannel(CH_ID, "遥控服务",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("TV 遥控服务")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
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
