package com.koco.tvremoteserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 显示异常自愈守护。
 *
 * 故障现象：休眠唤醒或 HDMI 热插拔后，画面缩成左上角一小块（实测约占屏幕 39%）。
 * 根因：HWC 重建 DRM 模式表时退回 1280x960，而显示器原生是 2048x1536 且不做缩放，
 * 1280x960 的信号按 1:1 显示在左上角。此时 SurfaceFlinger 的 DisplayInfo 也会同步
 * 变成 1280x960，所以「应用侧尺寸与内核尺寸一致」不能证明画面正常，必须和 EDID
 * 首选模式比较。
 *
 * 判据：/d/dri/0/summary 中 HDMI 的当前 Display mode 与
 * /sys/class/drm/card0-HDMI-A-1/modes 首行（EDID 首选模式）不一致即故障。
 * 息屏、HDMI 未连接或读不到 EDID 时不判断、不动作。
 *
 * 恢复：执行 setprop ctl.restart vendor.hwcomposer-2-1，让 HWC 重新读取 EDID 并重建
 * 模式表。该服务 rc 带 onrestart restart surfaceflinger，
 * 本固件 surfaceflinger.rc 又带 onrestart restart zygote，因此会连带框架软重启
 * （黑屏约 30 秒，所有应用被杀），本进程也会被带走；重启后由前台服务的 START_STICKY
 * 拉起，通过 dg_pending 复核结果。单次故障最多 3 次，之后只记录并提示人工处理。
 */
final class DisplayGuard {

    private static final String TAG = "TvRemoteDisplay";
    private static final String PREF = "srv";
    private static final String KEY_BASE = "dg_base";
    private static final String KEY_PENDING = "dg_pending";
    private static final String KEY_ATTEMPTS = "dg_attempts";
    private static final String KEY_LAST_AT = "dg_last_at";
    private static final String KEY_MANUAL = "dg_manual";

    /** 界面状态：等待采样 / 正常 / 检测到异常并自愈中 / 自愈失败需人工处理 */
    static final int ST_UNKNOWN = 0;
    static final int ST_NORMAL = 1;
    static final int ST_HEALING = 2;
    static final int ST_MANUAL = 3;

    private static final String SUMMARY = "/d/dri/0/summary";
    private static final String DRM_DIR = "/sys/class/drm";
    private static final String HWC_SERVICE = "vendor.hwcomposer-2-1";
    private static final String LOG_FILE = "display_guard.log";
    private static final int LOG_KEEP = 40;

    /** 采样间隔；连续 5 次（约 10 秒）异常才动作，避开唤醒瞬间的抖动 */
    private static final long SAMPLE_MS = 2000;
    private static final int NEED_MISMATCH = 5;
    /** 进程刚启动时先让显示稳定（框架重启后服务会在开机动画期间被拉起） */
    private static final long START_SETTLE_MS = 25000;
    /** 两次动作的最小间隔，防止框架还没起稳就再次重启 */
    private static final long RETRY_COOLDOWN_MS = 60000;
    /** 执行动作后若本进程还活着，就地等待并复核 */
    private static final long VERIFY_MS = 15000;
    /** 单次故障最多自愈次数，超过后只记录不动作 */
    private static final int MAX_ATTEMPTS = 3;

    private static final Pattern MODE = Pattern.compile("(\\d+)x(\\d+)");

    private static volatile boolean running;
    private static volatile int state = ST_UNKNOWN;
    private static volatile String stateSize = "";
    private static volatile boolean rootOk;
    private static Thread thread;
    private static final Object LOCK = new Object();

    private DisplayGuard() {
    }

    // ---------------- 生命周期与状态 ----------------

    /** 由前台服务启动，进程存活期间常驻；Activity 销毁不影响 */
    static void start(Context c) {
        if (running) return;
        running = true;
        final Context app = c.getApplicationContext();
        thread = new Thread(() -> loop(app), "display-guard");
        thread.setDaemon(true);
        thread.start();
    }

    static void stop() {
        running = false;
        Thread t = thread;
        if (t != null) t.interrupt();
        thread = null;
        state = ST_UNKNOWN;
    }

    static int state() {
        return state;
    }

    static String stateSize() {
        return stateSize;
    }

    static boolean rootOk() {
        return rootOk;
    }

    private static void setState(int s, String size) {
        state = s;
        stateSize = size;
    }

    // ---------------- 主循环 ----------------

    private static void loop(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sleep(START_SETTLE_MS);

        // 上次动作若把进程带走了，这里先核对结果
        reconcile(c, sp);
        probeRoot(c);

        int bad = 0;
        while (running) {
            Sample s = sample(c, sp);
            if (s == null) {
                // 息屏、HDMI 未连接或既没有 EDID 也没有基准：无法判断，清零计数
                bad = 0;
                setState(ST_UNKNOWN, "");
                sleep(SAMPLE_MS);
                continue;
            }
            if (s.ok()) {
                bad = 0;
                if (s.refPreferred) saveBase(sp, s.kernelText());
                if (sp.getBoolean(KEY_MANUAL, false) || sp.getInt(KEY_ATTEMPTS, 0) > 0) {
                    sp.edit().putBoolean(KEY_MANUAL, false).putInt(KEY_ATTEMPTS, 0).apply();
                }
                if (state != ST_NORMAL) {
                    record(c, "显示正常：内核 " + s.kernelText()
                            + "（" + s.refLabel() + " " + s.refText() + "）");
                }
                setState(ST_NORMAL, s.kernelText());
                sleep(SAMPLE_MS);
                continue;
            }

            bad++;
            if (bad < NEED_MISMATCH) {
                setState(ST_HEALING, s.kernelText());
                sleep(SAMPLE_MS);
                continue;
            }
            bad = 0;

            if (sp.getBoolean(KEY_MANUAL, false)) {
                setState(ST_MANUAL, s.kernelText());
                sleep(SAMPLE_MS);
                continue;
            }
            int attempts = sp.getInt(KEY_ATTEMPTS, 0);
            if (attempts >= MAX_ATTEMPTS) {
                sp.edit().putBoolean(KEY_MANUAL, true).apply();
                record(c, "连续 " + MAX_ATTEMPTS + " 次自愈失败（内核 " + s.kernelText()
                        + "，" + s.refLabel() + " " + s.refText() + "），需要人工处理");
                setState(ST_MANUAL, s.kernelText());
                sleep(SAMPLE_MS);
                continue;
            }
            long since = System.currentTimeMillis() - sp.getLong(KEY_LAST_AT, 0);
            if (since < RETRY_COOLDOWN_MS) {
                // 冷却中：保持异常提示，等框架起稳后再试
                setState(ST_HEALING, s.kernelText());
                sleep(SAMPLE_MS);
                continue;
            }
            heal(c, sp, s);
        }
    }

    /** 核对上一次动作的结果；采样无效（还在开机/息屏）时保持 pending，稍后再判 */
    private static void reconcile(Context c, SharedPreferences sp) {
        if (!sp.getBoolean(KEY_PENDING, false)) return;
        Sample s = null;
        long end = System.currentTimeMillis() + 60000;
        while (running && System.currentTimeMillis() < end) {
            s = sample(c, sp);
            if (s != null) break;
            sleep(SAMPLE_MS);
        }
        if (s == null) return;
        if (s.ok()) {
            if (s.refPreferred) saveBase(sp, s.kernelText());
            record(c, "自愈成功：内核 " + s.kernelText()
                    + "（" + s.refLabel() + " " + s.refText() + "）");
            sp.edit().putBoolean(KEY_PENDING, false).putInt(KEY_ATTEMPTS, 0).commit();
            setState(ST_NORMAL, s.kernelText());
        } else {
            record(c, "自愈未生效：内核 " + s.kernelText()
                    + "（" + s.refLabel() + " " + s.refText() + "）");
            sp.edit().putBoolean(KEY_PENDING, false).commit();
        }
    }

    /** root 通道自检，结果写日志，便于事后确认权限是否具备 */
    private static void probeRoot(Context c) {
        String out = root("id");
        rootOk = out != null && out.contains("uid=0");
        record(c, "root 通道：" + (rootOk ? "可用" : "不可用")
                + (out == null ? "（su 无法执行）" : "（" + out.replace('\n', ' ') + "）"));
    }

    private static void heal(Context c, SharedPreferences sp, Sample s) {
        int n = sp.getInt(KEY_ATTEMPTS, 0) + 1;
        // 这里必须同步提交：动作会连带重启框架、本进程即将被杀，
        // apply() 的异步落盘可能来不及，重启后计数归零会导致无限自愈
        sp.edit().putInt(KEY_ATTEMPTS, n)
                .putLong(KEY_LAST_AT, System.currentTimeMillis())
                .putBoolean(KEY_PENDING, true).commit();
        setState(ST_HEALING, s.kernelText());
        record(c, "第 " + n + "/" + MAX_ATTEMPTS + " 次自愈：内核 " + s.kernelText()
                + "，" + s.refLabel() + " " + s.refText() + "，执行重启 " + HWC_SERVICE);
        // 该固件不读取 persist.vendor.resolution.*（实测改了也不生效），只能重启 composer
        // 让 HWC 重新读 EDID 建模式表；重启会连带 SF 与 zygote，无法更轻量
        String out = root("setprop ctl.restart " + HWC_SERVICE + "; echo RC=$?");
        record(c, "自愈命令返回：" + (out == null ? "su 不可用" : (out.isEmpty() ? "无输出" : out)));

        // 本固件 SF 重启会连带重启 zygote，进程通常会被杀；只有存活下来才就地复核
        sleep(VERIFY_MS);
        Sample v = sample(c, sp);
        if (v != null && v.ok()) {
            record(c, "自愈成功（就地复核）：内核 " + v.kernelText());
            sp.edit().putBoolean(KEY_PENDING, false).putInt(KEY_ATTEMPTS, 0).apply();
            setState(ST_NORMAL, v.kernelText());
        }
    }

    // ---------------- 采样 ----------------

    /** 一次采样；返回 null 表示当前无法判断（息屏 / HDMI 未连接 / 无参照） */
    private static Sample sample(Context c, SharedPreferences sp) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return null;

        Connector conn = hdmiConnector();
        if (conn == null) return null;
        int[] kernel = kernelMode(conn.name);
        if (kernel == null) return null;
        if (conn.preferred != null) return new Sample(kernel, conn.preferred, true);

        // EDID 读不到时，用首次正常状态持久化下来的尺寸作参照
        int[] base = parse(sp.getString(KEY_BASE, ""));
        return base == null ? null : new Sample(kernel, base, false);
    }

    /**
     * 找到已连接的 HDMI 连接器，返回其名称与 EDID 首选模式（modes 文件首行）。
     * modes 读不到时 preferred 为 null，交由调用方退回持久化基准。
     */
    private static Connector hdmiConnector() {
        File[] dirs = new File(DRM_DIR).listFiles();
        if (dirs == null) return null;
        for (File d : dirs) {
            String name = d.getName();
            if (!name.startsWith("card0-HDMI-A-")) continue;
            if (!"connected".equals(firstLine(new File(d, "status")))) continue;
            return new Connector(name.substring("card0-".length()),
                    parse(firstLine(new File(d, "modes"))));
        }
        return null;
    }

    private static void saveBase(SharedPreferences sp, String v) {
        if (!v.equals(sp.getString(KEY_BASE, ""))) sp.edit().putString(KEY_BASE, v).apply();
    }

    /** 内核侧 HDMI 当前输出模式，取自 /d/dri/0/summary 的 Display mode 行 */
    private static int[] kernelMode(String conn) {
        File f = new File(SUMMARY);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            boolean hdmi = false;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("Connector:")) {
                    hdmi = line.contains(conn);
                    continue;
                }
                if (hdmi && line.contains("Display mode:")) return parse(line);
            }
        } catch (Exception e) {
            Log.w(TAG, "read " + SUMMARY + ": " + e.getMessage());
        }
        return null;
    }

    private static String firstLine(File f) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            return r.readLine();
        } catch (Exception e) {
            Log.w(TAG, "read " + f + ": " + e.getMessage());
            return null;
        }
    }

    private static int[] parse(String s) {
        if (s == null || s.isEmpty()) return null;
        Matcher m = MODE.matcher(s);
        if (!m.find()) return null;
        try {
            int w = Integer.parseInt(m.group(1));
            int h = Integer.parseInt(m.group(2));
            return w > 0 && h > 0 ? new int[]{w, h} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** HDMI 连接器信息；preferred 为 null 表示 EDID 模式表不可读 */
    private static final class Connector {
        final String name;
        final int[] preferred;

        Connector(String name, int[] preferred) {
            this.name = name;
            this.preferred = preferred;
        }
    }

    /** 一次采样结果：内核当前模式与参照模式（EDID 首选或持久化基准） */
    private static final class Sample {
        final int kw;
        final int kh;
        final int rw;
        final int rh;
        final boolean refPreferred;

        Sample(int[] kernel, int[] ref, boolean refPreferred) {
            this.kw = kernel[0];
            this.kh = kernel[1];
            this.rw = ref[0];
            this.rh = ref[1];
            this.refPreferred = refPreferred;
        }

        boolean ok() {
            return kw == rw && kh == rh;
        }

        String kernelText() {
            return kw + "x" + kh;
        }

        String refText() {
            return rw + "x" + rh;
        }

        String refLabel() {
            return refPreferred ? "EDID 首选" : "基准";
        }
    }

    // ---------------- root 执行 ----------------

    /**
     * 以 root 执行一条 shell 命令，返回输出（含 RC=退出码）；
     * 返回 null 表示 su 根本无法执行。
     */
    private static String root(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(suPath(), "0", "sh", "-c", cmd)
                    .redirectErrorStream(true).start();
            final Process proc = p;
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                } catch (Exception ignored) {
                }
            }, "display-guard-su");
            reader.setDaemon(true);
            reader.start();
            reader.join(15000);
            if (reader.isAlive()) {
                p.destroy();
                return "timeout";
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            Log.w(TAG, "su exec fail: " + e.getMessage());
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** 该固件 su 在 /system/xbin；找不到时交给 PATH 解析 */
    private static String suPath() {
        File x = new File("/system/xbin/su");
        if (x.exists()) return x.getAbsolutePath();
        File b = new File("/system/bin/su");
        if (b.exists()) return b.getAbsolutePath();
        return "su";
    }

    // ---------------- 记录 ----------------

    /** 本地保留最近 40 条故障与恢复记录，只写应用私有目录 */
    private static void record(Context c, String msg) {
        Log.i(TAG, msg);
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date()) + " " + msg;
        synchronized (LOCK) {
            File f = new File(c.getFilesDir(), LOG_FILE);
            List<String> lines = new ArrayList<>();
            if (f.exists()) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
                    String s;
                    while ((s = r.readLine()) != null) lines.add(s);
                } catch (Exception ignored) {
                }
            }
            lines.add(line);
            while (lines.size() > LOG_KEEP) lines.remove(0);
            try (PrintWriter w = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(f), "UTF-8"))) {
                for (String s : lines) w.println(s);
            } catch (Exception e) {
                Log.w(TAG, "write log fail: " + e.getMessage());
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
