package com.koco.tvremoteserver;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLServerSocketFactory;

/**
 * 盒端内置 Web 遥控服务（SPEC-020）。
 *
 * 纯 Java 实现，不依赖 Android API：
 *   - 监听 HTTP 17880 与 HTTPS 17843（证书由 build.py 生成并随 assets 打包）；
 *   - 静态页从注入的 ResourceProvider 读取，运行时由 AssetManager 提供；
 *   - 所有命令交给注入的 CommandHandler，复用 ServerService.dispatch，
 *     不复制协议解析（SPEC-020 FR-20-5）。
 *
 * SPEC-025：网页可在页面所在的盒子上切换控制其它盒子，因此允许跨源访问；
 * 鉴权仍由 token 与配对失败锁定保证。
 *
 * 每个请求独立处理并关闭连接，不维护会话；配对状态只由 token 体现。
 */
public class WebServer {

    /** 命令处理器：line 为协议原文，peerIp 用于配对失败锁定 */
    public interface CommandHandler {
        String handle(String line, String peerIp);
    }

    /** 静态资源提供者：name 为 assets/web 下的文件名，找不到返回 null */
    public interface ResourceProvider {
        byte[] read(String name);
    }

    /** HTTP 请求体上限 64KB（SPEC-020 NFR-20-4） */
    private static final int MAX_BODY = 64 * 1024;
    /** 请求行/请求头单行上限 */
    private static final int MAX_LINE = 8192;
    private static final int SOCKET_TIMEOUT_MS = 10000;

    /** 静态文件白名单：只允许页面所需的固定文件，未列出的一律 404 */
    private static final Set<String> STATIC_FILES = new HashSet<>(Arrays.asList(
            "index.html", "app.js", "style.css",
            "manifest.webmanifest", "sw.js",
            "icon-192.png", "icon-512.png"));

    private static final Map<String, String> MIME = new HashMap<>();

    static {
        MIME.put("index.html", "text/html; charset=utf-8");
        MIME.put("app.js", "application/javascript; charset=utf-8");
        MIME.put("style.css", "text/css; charset=utf-8");
        MIME.put("manifest.webmanifest", "application/manifest+json; charset=utf-8");
        MIME.put("sw.js", "application/javascript; charset=utf-8");
        MIME.put("icon-192.png", "image/png");
        MIME.put("icon-512.png", "image/png");
    }

    private final int httpPort;
    private final int httpsPort;
    private final CommandHandler commands;
    private final ResourceProvider resources;
    private final SSLServerSocketFactory tlsFactory;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket httpSocket;
    private ServerSocket httpsSocket;
    private volatile boolean running = true;

    public WebServer(int httpPort, int httpsPort, CommandHandler commands,
                     ResourceProvider resources, SSLServerSocketFactory tlsFactory) {
        this.httpPort = httpPort;
        this.httpsPort = httpsPort;
        this.commands = commands;
        this.resources = resources;
        this.tlsFactory = tlsFactory;
    }

    /** 启动两个监听端口；任一端口失败只记录，不影响另一个 */
    public void start() {
        httpSocket = bind(null, httpPort);
        if (tlsFactory != null) httpsSocket = bind(tlsFactory, httpsPort);
        if (httpSocket != null) acceptLoop(httpSocket, "web-http");
        if (httpsSocket != null) acceptLoop(httpsSocket, "web-https");
    }

    private ServerSocket bind(SSLServerSocketFactory factory, int port) {
        try {
            ServerSocket s = factory == null ? new ServerSocket() : factory.createServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port));
            log("listening on " + port + (factory == null ? " (http)" : " (https)"));
            return s;
        } catch (Exception e) {
            log("bind " + port + " failed: " + e.getMessage());
            return null;
        }
    }

    private void acceptLoop(final ServerSocket ss, final String name) {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    final Socket s = ss.accept();
                    pool.submit(() -> handle(s));
                } catch (Exception e) {
                    if (running) log(name + " accept: " + e.getMessage());
                    break;
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        close(httpSocket);
        close(httpsSocket);
        pool.shutdownNow();
    }

    private static void close(ServerSocket s) {
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static void log(String msg) {
        System.out.println("[TvRemoteWeb] " + msg);
    }

    // ---------------- 请求处理 ----------------

    private void handle(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;

            Map<String, String> headers = new HashMap<>();
            String line;
            int headerBytes = 0;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                headerBytes += line.length();
                if (headerBytes > MAX_BODY) return;
                int c = line.indexOf(':');
                if (c > 0) {
                    headers.put(line.substring(0, c).trim().toLowerCase(Locale.US),
                            line.substring(c + 1).trim());
                }
            }

            int len = intHeader(headers.get("content-length"));
            if (len > MAX_BODY) {
                write(s, Response.json(413, "{\"ok\":false,\"err\":\"toolong\"}", false));
                return;
            }
            byte[] body = readBody(in, len);

            String ip = s.getInetAddress() == null
                    ? "unknown" : s.getInetAddress().getHostAddress();
            Response resp = route(requestLine, headers, body, ip);
            write(s, resp);
        } catch (Exception e) {
            log("request: " + e.getMessage());
        }
    }

    private Response route(String requestLine, Map<String, String> headers,
                           byte[] body, String ip) {
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return Response.text(400, "Bad Request");
        String method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];

        String path = target;
        String query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }

        // SPEC-025：跨源预检，不要求 token，也不读取请求体
        if (method.equals("OPTIONS")) return Response.empty(204);

        if (path.startsWith("/api/")) return api(method, path, query, headers, body, ip);
        if (!method.equals("GET")) return Response.text(405, "Method Not Allowed");

        String name = path.equals("/") ? "index.html" : path.substring(1);
        // 白名单精确匹配：/../、编码变体、未知文件都会落到 404
        if (!STATIC_FILES.contains(name)) return Response.text(404, "Not Found");
        byte[] data = resources.read(name);
        if (data == null) return Response.text(404, "Not Found");
        // 外壳文件不允许 HTTP 缓存：升级后不会继续加载旧页面（SW 仍可离线缓存）
        boolean shell = name.endsWith(".html") || name.endsWith(".js")
                || name.endsWith(".css") || name.endsWith(".webmanifest");
        boolean noCache = name.equals("sw.js") || shell;
        return Response.bytes(200, MIME.get(name), data, noCache);
    }

    private Response api(String method, String path, String query,
                         Map<String, String> headers, byte[] body, String ip) {
        try {
            if (path.equals("/api/ping")) {
                return commands.handle("PING", ip).equals("PONG")
                        ? Response.json(200, "{\"ok\":true}", true)
                        : Response.json(200, err("protocol"), true);
            }

            boolean post = method.equals("POST");
            // 除 pair/ping 外都必须带 token；缺失直接 401
            String token = headers.get("x-token");

            if (path.equals("/api/pair")) {
                if (!post) return Response.text(405, "Method Not Allowed");
                String code = jsonString(body, "code");
                if (code == null) return Response.json(200, err("arg"), true);
                return pair(commands.handle("PAIR " + code, ip));
            }

            if (token == null || token.isEmpty()) {
                return Response.json(401, err("auth"), true);
            }

            if (path.equals("/api/key") || path.equals("/api/keylong")) {
                if (!post) return Response.text(405, "Method Not Allowed");
                Integer code = jsonInt(body, "code");
                if (code == null) return Response.json(200, err("arg"), true);
                String cmd = (path.equals("/api/key") ? "KEY " : "KEYLONG ")
                        + token + " " + code;
                return plain(commands.handle(cmd, ip));
            }

            if (path.equals("/api/text")) {
                if (!post) return Response.text(405, "Method Not Allowed");
                String text = jsonString(body, "text");
                if (text == null) return Response.json(200, err("arg"), true);
                String b64 = Base64.getEncoder().encodeToString(
                        text.getBytes(StandardCharsets.UTF_8));
                return plain(commands.handle("TEXT " + token + " " + b64, ip));
            }

            if (path.equals("/api/app")) {
                if (!post) return Response.text(405, "Method Not Allowed");
                String pkg = jsonString(body, "pkg");
                if (pkg == null) return Response.json(200, err("arg"), true);
                return plain(commands.handle("APP " + token + " " + pkg, ip));
            }

            if (path.equals("/api/move")) {
                if (!post) return Response.text(405, "Method Not Allowed");
                Integer dx = jsonInt(body, "dx");
                Integer dy = jsonInt(body, "dy");
                if (dx == null || dy == null) return Response.json(200, err("arg"), true);
                return plain(commands.handle("MOVE " + token + " " + dx + " " + dy, ip));
            }

            if (path.equals("/api/click")) {
                if (!post) return Response.text(405, "Method Not Allowed");
                Boolean down = jsonBool(body, "down");
                if (down == null) return Response.json(200, err("arg"), true);
                return plain(commands.handle("MCLICK " + token + " " + (down ? 1 : 0), ip));
            }

            // SPEC-025：网页触控板，复用 SPEC-023 的盒端命令
            if (path.equals("/api/tap")) {
                if (!post) return Response.text(405, "Method Not Allowed");
                return plain(commands.handle("MTAP " + token, ip));
            }

            if (path.equals("/api/long")) {
                if (!post) return Response.text(405, "Method Not Allowed");
                return plain(commands.handle("MLONG " + token, ip));
            }

            if (path.equals("/api/hide")) {
                if (!post) return Response.text(405, "Method Not Allowed");
                return plain(commands.handle("MHIDE " + token, ip));
            }

            if (path.equals("/api/scroll")) {
                if (!post) return Response.text(405, "Method Not Allowed");
                Integer steps = jsonInt(body, "steps");
                if (steps == null) return Response.json(200, err("arg"), true);
                return plain(commands.handle("SCROLL " + token + " " + steps, ip));
            }

            if (path.equals("/api/apps")) {
                if (!method.equals("GET")) return Response.text(405, "Method Not Allowed");
                return apps(commands.handle("LIST " + token, ip));
            }

            if (path.equals("/api/icon")) {
                if (!method.equals("GET")) return Response.text(405, "Method Not Allowed");
                String pkg = queryValue(query, "pkg");
                if (pkg == null || pkg.isEmpty()) return Response.json(200, err("arg"), true);
                return icon(commands.handle("ICON " + token + " " + pkg, ip));
            }

            return Response.text(404, "Not Found");
        } catch (Exception e) {
            return Response.json(200, err("protocol"), true);
        }
    }

    private Response pair(String resp) {
        if (resp.startsWith("OK ")) {
            String token = resp.substring(3).trim();
            return Response.json(200, "{\"ok\":true,\"token\":\"" + esc(token) + "\"}", true);
        }
        return plain(resp);
    }

    /** 普通命令：OK -> {"ok":true}；ERR auth -> 401；其余 ERR -> 200 原样错误码 */
    private Response plain(String resp) {
        if (resp.equals("OK")) return Response.json(200, "{\"ok\":true}", true);
        if (resp.startsWith("ERR ")) {
            String code = resp.substring(4).trim();
            int status = code.equals("auth") ? 401 : 200;
            return Response.json(status, err(code), true);
        }
        return Response.json(200, err("protocol"), true);
    }

    /** LIST 载荷「包名|base64(应用名)」逗号分隔 -> JSON 数组 */
    private Response apps(String resp) {
        if (!resp.startsWith("OK")) return plain(resp);
        String payload = resp.substring(2).trim();
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"apps\":[");
        boolean first = true;
        if (!payload.isEmpty()) {
            for (String item : payload.split(",")) {
                String[] p = item.split("\\|", 2);
                if (p.length < 1 || p[0].isEmpty()) continue;
                String name = p[0];
                if (p.length > 1) {
                    try {
                        name = new String(Base64.getDecoder().decode(p[1]),
                                StandardCharsets.UTF_8);
                    } catch (Exception ignored) {
                        name = p[0];
                    }
                }
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"pkg\":\"").append(esc(p[0]))
                        .append("\",\"name\":\"").append(esc(name)).append("\"}");
            }
        }
        sb.append("]}");
        return Response.json(200, sb.toString(), true);
    }

    /** ICON 的 Base64 解码为 PNG 字节流 */
    private Response icon(String resp) {
        if (!resp.startsWith("OK ")) return plain(resp);
        try {
            byte[] png = Base64.getDecoder().decode(resp.substring(3).trim());
            return Response.bytes(200, "image/png", png, true);
        } catch (Exception e) {
            return Response.json(200, err("protocol"), true);
        }
    }

    // ---------------- HTTP 基础 ----------------

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b = in.read();
        if (b == -1) return null;
        while (b != -1 && b != '\n') {
            if (b != '\r') bos.write(b);
            if (bos.size() > MAX_LINE) throw new IOException("line too long");
            b = in.read();
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] readBody(InputStream in, int len) throws IOException {
        if (len <= 0) return new byte[0];
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) break;
            off += n;
        }
        return off == len ? buf : Arrays.copyOf(buf, off);
    }

    private static int intHeader(String v) {
        if (v == null) return 0;
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static void write(Socket s, Response r) throws IOException {
        OutputStream out = s.getOutputStream();
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(r.status).append(' ').append(statusText(r.status))
                .append("\r\nContent-Type: ").append(r.type)
                .append("\r\nContent-Length: ").append(r.body.length)
                .append("\r\nConnection: close")
                .append("\r\nX-Content-Type-Options: nosniff")
                .append("\r\nCache-Control: ").append(r.noCache ? "no-store" : "max-age=3600")
                // SPEC-025：允许页面跨源控制其它盒子（token 仍是唯一凭证）
                .append("\r\nAccess-Control-Allow-Origin: *")
                .append("\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS")
                .append("\r\nAccess-Control-Allow-Headers: X-Token, Content-Type")
                .append("\r\nAccess-Control-Allow-Private-Network: true")
                .append("\r\nAccess-Control-Max-Age: 600")
                .append("\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(r.body);
        out.flush();
    }

    private static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            default: return "Bad Request";
        }
    }

    private static final class Response {
        final int status;
        final String type;
        final byte[] body;
        final boolean noCache;

        Response(int status, String type, byte[] body, boolean noCache) {
            this.status = status;
            this.type = type;
            this.body = body == null ? new byte[0] : body;
            this.noCache = noCache;
        }

        static Response json(int status, String body, boolean noCache) {
            return new Response(status, "application/json; charset=utf-8",
                    body.getBytes(StandardCharsets.UTF_8), noCache);
        }

        static Response empty(int status) {
            return new Response(status, "text/plain; charset=utf-8", new byte[0], true);
        }

        static Response bytes(int status, String type, byte[] body, boolean noCache) {
            return new Response(status, type, body, noCache);
        }

        static Response text(int status, String body) {
            return new Response(status, "text/plain; charset=utf-8",
                    body.getBytes(StandardCharsets.UTF_8), false);
        }
    }

    // ---------------- JSON 辅助 ----------------

    private static String err(String code) {
        return "{\"ok\":false,\"err\":\"" + esc(code) + "\"}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String bodyText(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String jsonString(byte[] body, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key)
                        + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(bodyText(body));
        return m.find() ? unescape(m.group(1)) : null;
    }

    private static Integer jsonInt(byte[] body, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)")
                .matcher(bodyText(body));
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group(1));
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean jsonBool(byte[] body, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)")
                .matcher(bodyText(body));
        if (!m.find()) return null;
        return Boolean.valueOf(m.group(1));
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (Exception e) {
                            sb.append("\\u");
                        }
                    }
                    break;
                default: sb.append(n);
            }
        }
        return sb.toString();
    }

    private static String queryValue(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            if (!kv.substring(0, eq).equals(key)) continue;
            try {
                return URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
