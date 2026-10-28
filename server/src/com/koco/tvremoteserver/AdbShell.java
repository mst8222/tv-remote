package com.koco.tvremoteserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SPEC-039：ADB（shell 身份）注入通道。
 *
 * 盒端自连本机 adbd（127.0.0.1:5555），用 input 命令注入真实输入事件，
 * 效果等同蓝牙 HID：云视听等不暴露无障碍节点树、只认真实按键的 TV 应用也能操作。
 *
 * 注入模式由盒端界面切换并持久化（srv.inject_adb）：
 *   false = 无障碍注入（默认，SPEC-014）
 *   true  = ADB 注入；连接不可用时协议返回 ERR adb，不静默回退无障碍。
 */
final class AdbShell {

    /** 执行成功 */
    static final int RES_OK = 0;
    /** 已连接但命令执行失败 */
    static final int RES_FAIL = 1;
    /** ADB 通道不可用（调试关闭 / 未授权 / 连接失败） */
    static final int RES_NO_ADB = 2;

    private static final String TAG = "TvRemoteAdb";
    private static final String PREF = "srv";
    private static final String KEY_MODE = "inject_adb";
    private static final String KEY_GRANTED = "adb_sec_granted";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    private static final int CONNECT_TIMEOUT = 2500;
    private static final int EXEC_TIMEOUT = 3000;
    /** 失败后冷却，避免连不上时每条命令都触发一次授权弹窗 */
    private static final long RETRY_COOLDOWN_MS = 5000;
    private static final String PERM_SECURE = "android.permission.WRITE_SECURE_SETTINGS";

    private static final Object LOCK = new Object();
    private static AdbClient client;
    private static long failAt;
    private static String lastError = "";
    private static int[] screen;

    private AdbShell() {
    }

    // ---------------- 模式 ----------------

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 是否使用 ADB 注入模式 */
    static boolean enabled(Context c) {
        return sp(c).getBoolean(KEY_MODE, false);
    }

    static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_MODE, on).apply();
        if (!on) reset();
    }

    static boolean isConnected() {
        synchronized (LOCK) {
            return client != null && client.isConnected();
        }
    }

    /** 最近一次连接/执行失败原因，供盒端界面显示 */
    static String lastError() {
        synchronized (LOCK) {
            return lastError;
        }
    }

    /** 盒端界面点「连接」时主动握手，返回是否成功 */
    static boolean probe(Context c) {
        synchronized (LOCK) {
            return ensure(c) != null;
        }
    }

    static void reset() {
        synchronized (LOCK) {
            if (client != null) client.close();
            client = null;
            screen = null;
            failAt = 0;
            lastError = "";
        }
    }

    // ---------------- 注入命令 ----------------

    /** 真实按键（含菜单键 82，无障碍做不到） */
    static int key(Context c, int code) {
        return result(c, "input keyevent " + code);
    }

    /** 长按：input keyevent --longpress；不支持时由调用方回退无障碍长按 */
    static int keyLong(Context c, int code) {
        return result(c, "input keyevent --longpress " + code);
    }

    /**
     * 文字：仅 ASCII 走 shell（input text 的 %s 表示空格）；
     * 中文/特殊字符返回 RES_FAIL，由调用方回退无障碍 ACTION_SET_TEXT。
     */
    static int text(Context c, String t) {
        if (!asciiSafe(t)) return RES_FAIL;
        return result(c, "input text '" + t.replace(" ", "%s") + "'");
    }

    /** 绝对点按：归一化坐标 0..10000 */
    static int tap(Context c, int nx, int ny) {
        int[] px = toScreen(c, nx, ny);
        if (px == null) return RES_NO_ADB;
        return result(c, "input tap " + px[0] + " " + px[1]);
    }

    /** 绝对长按：同一坐标按住 800ms（系统长按阈值 500ms） */
    static int longPress(Context c, int nx, int ny) {
        int[] px = toScreen(c, nx, ny);
        if (px == null) return RES_NO_ADB;
        return result(c, "input swipe " + px[0] + " " + px[1] + " " + px[0] + " " + px[1] + " 800");
    }

    /** 绝对滑动：起点/终点归一化坐标 + 时长 */
    static int swipe(Context c, int nx1, int ny1, int nx2, int ny2, int ms) {
        int[] a = toScreen(c, nx1, ny1);
        if (a == null) return RES_NO_ADB;
        int[] b = toScreen(c, nx2, ny2);
        if (b == null) return RES_NO_ADB;
        return result(c, "input swipe " + a[0] + " " + a[1] + " " + b[0] + " " + b[1] + " " + ms);
    }

    // ---------------- 连接与执行 ----------------

    private static int result(Context c, String cmd) {
        String out = run(c, cmd);
        if (out == null) return RES_NO_ADB;
        boolean ok = out.contains("RC=0");
        if (!ok) lastError = out.trim();
        return ok ? RES_OK : RES_FAIL;
    }

    /**
     * 执行一条命令：返回 null 表示 ADB 通道不可用；
     * 否则返回命令输出（含 RC=<退出码>）。
     */
    private static String run(Context c, String cmd) {
        synchronized (LOCK) {
            AdbClient a = ensure(c);
            if (a == null) return null;
            try {
                return a.exec(cmd + "; echo RC=$?", EXEC_TIMEOUT);
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Log.w(TAG, "exec fail: " + lastError);
                if (client != null) client.close();
                client = null;
                screen = null;
                failAt = System.currentTimeMillis();
                return null;
            }
        }
    }

    /** 惰性连接（带失败冷却）；连上后顺手申请 WRITE_SECURE_SETTINGS，便于盒端开关直接开 ADB */
    private static AdbClient ensure(Context c) {
        if (client != null && client.isConnected()) return client;
        if (failAt != 0 && System.currentTimeMillis() - failAt < RETRY_COOLDOWN_MS) return null;
        AdbClient a = null;
        try {
            a = AdbClient.open(new File(c.getFilesDir(), "adbkey.pk8"));
            a.connect(HOST, PORT, CONNECT_TIMEOUT);
            client = a;
            failAt = 0;
            lastError = "";
            selfGrant(c, a);
            return a;
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Log.w(TAG, "connect fail: " + lastError);
            if (a != null) a.close();
            client = null;
            failAt = System.currentTimeMillis();
            return null;
        }
    }

    /**
     * 首次连上后把 WRITE_SECURE_SETTINGS 授予自己（shell 的 pm grant 允许授予
     * development 级权限）。拿到后盒端界面的「ADB 调试」开关就能自行打开，
     * 不必每次都让用户进开发者选项。
     */
    private static void selfGrant(Context c, AdbClient a) {
        if (hasSecureWrite(c)) return;
        if (sp(c).getBoolean(KEY_GRANTED, false)) return;
        try {
            String out = a.exec("pm grant " + c.getPackageName() + " " + PERM_SECURE
                    + "; echo RC=$?", 4000);
            boolean ok = out.contains("RC=0") && hasSecureWrite(c);
            sp(c).edit().putBoolean(KEY_GRANTED, ok).apply();
            Log.i(TAG, "self grant WRITE_SECURE_SETTINGS: " + ok);
        } catch (Exception e) {
            Log.w(TAG, "grant fail: " + e.getMessage());
        }
    }

    /** 是否有写系统设置（开关 ADB 调试）的权限 */
    static boolean hasSecureWrite(Context c) {
        return c.getPackageManager().checkPermission(PERM_SECURE, c.getPackageName())
                == PackageManager.PERMISSION_GRANTED;
    }

    /** ADB 调试开关当前状态（开发者选项里的总开关） */
    @SuppressWarnings("deprecation")
    static boolean adbEnabled(Context c) {
        return android.provider.Settings.Global.getInt(c.getContentResolver(),
                android.provider.Settings.Global.ADB_ENABLED, 0) == 1;
    }

    /**
     * 打开 ADB 调试。需要 WRITE_SECURE_SETTINGS；成功返回 true。
     * 注意 adbd 会重启，本应用与它的连接随即断开，下一次命令自动重连。
     */
    @SuppressWarnings("deprecation")
    static boolean enableAdb(Context c) {
        if (!hasSecureWrite(c)) return false;
        try {
            android.provider.Settings.Global.putInt(c.getContentResolver(),
                    android.provider.Settings.Global.ADB_ENABLED, 1);
            reset();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "enable adb fail: " + e.getMessage());
            return false;
        }
    }

    // ---------------- 坐标 ----------------

    /**
     * 归一化坐标转像素。屏幕尺寸取 `wm size`（input 注入走的是显示逻辑坐标，
     * 与该命令输出一致），取不到时退回应用 DisplayMetrics。
     */
    private static int[] toScreen(Context c, int nx, int ny) {
        int[] s = screenSize(c);
        if (s == null) return null;
        return new int[]{
                Math.max(0, Math.min(s[0] - 1, nx * (s[0] - 1) / 10000)),
                Math.max(0, Math.min(s[1] - 1, ny * (s[1] - 1) / 10000))
        };
    }

    private static int[] screenSize(Context c) {
        synchronized (LOCK) {
            if (screen != null) return screen;
            String out = run(c, "wm size");
            int w = 0;
            int h = 0;
            if (out != null) {
                Matcher m = Pattern.compile("(\\d+)x(\\d+)").matcher(out);
                while (m.find()) {
                    w = Integer.parseInt(m.group(1));
                    h = Integer.parseInt(m.group(2));
                }
            }
            if (w <= 0 || h <= 0) {
                DisplayMetrics dm = c.getResources().getDisplayMetrics();
                w = dm.widthPixels;
                h = dm.heightPixels;
            }
            screen = new int[]{w, h};
            return screen;
        }
    }

    /** input text 只能安全传 ASCII：排除会破坏 shell 单引号与 % 分隔符的字符 */
    private static boolean asciiSafe(String t) {
        if (t.isEmpty()) return true;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch < 0x20 || ch > 0x7E) return false;
            if ("'\"`$\\;|&<>()*?!#~%".indexOf(ch) >= 0) return false;
        }
        return true;
    }
}
