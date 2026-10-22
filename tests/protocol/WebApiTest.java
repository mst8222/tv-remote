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

    public static void main(String[] args) throws Exception {
        box = new MockBoxServer("123456");
        box.packages = "com.android.settings|"
                + b64("设置") + ",com.example.app|" + b64("示例应用");
        port = freePort();
        web = new WebServer(port, 0, box::dispatch, WebApiTest::asset, null);
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
        tc_move_click();
        tc_touch();
        tc_cors();
        tc_body_limit();

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

    static void tc_move_click() throws Exception {
        Res move = req("POST", "/api/move", "{\"dx\":5,\"dy\":-6}", box.token());
        check("move_ok", move.code == 200 && move.text().contains("\"ok\":true"),
                "MOVE 应 OK，实际 " + move.text());
        check("move_record", box.lastDx == 5 && box.lastDy == -6,
                "服务端应记录 5/-6，实际 " + box.lastDx + "/" + box.lastDy);

        Res range = req("POST", "/api/move", "{\"dx\":128,\"dy\":0}", box.token());
        check("move_range", range.code == 200 && range.text().contains("arg"),
                "越界位移应 ERR arg，实际 " + range.text());

        Res down = req("POST", "/api/click", "{\"down\":true}", box.token());
        check("click_down", down.text().contains("\"ok\":true") && box.mouseDown,
                "按下应 OK 并记录状态，实际 " + down.text());
        Res up = req("POST", "/api/click", "{\"down\":false}", box.token());
        check("click_up", up.text().contains("\"ok\":true") && !box.mouseDown,
                "抬起应 OK 并清除状态，实际 " + up.text());

        Res bad = req("POST", "/api/click", "{\"down\":\"x\"}", box.token());
        check("click_arg", bad.text().contains("arg"), "非法 down 应 ERR arg，实际 " + bad.text());
    }

    /** SPEC-025：网页触控板复用 MTAP/MLONG/SCROLL */
    static void tc_touch() throws Exception {
        box.resetTouchRecords();

        Res tap = req("POST", "/api/tap", "{}", box.token());
        check("api_tap_ok", tap.code == 200 && tap.text().contains("\"ok\":true")
                        && box.tapCount == 1,
                "/api/tap 应触发 MTAP，实际 " + tap.text() + " count=" + box.tapCount);

        Res lng = req("POST", "/api/long", "{}", box.token());
        check("api_long_ok", lng.code == 200 && lng.text().contains("\"ok\":true")
                        && box.longTapCount == 1,
                "/api/long 应触发 MLONG，实际 " + lng.text() + " count=" + box.longTapCount);

        Res scroll = req("POST", "/api/scroll", "{\"steps\":3}", box.token());
        check("api_scroll_ok", scroll.code == 200 && scroll.text().contains("\"ok\":true")
                        && box.scrollCount == 1 && box.lastScroll == 3,
                "/api/scroll 应触发 SCROLL 3，实际 " + scroll.text()
                        + " steps=" + box.lastScroll);

        Res miss = req("POST", "/api/scroll", "{}", box.token());
        check("api_scroll_arg", miss.text().contains("arg"),
                "缺少 steps 应 ERR arg，实际 " + miss.text());

        Res range = req("POST", "/api/scroll", "{\"steps\":6}", box.token());
        check("api_scroll_range", range.text().contains("arg"),
                "steps 越界应 ERR arg，实际 " + range.text());

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

        Res auth = req("POST", "/api/tap", "{}", null);
        check("api_tap_auth", auth.code == 401 && auth.text().contains("auth"),
                "缺少 token 应 401 auth，实际 " + auth.code + " " + auth.text());

        Res method = req("GET", "/api/tap", null, box.token());
        check("api_tap_method", method.code == 405,
                "GET /api/tap 应 405，实际 " + method.code);
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
