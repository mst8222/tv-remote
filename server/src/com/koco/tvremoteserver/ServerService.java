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
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * 盒子端的遥控服务：监听 TCP 8899，收到命令后经无障碍服务注入。
 * 不需要 root；默认用无障碍注入（SPEC-014），可切换 ADB 注入模式（SPEC-039，
 * 需要用户在开发者选项里打开一次调试开关）。
 * 开机自启（SPEC-010）。
 *
 * 协议（每行一条，\n 结尾）：
 *   PAIR <6位配对码>          -> OK <token>
 *   KEY <token> <keycode>     -> OK | ERR a11y | ERR adb | ERR cmd | ERR exec
 *   KEYLONG <token> <keycode> -> OK | ERR a11y | ERR adb | ERR cmd | ERR exec（SPEC-018，仅 23）
 *   TEXT <token> <base64>     -> OK | ERR a11y | ERR adb | ERR exec
 *   APP <token> <包名>         -> OK | ERR exec
 *   LIST <token>              -> OK <包名|base64(应用名),逗号分隔>
 *   ICON <token> <包名>        -> OK <base64(PNG)> | ERR arg | ERR exec（SPEC-019）
 *   MOVE <token> <dx> <dy>    -> OK | ERR arg | ERR a11y | ERR exec（SPEC-021，单轴 -127..127）
 *   MCLICK <token> <0|1>      -> OK | ERR arg | ERR a11y | ERR exec（SPEC-021）
 *   MTAP <token>              -> OK | ERR a11y | ERR exec（SPEC-023 光标处单击）
 *   MLONG <token>             -> OK | ERR a11y | ERR exec（SPEC-023 光标处长按）
 *   SCROLL <token> <steps>    -> OK | ERR arg | ERR a11y | ERR exec（SPEC-023，-5..5）
 *   MTAPN <token> <x> <y>     -> OK | ERR arg | ERR a11y | ERR adb | ERR exec（SPEC-028/039）
 *   MLONGN <token> <x> <y>    -> OK | ERR arg | ERR a11y | ERR adb | ERR exec（SPEC-028/039）
 *   MSWIPEN <token> <x1> <y1> <x2> <y2> <ms>
 *                             -> OK | ERR arg | ERR a11y | ERR adb | ERR exec（SPEC-028/039）
 *   PING                      -> PONG
 *
 * SPEC-039：盒端可切换「ADB 注入模式」，KEY/KEYLONG/TEXT/绝对触摸改由 shell 的
 * input 命令注入真实事件（等同蓝牙 HID）；ADB 不可用时返回 ERR adb。
 */
public class ServerService extends Service {

    private static final String TAG = "TvRemoteServer";
    public static final int PORT = 8899;
    public static final int DISCOVERY_PORT = 8898;
    // SPEC-020：内置 Web 遥控页端口（避开 8080/8443 等常见端口，防止被其它应用占用）
    public static final int WEB_HTTP_PORT = 17880;
    public static final int WEB_HTTPS_PORT = 17843;
    private static final String WEB_CERT = "web/web.p12";
    private static final char[] WEB_PASS = "koco123456".toCharArray();
    private static final String CH_ID = "tvremote";

    private ServerSocket server;
    private DatagramSocket udp;
    private WebServer web;
    private volatile boolean running = true;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private SharedPreferences sp;

    // SPEC-006：参数上限与配对失败锁定
    private static final int MAX_PKG_LEN = 128;
    private static final int MAX_TEXT_CHARS = 256;
    private static final int MAX_TEXT_B64 = 2048;
    private static final int PAIR_MAX_FAILS = 5;
    private static final long PAIR_LOCK_MS = 5 * 60 * 1000L;
    private final Map<String, Integer> pairFails = new HashMap<>();
    private final Map<String, Long> pairLockUntil = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        sp = getSharedPreferences("srv", MODE_PRIVATE);
        ensurePairCode();
        startForeground(1, buildNotification(getString(R.string.ntf_running)));
        new Thread(this::serveTcp, "tcp-accept").start();
        new Thread(this::broadcastLoop, "udp-broadcast").start();
        startWeb();
        // 显示异常自愈守护：跟随前台服务常驻，Activity 销毁不影响
        DisplayGuard.start(this);
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
        if (web != null) web.stop();
        DisplayGuard.stop();
        AdbShell.reset();
        TvRemoteAccessibilityService.hideAirCursor();
        super.onDestroy();
    }

    // ---------------- SPEC-020 Web 服务 ----------------

    private void startWeb() {
        try {
            WebServer.ApkUploadHandler uploadHandler = new WebServer.ApkUploadHandler() {
                @Override
                public java.io.File createTargetFile() {
                    java.io.File dir = new java.io.File(getCacheDir(), "apkpush");
                    return new java.io.File(dir, "upload.apk");
                }

                @Override
                public String onUploadComplete(java.io.File apk) {
                    if (!isApkFile(apk)) return "notapk";
                    return Updater.install(ServerService.this, apk) ? null : "install";
                }
            };
            web = new WebServer(WEB_HTTP_PORT, WEB_HTTPS_PORT, this::dispatch,
                    this::readAsset, uploadHandler, buildTls());
            web.start();
        } catch (Exception e) {
            Log.e(TAG, "web start failed", e);
        }
    }

    /** 静态资源从 APK 的 assets/web 读取 */
    private byte[] readAsset(String name) {
        AssetManager am = getAssets();
        try (InputStream in = am.open("web/" + name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 自签证书随 APK 发布；证书缺失时仅关闭 HTTPS，HTTP 照常可用 */
    private SSLServerSocketFactory buildTls() {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = getAssets().open(WEB_CERT)) {
                ks.load(in, WEB_PASS);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, WEB_PASS);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx.getServerSocketFactory();
        } catch (Exception e) {
            Log.w(TAG, "web tls unavailable: " + e.getMessage());
            return null;
        }
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

    // ---------------- SPEC-006 加固辅助 ----------------

    /** 包名只允许字母、数字、点、下划线，长度不超过 128 */
    private static boolean validPkg(String pkg) {
        return pkg.length() <= MAX_PKG_LEN && pkg.matches("[A-Za-z0-9._]+");
    }

    /** SPEC-028：解析绝对触摸的归一化坐标「x y」，均为 0..10000 的整数 */
    private static int[] normPoint(String[] p) {
        if (p.length < 3) return null;
        String[] v = p[2].trim().split("\\s+");
        if (v.length != 2) return null;
        int x;
        int y;
        try {
            x = Integer.parseInt(v[0]);
            y = Integer.parseInt(v[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (x < 0 || x > 10000 || y < 0 || y > 10000) return null;
        return new int[]{x, y};
    }

    private boolean isPairLocked(String ip) {
        synchronized (pairLockUntil) {
            Long until = pairLockUntil.get(ip);
            if (until == null) return false;
            if (System.currentTimeMillis() < until) return true;
            pairLockUntil.remove(ip);
            pairFails.remove(ip);
            return false;
        }
    }

    private void notePairFail(String ip) {
        synchronized (pairLockUntil) {
            int n = pairFails.containsKey(ip) ? pairFails.get(ip) : 0;
            n++;
            pairFails.put(ip, n);
            if (n >= PAIR_MAX_FAILS) {
                pairLockUntil.put(ip, System.currentTimeMillis() + PAIR_LOCK_MS);
            }
        }
    }

    private void clearPairFail(String ip) {
        synchronized (pairLockUntil) {
            pairFails.remove(ip);
            pairLockUntil.remove(ip);
        }
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
                String resp = dispatch(line.trim(), sock.getInetAddress().getHostAddress());
                out.println(resp);
                if (resp.startsWith("ERR bye")) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "client: " + e.getMessage());
        }
    }

    private String dispatch(String line, String peerIp) {
        try {
            if (line.equals("PING")) return "PONG";

            String[] p = line.split(" ", 3);
            if (p.length == 0) return "ERR empty";

            if (p[0].equals("PAIR")) {
                if (p.length < 2) return "ERR arg";
                if (isPairLocked(peerIp)) return "ERR locked";
                if (p[1].equals(sp.getString("code", ""))) {
                    clearPairFail(peerIp);
                    String tok = sp.getString("token", "");
                    if (tok.isEmpty()) {
                        tok = randomToken();
                        sp.edit().putString("token", tok).apply();
                    }
                    return "OK " + tok;
                }
                notePairFail(peerIp);
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
                    // SPEC-039：ADB 模式下注入真实按键事件（等同蓝牙 HID）
                    if (AdbShell.enabled(this)) return adbResult(AdbShell.key(this, code));
                    switch (TvRemoteAccessibilityService.key(this, code)) {
                        case TvRemoteAccessibilityService.RES_OK: return "OK";
                        case TvRemoteAccessibilityService.RES_NO_A11Y: return "ERR a11y";
                        case TvRemoteAccessibilityService.RES_UNSUPPORTED: return "ERR cmd";
                        default: return "ERR exec";
                    }
                }
                case "KEYLONG": {
                    // SPEC-018：长按 OK，盒端执行 ACTION_LONG_CLICK
                    if (p.length < 3) return "ERR arg";
                    int code;
                    try {
                        code = Integer.parseInt(p[2].trim());
                    } catch (NumberFormatException e) {
                        return "ERR arg";
                    }
                    if (AdbShell.enabled(this)) {
                        // --longpress 不可用的固件退回无障碍长按
                        int r = AdbShell.keyLong(this, code);
                        if (r != AdbShell.RES_FAIL) return adbResult(r);
                    }
                    switch (TvRemoteAccessibilityService.keyLong(this, code)) {
                        case TvRemoteAccessibilityService.RES_OK: return "OK";
                        case TvRemoteAccessibilityService.RES_NO_A11Y: return "ERR a11y";
                        case TvRemoteAccessibilityService.RES_UNSUPPORTED: return "ERR cmd";
                        default: return "ERR exec";
                    }
                }
                case "MOVE": {
                    // SPEC-021/023：相对位移，单轴 -127..127
                    if (p.length < 3) return "ERR arg";
                    String[] v = p[2].trim().split("\\s+");
                    if (v.length != 2) return "ERR arg";
                    int dx;
                    int dy;
                    try {
                        dx = Integer.parseInt(v[0]);
                        dy = Integer.parseInt(v[1]);
                    } catch (NumberFormatException e) {
                        return "ERR arg";
                    }
                    if (Math.abs(dx) > 127 || Math.abs(dy) > 127) return "ERR arg";
                    switch (TvRemoteAccessibilityService.airMove(dx, dy)) {
                        case TvRemoteAccessibilityService.RES_OK: return "OK";
                        case TvRemoteAccessibilityService.RES_NO_A11Y: return "ERR a11y";
                        default: return "ERR exec";
                    }
                }
                case "MTAP": {
                    // SPEC-023：光标处单击，节点优先、手势兜底
                    switch (TvRemoteAccessibilityService.airTap()) {
                        case TvRemoteAccessibilityService.RES_OK: return "OK";
                        case TvRemoteAccessibilityService.RES_NO_A11Y: return "ERR a11y";
                        default: return "ERR exec";
                    }
                }
                case "MLONG": {
                    // SPEC-023：光标处长按，节点优先、手势兜底
                    switch (TvRemoteAccessibilityService.airLongTap()) {
                        case TvRemoteAccessibilityService.RES_OK: return "OK";
                        case TvRemoteAccessibilityService.RES_NO_A11Y: return "ERR a11y";
                        default: return "ERR exec";
                    }
                }
                case "SCROLL": {
                    // SPEC-023：正数向下/向前，范围 -5..5
                    if (p.length < 3) return "ERR arg";
                    int steps;
                    try {
                        steps = Integer.parseInt(p[2].trim());
                    } catch (NumberFormatException e) {
                        return "ERR arg";
                    }
                    if (Math.abs(steps) > 5) return "ERR arg";
                    switch (TvRemoteAccessibilityService.airScroll(steps)) {
                        case TvRemoteAccessibilityService.RES_OK: return "OK";
                        case TvRemoteAccessibilityService.RES_NO_A11Y: return "ERR a11y";
                        default: return "ERR exec";
                    }
                }
                case "MCLICK": {
                    // SPEC-021：左键按下/抬起，仅接受 0/1
                    if (p.length < 3) return "ERR arg";
                    String st = p[2].trim();
                    if (!st.equals("0") && !st.equals("1")) return "ERR arg";
                    switch (TvRemoteAccessibilityService.airClick(st.equals("1"))) {
                        case TvRemoteAccessibilityService.RES_OK: return "OK";
                        case TvRemoteAccessibilityService.RES_NO_A11Y: return "ERR a11y";
                        default: return "ERR exec";
                    }
                }
                case "MHIDE": {
                    // SPEC-023：退出触控板时立即隐藏光标，不等 5 秒空闲回收
                    if (p.length > 2) return "ERR arg";
                    switch (TvRemoteAccessibilityService.hideAirCursor()) {
                        case TvRemoteAccessibilityService.RES_OK: return "OK";
                        case TvRemoteAccessibilityService.RES_NO_A11Y: return "ERR a11y";
                        default: return "ERR exec";
                    }
                }
                case "MTAPN": {
                    // SPEC-028：绝对点按，归一化坐标 0..10000
                    int[] v = normPoint(p);
                    if (v == null) return "ERR arg";
                    if (AdbShell.enabled(this)) return adbResult(AdbShell.tap(this, v[0], v[1]));
                    switch (TvRemoteAccessibilityService.touchTap(v[0], v[1])) {
                        case TvRemoteAccessibilityService.RES_OK: return "OK";
                        case TvRemoteAccessibilityService.RES_NO_A11Y: return "ERR a11y";
                        default: return "ERR exec";
                    }
                }
                case "MLONGN": {
                    // SPEC-028：绝对长按，归一化坐标 0..10000
                    int[] v = normPoint(p);
                    if (v == null) return "ERR arg";
                    if (AdbShell.enabled(this)) return adbResult(AdbShell.longPress(this, v[0], v[1]));
                    switch (TvRemoteAccessibilityService.touchLong(v[0], v[1])) {
                        case TvRemoteAccessibilityService.RES_OK: return "OK";
                        case TvRemoteAccessibilityService.RES_NO_A11Y: return "ERR a11y";
                        default: return "ERR exec";
                    }
                }
                case "MSWIPEN": {
                    // SPEC-028：绝对滑动，归一化坐标 0..10000 + 时长 50..1500ms
                    if (p.length < 3) return "ERR arg";
                    String[] w = p[2].trim().split("\\s+");
                    if (w.length != 5) return "ERR arg";
                    int[] a = new int[5];
                    try {
                        for (int i = 0; i < 5; i++) a[i] = Integer.parseInt(w[i]);
                    } catch (NumberFormatException e) {
                        return "ERR arg";
                    }
                    for (int i = 0; i < 4; i++) {
                        if (a[i] < 0 || a[i] > 10000) return "ERR arg";
                    }
                    if (a[4] < 50 || a[4] > 1500) return "ERR arg";
                    if (AdbShell.enabled(this)) {
                        return adbResult(AdbShell.swipe(
                                this, a[0], a[1], a[2], a[3], a[4]));
                    }
                    switch (TvRemoteAccessibilityService.touchSwipe(
                            a[0], a[1], a[2], a[3], a[4])) {
                        case TvRemoteAccessibilityService.RES_OK: return "OK";
                        case TvRemoteAccessibilityService.RES_NO_A11Y: return "ERR a11y";
                        default: return "ERR exec";
                    }
                }
                case "TEXT": {
                    if (p.length < 3) return "ERR arg";
                    if (p[2].length() > MAX_TEXT_B64) return "ERR toolong";
                    String txt = new String(Base64.decode(p[2], Base64.NO_WRAP), "UTF-8");
                    if (txt.codePointCount(0, txt.length()) > MAX_TEXT_CHARS) return "ERR toolong";
                    if (AdbShell.enabled(this)) {
                        // SPEC-039：ASCII 走 input text；中文等退回无障碍 ACTION_SET_TEXT
                        int r = AdbShell.text(this, txt);
                        if (r != AdbShell.RES_FAIL) return adbResult(r);
                    }
                    switch (TvRemoteAccessibilityService.text(txt)) {
                        case TvRemoteAccessibilityService.RES_OK: return "OK";
                        case TvRemoteAccessibilityService.RES_NO_A11Y: return "ERR a11y";
                        default: return "ERR exec";
                    }
                }
                case "APP":
                    if (p.length < 3) return "ERR arg";
                    if (!validPkg(p[2])) return "ERR arg";
                    return launch(p[2]) ? "OK" : "ERR exec";
                case "LIST":
                    // SPEC-016：返回第三方应用「包名|base64(应用名)」，逗号分隔
                    return "OK " + listPackages();
                case "ICON":
                    // SPEC-019：按包名返回应用图标 PNG 的 Base64
                    if (p.length < 3) return "ERR arg";
                    {
                        String icon = iconBase64(p[2].trim());
                        if (icon == null) return "ERR arg";
                        return "OK " + icon;
                    }
                case "APKURL": {
                    // SPEC-040：从下载链接安装 APK。立即返回 OK，后台异步下载并调起安装
                    if (p.length < 3) return "ERR arg";
                    String url;
                    try {
                        url = new String(WebServer.b64Decode(p[2].trim()), "UTF-8");
                    } catch (Exception e) {
                        return "ERR arg";
                    }
                    String low = url.toLowerCase(java.util.Locale.US);
                    if (!low.startsWith("http://") && !low.startsWith("https://")) {
                        return "ERR arg";
                    }
                    final String apkUrl = url;
                    pool.submit(() -> downloadAndInstall(apkUrl));
                    return "OK";
                }
                default:
                    return "ERR cmd";
            }
        } catch (Exception e) {
            return "ERR " + e.getClass().getSimpleName();
        }
    }

    // ---------------- SPEC-039 ADB 注入结果映射 ----------------

    /** AdbShell 结果转协议返回：不可用明确报 ERR adb，不退化成无障碍 */
    private static String adbResult(int r) {
        switch (r) {
            case AdbShell.RES_OK: return "OK";
            case AdbShell.RES_NO_ADB: return "ERR adb";
            default: return "ERR exec";
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

    /** SPEC-019：取应用图标并渲染为 144x144 PNG 的 Base64；包不存在或渲染失败返回 null */
    private String iconBase64(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            Drawable d = pm.getApplicationIcon(pkg);
            Bitmap bmp = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, 144, 144);
            d.draw(c);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            bmp.recycle();
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(TAG, "icon fail: " + pkg, e);
            return null;
        }
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

    // ---------------- SPEC-040 APK 下载与安装 ----------------

    /** 下载上限 500MB */
    private static final long APK_MAX_BYTES = 500L * 1024 * 1024;

    /** SPEC-040：后台下载 APK 链接并调起系统安装界面 */
    private void downloadAndInstall(String url) {
        File dir = new File(getCacheDir(), "apkpush");
        File apk = new File(dir, System.currentTimeMillis() + ".apk");
        try {
            Updater.downloadUrl(this, url, apk, APK_MAX_BYTES);
            if (!isApkFile(apk)) {
                apk.delete();
                toastOnMain(getString(R.string.apk_not_apk));
                return;
            }
            Updater.install(this, apk);
        } catch (Exception e) {
            Log.e(TAG, "apk download/install fail: " + url, e);
            toastOnMain(getString(R.string.apk_download_failed));
        }
    }

    /** 校验 ZIP 魔数 PK\x03\x04（APK 是 ZIP 格式） */
    private static boolean isApkFile(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] h = new byte[4];
            int n = in.read(h);
            return n >= 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 0x03 && h[3] == 0x04;
        } catch (Exception e) {
            return false;
        }
    }

    /** 在主线程弹 Toast（后台线程调用） */
    private void toastOnMain(final String msg) {
        new Handler(Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show());
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
