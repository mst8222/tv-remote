package com.koco.tvremoteserver;

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
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 GitHub 与 Gitee 公开 Release 的 OTA 更新（SPEC-017 / SPEC-030）。
 *
 * 两个源并行探测，取版本更高者；下载优先 Gitee 直链（国内可达），
 * GitHub 资产 API 作为回退。单个源不可达不影响另一个源。
 */
final class Updater {

    /** GitHub 公开仓库与资产文件名：手机端下载 TVRemote.apk */
    private static final String GH_API = "https://api.github.com/repos/mst8222/tv-remote/releases/latest";
    /** GitHub 资产下载走 API 端点：浏览器下载地址所在域名在部分网络会被卡住 */
    private static final String GH_ASSET_API =
            "https://api.github.com/repos/mst8222/tv-remote/releases/assets/";
    /** Gitee 镜像仓库：与 GitHub 公开快照同内容，Release 同步发布 */
    private static final String GITEE_API = "https://gitee.com/api/v5/repos/moston/tv-remote/releases";
    private static final String APK_NAME = "TVRemoteServer.apk";
    private static final int TIMEOUT_MS = 15000;

    /** 单个下载源：Gitee 为直链，GitHub 走资产 API */
    static final class Source {
        String url = "";
        long size = 0;
        /** GitHub 资产 id；0 表示直链（Gitee） */
        long assetId = 0;
    }

    /** 最新 Release 的必要信息；sources 已按下载优先级排序 */
    static final class Release {
        String tag = "";
        String notes = "";
        long size = 0;
        final List<Source> sources = new ArrayList<>();
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
        return compare(tag, current) > 0;
    }

    private static int compare(String a, String b) {
        int[] x = parse(displayVersion(a));
        int[] y = parse(displayVersion(b));
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) return x[i] > y[i] ? 1 : -1;
        }
        return 0;
    }

    private static boolean sameVersion(String a, String b) {
        return compare(a, b) == 0;
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

    /** 并行读取两个源，合并成版本更高、带按优先级排序下载源的结果；阻塞调用 */
    static Release fetchLatest(Context c) throws Exception {
        final Release[] found = new Release[2];
        final Exception[] errors = new Exception[2];

        Thread gh = new Thread(() -> {
            try {
                found[0] = fetchGithub(c);
            } catch (Exception e) {
                errors[0] = e;
            }
        }, "ota-github");
        Thread ge = new Thread(() -> {
            try {
                found[1] = fetchGitee(c);
            } catch (Exception e) {
                errors[1] = e;
            }
        }, "ota-gitee");
        gh.setDaemon(true);
        ge.setDaemon(true);
        gh.start();
        ge.start();
        join(gh);
        join(ge);

        Release github = found[0];
        Release gitee = found[1];
        if (github == null && gitee == null) {
            throw errors[0] != null ? errors[0] : errors[1];
        }

        Release best;
        if (github == null) {
            best = gitee;
        } else if (gitee == null) {
            best = github;
        } else {
            best = compare(github.tag, gitee.tag) >= 0 ? github : gitee;
        }

        Release out = new Release();
        out.tag = best.tag;
        out.notes = best.notes;
        // 下载优先级：Gitee 直链在前，GitHub 资产 API 在后
        if (gitee != null && sameVersion(gitee.tag, best.tag)) out.sources.addAll(gitee.sources);
        if (github != null && sameVersion(github.tag, best.tag)) out.sources.addAll(github.sources);
        // 更新说明优先取 GitHub（正文更完整），为空再用 Gitee
        if (github != null && sameVersion(github.tag, best.tag)
                && !github.notes.trim().isEmpty()) {
            out.notes = github.notes;
        }
        if (out.sources.isEmpty()) {
            throw new Exception(c.getString(R.string.update_err_asset, APK_NAME));
        }
        out.size = out.sources.get(0).size;
        return out;
    }

    private static void join(Thread t) {
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** GitHub releases/latest */
    private static Release fetchGithub(Context c) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = open(GH_API, "application/vnd.github+json");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception(c.getString(R.string.update_err_http, code));
            }
            JSONObject o = new JSONObject(readAll(conn.getInputStream()));
            Release r = new Release();
            r.tag = o.optString("tag_name", "");
            r.notes = o.optString("body", "");
            Source s = findAsset(o.optJSONArray("assets"), true);
            if (r.tag.isEmpty()) throw new Exception(c.getString(R.string.update_err_json));
            if (s == null) throw new Exception(c.getString(R.string.update_err_asset, APK_NAME));
            r.sources.add(s);
            return r;
        } catch (IOException e) {
            android.util.Log.w("TVRemoteOTA", "更新源网络失败", e);
            throw new Exception(c.getString(R.string.update_err_network), e);
        } catch (JSONException e) {
            throw new Exception(c.getString(R.string.update_err_json), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Gitee releases：返回数组，取版本最高且带本端 APK 的一项 */
    private static Release fetchGitee(Context c) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = open(GITEE_API, "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception(c.getString(R.string.update_err_http, code));
            }
            JSONArray arr = new JSONArray(readAll(conn.getInputStream()));
            Release best = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optBoolean("prerelease")) continue;
                String tag = o.optString("tag_name", "");
                if (tag.isEmpty()) continue;
                Source s = findAsset(o.optJSONArray("assets"), false);
                if (s == null) continue;
                if (best == null || compare(tag, best.tag) > 0) {
                    best = new Release();
                    best.tag = tag;
                    best.notes = o.optString("body", "");
                    best.sources.add(s);
                }
            }
            if (best == null) {
                throw new Exception(c.getString(R.string.update_err_asset, APK_NAME));
            }
            return best;
        } catch (IOException e) {
            android.util.Log.w("TVRemoteOTA", "更新源网络失败", e);
            throw new Exception(c.getString(R.string.update_err_network), e);
        } catch (JSONException e) {
            throw new Exception(c.getString(R.string.update_err_json), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 在资产数组里找本端 APK；github=true 时要求资产 id（下载走资产 API） */
    private static Source findAsset(JSONArray assets, boolean github) throws JSONException {
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (!APK_NAME.equals(a.optString("name"))) continue;
            Source s = new Source();
            s.url = a.optString("browser_download_url", "");
            s.size = a.optLong("size", 0);
            if (github) {
                s.assetId = a.optLong("id", 0);
                if (s.url.isEmpty() || s.assetId == 0) return null;
            } else if (s.url.isEmpty()) {
                return null;
            }
            return s;
        }
        return null;
    }

    /** 下载安装包到私有缓存；按 Gitee、GitHub 顺序逐个源尝试；阻塞调用 */
    static File download(Context c, Release r) throws Exception {
        File dir = new File(c.getCacheDir(), "update");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new Exception(c.getString(R.string.update_err_network));
        }
        File apk = new File(dir, APK_NAME);
        Exception last = null;
        for (Source s : r.sources) {
            if (apk.exists() && !apk.delete()) {
                throw new Exception(c.getString(R.string.update_err_size));
            }
            HttpURLConnection conn = null;
            try {
                String url = s.assetId > 0 ? GH_ASSET_API + s.assetId : s.url;
                conn = open(url, "application/octet-stream");
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
                if (s.size > 0 && apk.length() != s.size) {
                    throw new Exception(c.getString(R.string.update_err_size));
                }
                return apk;
            } catch (IOException e) {
                last = new Exception(c.getString(R.string.update_err_network), e);
            } catch (Exception e) {
                last = e;
            } finally {
                if (conn != null) conn.disconnect();
            }
            apk.delete();
        }
        throw last != null ? last : new Exception(c.getString(R.string.update_err_network));
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
