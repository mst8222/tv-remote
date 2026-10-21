package com.koco.tvremote;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 基于 GitHub 公开 Release 的 OTA 更新（SPEC-017）。
 * 只读取固定仓库的最新 Release，下载与本应用同名的 APK 后交给系统安装器。
 */
final class Updater {

    /** 公开仓库与资产文件名：手机端下载 TVRemote.apk */
    private static final String REPO = "mst8222/tv-remote";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    /** 资产下载走 API 端点：浏览器下载地址所在域名在部分网络会被卡住，API 域名通常可达 */
    private static final String ASSET_API = "https://api.github.com/repos/" + REPO + "/releases/assets/";
    private static final String APK_NAME = "TVRemote.apk";
    private static final int TIMEOUT_MS = 30000;

    /** 最新 Release 的必要信息 */
    static final class Release {
        String tag = "";
        String notes = "";
        String url = "";
        long size = 0;
        long assetId = 0;
    }

    private Updater() {
    }

    /** 当前应用 versionName */
    static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /** 去掉 tag 的 v 前缀，用于界面展示 */
    static String displayVersion(String tag) {
        if (tag == null) return "";
        String s = tag.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        return s;
    }

    /** tag 是否比当前版本新：逐段按数字比较，段数不足按 0 处理 */
    static boolean isNewer(String tag, String current) {
        int[] a = parse(displayVersion(tag));
        int[] b = parse(displayVersion(current));
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    private static int[] parse(String v) {
        int[] out = new int[3];
        String[] parts = v.split("\\.");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** 读取最新 Release；阻塞调用，需在后台线程执行 */
    static Release fetchLatest(Context c) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = open(API, "application/vnd.github+json");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception(c.getString(R.string.update_err_http, code));
            }
            JSONObject o = new JSONObject(readAll(conn.getInputStream()));
            Release r = new Release();
            r.tag = o.optString("tag_name", "");
            r.notes = o.optString("body", "");
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (APK_NAME.equals(a.optString("name"))) {
                        r.url = a.optString("browser_download_url", "");
                        r.size = a.optLong("size", 0);
                        r.assetId = a.optLong("id", 0);
                        break;
                    }
                }
            }
            if (r.tag.isEmpty()) throw new Exception(c.getString(R.string.update_err_json));
            if (r.url.isEmpty() || r.assetId == 0) {
                throw new Exception(c.getString(R.string.update_err_asset, APK_NAME));
            }
            return r;
        } catch (IOException e) {
            throw new Exception(c.getString(R.string.update_err_network), e);
        } catch (JSONException e) {
            throw new Exception(c.getString(R.string.update_err_json), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 下载安装包到私有缓存；阻塞调用，需在后台线程执行 */
    static File download(Context c, Release r) throws Exception {
        File dir = new File(c.getCacheDir(), "update");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new Exception(c.getString(R.string.update_err_network));
        }
        File apk = new File(dir, APK_NAME);
        if (apk.exists() && !apk.delete()) {
            throw new Exception(c.getString(R.string.update_err_size));
        }
        HttpURLConnection conn = null;
        try {
            conn = open(ASSET_API + r.assetId, "application/octet-stream");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception(c.getString(R.string.update_err_http, code));
            }
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            if (r.size > 0 && apk.length() != r.size) {
                apk.delete();
                throw new Exception(c.getString(R.string.update_err_size));
            }
            return apk;
        } catch (IOException e) {
            apk.delete();
            throw new Exception(c.getString(R.string.update_err_network), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 交给系统安装器（SPEC-017 FR-17-7/8）。
     * 普通应用没有静默安装权限，commit 后会收到待用户确认的状态，
     * 由 UpdateInstallReceiver 拉起系统安装界面。
     *
     * @return true 表示已提交安装会话；false 表示被权限拦截或提交失败
     */
    static boolean install(Activity a, File apk) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, R.string.update_perm_required, Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
            } catch (Exception ignored) {
                // 个别电视固件没有该设置页，提示已足够
            }
            return false;
        }
        try {
            PackageInstaller pi = a.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            p.setAppPackageName(a.getPackageName());
            int id = pi.createSession(p);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    s.fsync(out);
                }
                Intent status = new Intent(a, UpdateInstallReceiver.class);
                // 必须 MUTABLE：系统要把 STATUS / EXTRA_INTENT 等 extras 填进来，
                // IMMUTABLE 会被系统忽略，接收器只会收到空状态（SPEC-017 实测踩坑）
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
                s.commit(PendingIntent.getBroadcast(a, 0, status, flags).getIntentSender());
            }
            return true;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            Toast.makeText(a, a.getString(R.string.update_install_failed, msg),
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", accept);
        conn.setRequestProperty("User-Agent", "TVRemote-OTA");
        return conn;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = is.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString();
        }
    }
}
