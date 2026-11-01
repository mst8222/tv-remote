import com.koco.tvremoteserver.WebServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * SPEC-020 Web 遥控服务的自动测试。
 * 被测对象是纯 Java 的 WebServer，命令处理注入 MockBoxServer，
 * 静态资源从 server/assets/web 直接读取（与 APK assets 同源）。
 */
public class WebApiTest {

    static MockBoxServer box;
    static WebServer web;
    static int port;
    static int passed = 0;
    static int failed = 0;
    // SPEC-040：mock APK 上传处理器，记录上传结果
    static volatile String apkUploadErr;
    static volatile byte[] apkUploadBytes;

    public static void main(String[] args) throws Exception {
        box = new MockBoxServer("123456");
        box.packages = "com.android.settings|"
                + b64("设置") + ",com.example.app|" + b64("示例应用");
        port = freePort();
        WebServer.ApkUploadHandler uploadHandler = new WebServer.ApkUploadHandler() {
            @Override
            public java.io.File createTargetFile() {
                try {
                    return java.io.File.createTempFile("apktest", ".apk");
                } catch (Exception e) {
                    return new java.io.File(System.getProperty("java.io.tmpdir"), "apktest.apk");
                }
            }
            @Override
            public String onUploadComplete(java.io.File apk) {
                try {
                    apkUploadBytes = Files.readAllBytes(apk.toPath());
                } catch (Exception e) {
                    apkUploadBytes = new byte[0];
                }
                apk.delete();
                // 模拟盒端校验 ZIP 魔数
                if (apkUploadBytes.length >= 4
                        && apkUploadBytes[0] == 'P' && apkUploadBytes[1] == 'K'
                        && apkUploadBytes[2] == 0x03 && apkUploadBytes[3] == 0x04) {
                    apkUploadErr = null;
                } else {
                    apkUploadErr = "notapk";
                }
                return apkUploadErr;
            }
        };
        web = new WebServer(port, 0, box::dispatch, WebApiTest::asset, uploadHandler, null);
        web.start();
        Thread.sleep(150);

        tc_static();
        tc_traversal();
        tc_ping();
        tc_pair();
        tc_auth();
        tc_key();
        tc_text();
        tc_app();
        tc_apps();
        tc_icon();
        tc_touch();
        tc_cors();
        tc_body_limit();
        tc_apkurl();
        tc_apk_upload();

        web.stop();
        box.close();
        System.out.println();
        System.out.println("Web API 结果：通过 " + passed + "，失败 " + failed
                + (failed == 0 ? " —— 全部通过" : " —— 存在失败"));
        System.exit(failed == 0 ? 0 : 1);
    }

    static void tc_static() throws Exception {
        Res r = req("GET", "/", null, null);
        check("static_index", r.code == 200 && r.text().contains("TV Remote"),
                "GET / 应返回页面，实际 " + r.code);
        check("static_index_type", r.type != null && r.type.contains("text/html"),
                "index 应为 text/html，实际 " + r.type);
        check("static_index_nocache", "no-store".equals(r.cache),
                "index 不应被 HTTP 缓存，实际 " + r.cache);

        Res js = req("GET", "/app.js", null, null);
        check("static_appjs", js.code == 200 && js.text().contains("/api/key"),
                "app.js 应返回脚本，实际 " + js.code);
        check("static_appjs_nocache", "no-store".equals(js.cache),
                "app.js 不应被 HTTP 缓存，实际 " + js.cache);

        Res sw = req("GET", "/sw.js", null, null);
        check("static_sw_nocache", "no-store".equals(sw.cache),
                "sw.js 不应缓存，实际 " + sw.cache);

        Res miss = req("GET", "/nope.txt", null, null);
        check("static_unknown_404", miss.code == 404, "未知文件应 404，实际 " + miss.code);

        Res cert = req("GET", "/web.p12", null, null);
        check("static_cert_hidden", cert.code == 404, "证书不得通过 HTTP 下发，实际 " + cert.code);
    }

    static void tc_traversal() throws Exception {
        String status = raw("GET /../index.html HTTP/1.1");
        check("traversal_parent", status.startsWith("HTTP/1.1 404"),
                "/../index.html 应 404，实际 " + status);
        Res enc = req("GET", "/%2e%2e/index.html", null, null);
        check("traversal_encoded", enc.code == 404,
                "编码路径穿越应 404，实际 " + enc.code);
    }

    static void tc_ping() throws Exception {
        Res r = req("GET", "/api/ping", null, null);
        check("api_ping", r.code == 200 && r.text().contains("\"ok\":true"),
                "PING 应返回 ok，实际 " + r.code + " " + r.text());
        check("api_nocache", "no-store".equals(r.cache),
                "/api/* 不应缓存，实际 " + r.cache);
    }

    static void tc_pair() throws Exception {
        Res ok = req("POST", "/api/pair", "{\"code\":\"123456\"}", null);
        check("pair_ok", ok.code == 200 && ok.text().contains(box.token()),
                "正确配对码应返回 token，实际 " + ok.text());

        box.resetPairGuard();
        Res bad = req("POST", "/api/pair", "{\"code\":\"654321\"}", null);
        check("pair_badcode", bad.code == 200 && bad.text().contains("badcode"),
                "错误配对码应返回 badcode，实际 " + bad.text());
        box.resetPairGuard();
    }

    static void tc_auth() throws Exception {
        Res none = req("POST", "/api/key", "{\"code\":23}", null);
        check("auth_missing_401",
                none.code == 401 && none.text().contains("\"err\":\"auth\""),
                "缺少 token 应 401 auth，实际 " + none.code + " " + none.text());

        Res wrong = req("POST", "/api/key", "{\"code\":23}", "deadbeef");
        check("auth_wrong_401",
                wrong.code == 401 && wrong.text().contains("\"err\":\"auth\""),
                "错误 token 应 401 auth，实际 " + wrong.code + " " + wrong.text());
    }

    static void tc_key() throws Exception {
        Res ok = req("POST", "/api/key", "{\"code\":23}", box.token());
        check("key_ok", ok.code == 200 && ok.text().contains("\"ok\":true"),
                "KEY 23 应 OK，实际 " + ok.text());
        check("key_record", "23".equals(box.lastKeycode),
                "服务端应记录 23，实际 " + box.lastKeycode);

        Res cmd = req("POST", "/api/key", "{\"code\":99}", box.token());
        check("key_unsupported", cmd.code == 200 && cmd.text().contains("cmd"),
                "白名单外 keycode 应 ERR cmd，实际 " + cmd.text());

        Res arg = req("POST", "/api/key", "{}", box.token());
        check("key_missing_arg", arg.code == 200 && arg.text().contains("arg"),
                "缺少 code 应 ERR arg，实际 " + arg.text());
    }

    static void tc_text() throws Exception {
        Res ok = req("POST", "/api/text", "{\"text\":\"你好 \\\"TV\\\"\"}", box.token());
        check("text_ok", ok.code == 200 && ok.text().contains("\"ok\":true"),
                "TEXT 应 OK，实际 " + ok.text());
        check("text_record", "你好 \"TV\"".equals(box.lastTextProcessed),
                "服务端应收到原文本，实际 " + box.lastTextProcessed);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append('测');
        Res longRes = req("POST", "/api/text", "{\"text\":\"" + sb + "\"}", box.token());
        check("text_toolong", longRes.code == 200 && longRes.text().contains("toolong"),
                "超长文本应 toolong，实际 " + longRes.text());
    }

    static void tc_app() throws Exception {
        Res ok = req("POST", "/api/app", "{\"pkg\":\"com.example.app\"}", box.token());
        check("app_ok", ok.code == 200 && ok.text().contains("\"ok\":true"),
                "APP 应 OK，实际 " + ok.text());

        Res bad = req("POST", "/api/app", "{\"pkg\":\"bad pkg;reboot\"}", box.token());
        check("app_bad_pkg", bad.code == 200 && bad.text().contains("arg"),
                "非法包名应 ERR arg，实际 " + bad.text());
    }

    static void tc_apps() throws Exception {
        Res r = req("GET", "/api/apps", null, box.token());
        String body = r.text();
        check("apps_ok", r.code == 200 && body.contains("\"ok\":true"), "LIST 应 OK，实际 " + body);
        check("apps_pkg", body.contains("com.android.settings"), "应包含设置包名，实际 " + body);
        check("apps_name", body.contains("设置") && body.contains("示例应用"),
                "应用名应解码为 UTF-8，实际 " + body);
    }

    static void tc_icon() throws Exception {
        Res r = req("GET", "/api/icon?pkg=" + box.iconPkg, null, box.token());
        check("icon_page", r.code == 200, "ICON 应 200，实际 " + r.code);
        check("icon_type", r.type != null && r.type.startsWith("image/png"),
                "ICON 应为 image/png，实际 " + r.type);
        check("icon_bytes", r.raw != null && r.raw.length > 0,
                "ICON 应返回 PNG 字节，实际 " + (r.raw == null ? 0 : r.raw.length));

        Res miss = req("GET", "/api/icon?pkg=com.unknown", null, box.token());
        check("icon_unknown", miss.code == 200 && miss.text().contains("arg"),
                "未知包应 ERR arg，实际 " + miss.text());
    }

    /** SPEC-028/029：网页触控板双模式（绝对触摸 tapn/longn/swipen + 鼠标 move/click/tap/long/scroll） */
    static void tc_touch() throws Exception {
        box.resetTouchRecords();

        Res tap = req("POST", "/api/tapn", "{\"x\":2500,\"y\":7500}", box.token());
        check("api_tapn_ok", tap.code == 200 && tap.text().contains("\"ok\":true")
                        && box.tapnCount == 1 && box.lastTapNx == 2500 && box.lastTapNy == 7500,
                "/api/tapn 应触发 MTAPN 2500 7500，实际 " + tap.text()
                        + " " + box.lastTapNx + "/" + box.lastTapNy);

        Res tapArg = req("POST", "/api/tapn", "{\"x\":2500}", box.token());
        check("api_tapn_arg", tapArg.text().contains("arg"),
                "缺少 y 应 ERR arg，实际 " + tapArg.text());

        Res tapRange = req("POST", "/api/tapn", "{\"x\":10001,\"y\":0}", box.token());
        check("api_tapn_range", tapRange.text().contains("arg"),
                "x 越界应 ERR arg，实际 " + tapRange.text());

        Res lng = req("POST", "/api/longn", "{\"x\":6000,\"y\":4000}", box.token());
        check("api_longn_ok", lng.code == 200 && lng.text().contains("\"ok\":true")
                        && box.longnCount == 1 && box.lastLongNx == 6000
                        && box.lastLongNy == 4000,
                "/api/longn 应触发 MLONGN 6000 4000，实际 " + lng.text()
                        + " " + box.lastLongNx + "/" + box.lastLongNy);

        Res lngArg = req("POST", "/api/longn", "{}", box.token());
        check("api_longn_arg", lngArg.text().contains("arg"),
                "缺少坐标应 ERR arg，实际 " + lngArg.text());

        Res swipe = req("POST", "/api/swipen",
                "{\"x1\":5000,\"y1\":8000,\"x2\":5000,\"y2\":2000,\"ms\":300}", box.token());
        check("api_swipen_ok", swipe.code == 200 && swipe.text().contains("\"ok\":true")
                        && box.swipeCount == 1 && box.lastSwipeX1 == 5000
                        && box.lastSwipeY1 == 8000 && box.lastSwipeX2 == 5000
                        && box.lastSwipeY2 == 2000 && box.lastSwipeMs == 300,
                "/api/swipen 应触发 MSWIPEN，实际 " + swipe.text());

        Res swipeArg = req("POST", "/api/swipen",
                "{\"x1\":0,\"y1\":0,\"x2\":100,\"y2\":100}", box.token());
        check("api_swipen_arg", swipeArg.text().contains("arg"),
                "缺少 ms 应 ERR arg，实际 " + swipeArg.text());

        Res swipeMs = req("POST", "/api/swipen",
                "{\"x1\":0,\"y1\":0,\"x2\":100,\"y2\":100,\"ms\":1501}", box.token());
        check("api_swipen_ms", swipeMs.text().contains("arg"),
                "ms 越界应 ERR arg，实际 " + swipeMs.text());

        Res move = req("POST", "/api/move", "{\"dx\":5,\"dy\":-6}", box.token());
        check("api_move_ok", move.code == 200 && move.text().contains("\"ok\":true")
                        && box.moveCount == 1 && box.lastDx == 5 && box.lastDy == -6,
                "/api/move 应触发 MOVE 5 -6，实际 " + move.text()
                        + " " + box.lastDx + "/" + box.lastDy);

        Res moveArg = req("POST", "/api/move", "{\"dx\":5}", box.token());
        check("api_move_arg", moveArg.text().contains("arg"),
                "缺少 dy 应 ERR arg，实际 " + moveArg.text());

        Res moveAuth = req("POST", "/api/move", "{\"dx\":1,\"dy\":1}", null);
        check("api_move_auth", moveAuth.code == 401 && moveAuth.text().contains("auth"),
                "缺少 token 应 401 auth，实际 " + moveAuth.code);

        Res clickDown = req("POST", "/api/click", "{\"down\":true}", box.token());
        check("api_click_down", clickDown.code == 200 && box.mouseDown,
                "/api/click down=true 应按下左键，实际 " + clickDown.text());

        Res clickUp = req("POST", "/api/click", "{\"down\":false}", box.token());
        check("api_click_up", clickUp.code == 200 && !box.mouseDown,
                "/api/click down=false 应释放左键，实际 " + clickUp.text());

        Res clickArg = req("POST", "/api/click", "{}", box.token());
        check("api_click_arg", clickArg.text().contains("arg"),
                "缺少 down 应 ERR arg，实际 " + clickArg.text());

        Res mtap = req("POST", "/api/tap", "{}", box.token());
        check("api_tap_ok", mtap.code == 200 && box.tapCount == 1,
                "/api/tap 应触发 MTAP，实际 " + mtap.text() + " count=" + box.tapCount);

        Res mlong = req("POST", "/api/long", "{}", box.token());
        check("api_long_ok", mlong.code == 200 && box.longTapCount == 1,
                "/api/long 应触发 MLONG，实际 " + mlong.text()
                        + " count=" + box.longTapCount);

        Res scroll = req("POST", "/api/scroll", "{\"steps\":3}", box.token());
        check("api_scroll_ok", scroll.code == 200 && box.scrollCount == 1
                        && box.lastScroll == 3,
                "/api/scroll 应触发 SCROLL 3，实际 " + scroll.text()
                        + " steps=" + box.lastScroll);

        Res scrollArg = req("POST", "/api/scroll", "{}", box.token());
        check("api_scroll_arg", scrollArg.text().contains("arg"),
                "缺少 steps 应 ERR arg，实际 " + scrollArg.text());

        Res moveMethod = req("GET", "/api/move", null, box.token());
        check("api_move_method", moveMethod.code == 405,
                "GET /api/move 应 405，实际 " + moveMethod.code);

        Res hide = req("POST", "/api/hide", "{}", box.token());
        check("api_hide_ok", hide.code == 200 && hide.text().contains("\"ok\":true")
                        && box.hideCount == 1,
                "/api/hide 应触发 MHIDE，实际 " + hide.text() + " count=" + box.hideCount);

        Res hideAuth = req("POST", "/api/hide", "{}", null);
        check("api_hide_auth", hideAuth.code == 401 && hideAuth.text().contains("auth"),
                "缺少 token 应 401 auth，实际 " + hideAuth.code + " " + hideAuth.text());

        Res hideMethod = req("GET", "/api/hide", null, box.token());
        check("api_hide_method", hideMethod.code == 405,
                "GET /api/hide 应 405，实际 " + hideMethod.code);

        Res auth = req("POST", "/api/tapn", "{\"x\":0,\"y\":0}", null);
        check("api_tapn_auth", auth.code == 401 && auth.text().contains("auth"),
                "缺少 token 应 401 auth，实际 " + auth.code + " " + auth.text());

        Res method = req("GET", "/api/tapn", null, box.token());
        check("api_tapn_method", method.code == 405,
                "GET /api/tapn 应 405，实际 " + method.code);
    }

    /** SPEC-025：跨源访问与 OPTIONS 预检 */
    static void tc_cors() throws Exception {
        Res pre = req("OPTIONS", "/api/key", null, null, new String[]{
                "Origin", "http://192.168.3.99:17880",
                "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "x-token,content-type"});
        check("cors_preflight_204", pre.code == 204,
                "OPTIONS 预检应 204，实际 " + pre.code);
        check("cors_allow_origin", "*".equals(pre.acao),
                "应返回 Access-Control-Allow-Origin: *，实际 " + pre.acao);
        check("cors_allow_methods", pre.acam != null && pre.acam.contains("POST"),
                "应允许 POST，实际 " + pre.acam);
        check("cors_allow_headers", pre.acah != null
                        && pre.acah.toLowerCase().contains("x-token"),
                "应允许 X-Token 头，实际 " + pre.acah);
        check("cors_preflight_no_token", pre.code != 401,
                "预检不得要求 token，实际 " + pre.code);

        Res ping = req("GET", "/api/ping", null, null);
        check("cors_response_header", "*".equals(ping.acao),
                "普通响应也应带 CORS 头，实际 " + ping.acao);
    }

    static void tc_body_limit() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 70000; i++) sb.append('a');
        try {
            Res r = req("POST", "/api/text", "{\"text\":\"" + sb + "\"}", box.token());
            check("body_limit", r.code == 413, "超过 64KB 应 413，实际 " + r.code);
        } catch (Exception e) {
            // 服务端提前关闭连接也视为拒绝
            record("body_limit", true, "超大请求被拒绝（连接关闭）");
        }
    }

    // ---------------- SPEC-040 APK 推送安装 ----------------

    static void tc_apkurl() throws Exception {
        // 缺少 token -> 401
        Res noTok = req("POST", "/api/apkurl", "{\"url\":\"https://x/a.apk\"}", null);
        check("apkurl_no_token", noTok.code == 401, "无 token 应 401，实际 " + noTok.code);

        // 正常链接 -> OK
        box.resetPairGuard();
        Res ok = req("POST", "/api/apkurl",
                "{\"url\":\"https://example.com/app.apk\"}", box.token());
        check("apkurl_ok", ok.code == 200 && ok.text().contains("\"ok\":true"),
                "有效链接应返回 ok，实际 " + ok.text());

        // 非法 scheme -> ERR arg
        Res bad = req("POST", "/api/apkurl",
                "{\"url\":\"ftp://example.com/a.apk\"}", box.token());
        check("apkurl_bad_scheme", bad.code == 200 && bad.text().contains("\"arg\""),
                "非法 scheme 应返回 arg，实际 " + bad.text());

        // 缺少 url 字段 -> ERR arg
        Res miss = req("POST", "/api/apkurl", "{}", box.token());
        check("apkurl_missing_url", miss.code == 200 && miss.text().contains("\"arg\""),
                "缺 url 应返回 arg，实际 " + miss.text());
    }

    static void tc_apk_upload() throws Exception {
        // 缺少 token -> 401
        Res noTok = reqBytes("POST", "/api/apk-upload",
                new byte[]{'P', 'K', 0x03, 0x04, 0, 0}, null);
        check("apk_upload_no_token", noTok.code == 401,
                "无 token 应 401，实际 " + noTok.code);

        // 合法 APK（ZIP 魔数）-> ok
        byte[] apk = new byte[]{'P', 'K', 0x03, 0x04, 1, 2, 3, 4};
        Res ok = reqBytes("POST", "/api/apk-upload", apk, box.token());
        check("apk_upload_ok", ok.code == 200 && ok.text().contains("\"ok\":true"),
                "合法 APK 应返回 ok，实际 " + ok.text());
        check("apk_upload_bytes", apkUploadBytes != null
                && java.util.Arrays.equals(apk, apkUploadBytes),
                "上传字节应与盒端收到的一致");

        // 非 APK 文件 -> notapk
        byte[] txt = "hello world".getBytes(StandardCharsets.UTF_8);
        Res bad = reqBytes("POST", "/api/apk-upload", txt, box.token());
        check("apk_upload_not_apk", bad.code == 200 && bad.text().contains("notapk"),
                "非 APK 应返回 notapk，实际 " + bad.text());
    }

    /** SPEC-040：发送原始字节体（不走 JSON），用于 APK 上传测试 */
    static Res reqBytes(String method, String path, byte[] body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(2000);
        c.setReadTimeout(5000);
        c.setInstanceFollowRedirects(false);
        if (token != null) c.setRequestProperty("X-Token", token);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] data = readAll(in);
        return new Res(code, c.getContentType(), data, c.getHeaderField("Cache-Control"),
                c.getHeaderField("Access-Control-Allow-Origin"),
                c.getHeaderField("Access-Control-Allow-Methods"),
                c.getHeaderField("Access-Control-Allow-Headers"));
    }

    // ---------------- 基础工具 ----------------

    static byte[] asset(String name) {
        try {
            return Files.readAllBytes(Paths.get("..", "..", "server", "assets", "web", name));
        } catch (Exception e) {
            return null;
        }
    }

    static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static class Res {
        int code;
        String type;
        byte[] raw;
        String cache;
        String acao;
        String acam;
        String acah;

        Res(int code, String type, byte[] raw, String cache,
            String acao, String acam, String acah) {
            this.code = code;
            this.type = type;
            this.raw = raw;
            this.cache = cache;
            this.acao = acao;
            this.acam = acam;
            this.acah = acah;
        }

        String text() {
            return raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
        }
    }

    static Res req(String method, String path, String body, String token) throws Exception {
        return req(method, path, body, token, null);
    }

    static Res req(String method, String path, String body, String token,
                   String[] extraHeaders) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(2000);
        c.setReadTimeout(5000);
        c.setInstanceFollowRedirects(false);
        if (token != null) c.setRequestProperty("X-Token", token);
        if (extraHeaders != null) {
            for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
                c.setRequestProperty(extraHeaders[i], extraHeaders[i + 1]);
            }
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] data = readAll(in);
        return new Res(code, c.getContentType(), data, c.getHeaderField("Cache-Control"),
                c.getHeaderField("Access-Control-Allow-Origin"),
                c.getHeaderField("Access-Control-Allow-Methods"),
                c.getHeaderField("Access-Control-Allow-Headers"));
    }

    static String raw(String requestLine) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            s.setSoTimeout(3000);
            OutputStream out = s.getOutputStream();
            out.write((requestLine + "\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            return new String(readAll(s.getInputStream()), StandardCharsets.UTF_8);
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + " — " + detail);
        }
    }

    static void record(String name, boolean ok, String detail) {
        check(name, ok, detail);
    }
}
