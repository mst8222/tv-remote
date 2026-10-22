import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 直连协议的盒子端参照实现（对应 spec/implemented/SPEC-001-lan-direct.md）。
 *
 * 从 server/src/com/koco/tvremoteserver/ServerService.java 的 dispatch 逻辑
 * 迁移改写为纯 Java，便于在 JVM 上做协议一致性测试：
 *   SharedPreferences -> 内存字段
 *   android.util.Base64 -> java.util.Base64
 *   无障碍 / AudioManager / PackageManager 注入 -> 按语义模拟（记录参数、按规则判定成败）
 *
 * 约束：dispatch 的行为必须与真实盒子端逐行对应；
 * 盒子端协议行为变化时，本文件必须同步修改（先改 spec，再同步两端）。
 */
public class MockBoxServer implements AutoCloseable {

    private final ServerSocket server;
    private final List<Socket> clients = new ArrayList<>();
    private volatile String pairCode;
    private final String token;

    // 最近一次命令的语义记录（供测试断言）
    public volatile String lastKeycode;
    /** SPEC-018：最近一次长按命令的 keycode */
    public volatile String lastLongKeycode;
    public volatile String lastTextRaw;       // base64 解码后的原始文本
    public volatile String lastTextProcessed; // ACTION_SET_TEXT 原样写入的文本
    public volatile String lastPkg;
    public volatile String launchVia;         // "action" 或 "intent"
    /** SPEC-014：无障碍服务是否已开启（模拟盒子端状态） */
    public volatile boolean a11yEnabled = true;

    public MockBoxServer(String pairCode) throws Exception {
        server = new ServerSocket(0);
        this.pairCode = pairCode;
        // 与盒子端 randomToken 一致：16 字节安全随机 -> 32 位小写 hex
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        token = sb.toString();
        Thread t = new Thread(this::serve, "mock-box-accept");
        t.setDaemon(true);
        t.start();
    }

    public int port() { return server.getLocalPort(); }

    public String token() { return token; }

    public void setPairCode(String code) { this.pairCode = code; }

    /** SPEC-016：LIST 载荷「包名|base64(应用名)」逗号分隔；空串表示盒子无第三方应用 */
    public volatile String packages = "";

    /** SPEC-019：ICON 只对 iconPkg 返回 iconBase64（1x1 PNG），其余包名返回 ERR arg */
    public volatile String iconPkg = "com.example.app";
    public volatile String iconBase64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    // SPEC-021：最近一次飞鼠位移与左键状态
    public volatile int lastDx;
    public volatile int lastDy;
    public volatile boolean mouseDown;

    // SPEC-023：触控板命令记录
    public volatile int moveCount;
    public volatile int tapCount;
    public volatile int longTapCount;
    public volatile int scrollCount;
    public volatile int hideCount;
    public volatile int lastScroll;
    public volatile int connectionCount;

    /** 测试辅助：清空触控板命令记录，避免用例之间互相影响 */
    public void resetTouchRecords() {
        moveCount = 0;
        tapCount = 0;
        longTapCount = 0;
        scrollCount = 0;
        hideCount = 0;
        lastScroll = 0;
        lastDx = 0;
        lastDy = 0;
        mouseDown = false;
        connectionCount = 0;
    }

    // SPEC-006：同一 IP 连续配对失败 5 次锁 5 分钟
    private static final int PAIR_MAX_FAILS = 5;
    private static final long PAIR_LOCK_MS = 5 * 60 * 1000L;
    private final Map<String, Integer> pairFails = new HashMap<>();
    private final Map<String, Long> pairLockUntil = new HashMap<>();

    private void serve() {
        try {
            while (!server.isClosed()) {
                Socket s = server.accept();
                synchronized (clients) { clients.add(s); }
                connectionCount++;
                Thread h = new Thread(() -> handle(s), "mock-box-conn");
                h.setDaemon(true);
                h.start();
            }
        } catch (Exception ignored) {
            // close() 后 accept 抛异常属正常退出
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(sock.getOutputStream(), true)) {
            sock.setSoTimeout(30000);
            String line;
            while ((line = in.readLine()) != null) {
                String resp = dispatch(line.trim());
                out.println(resp);
                if (resp.startsWith("ERR bye")) break;
            }
        } catch (Exception ignored) {
        }
    }

    /** 与 ServerService.dispatch 保持逐行对应 */
    public String dispatch(String line) {
        return dispatch(line, "127.0.0.1");
    }

    /** SPEC-006：peerIp 用于配对失败锁定；TCP 与 Web 两条入口语义一致 */
    public String dispatch(String line, String peerIp) {
        try {
            if (line.equals("PING")) return "PONG";

            String[] p = line.split(" ", 3);
            if (p.length == 0) return "ERR empty";

            if (p[0].equals("PAIR")) {
                if (p.length < 2) return "ERR arg";
                if (isPairLocked(peerIp)) return "ERR locked";
                if (p[1].equals(pairCode)) {
                    pairFails.remove(peerIp);
                    pairLockUntil.remove(peerIp);
                    return "OK " + token;
                }
                notePairFail(peerIp);
                return "ERR badcode";
            }

            // 其余命令必须带 token
            if (p.length < 2 || !p[1].equals(token)) return "ERR auth";

            switch (p[0]) {
                case "KEY": {
                    if (p.length < 3) return "ERR arg";
                    int code;
                    try {
                        code = Integer.parseInt(p[2].trim());
                    } catch (NumberFormatException e) {
                        return "ERR arg";
                    }
                    return keyevent(code);
                }
                case "KEYLONG": {
                    // SPEC-018：长按，仅 OK（23）支持
                    if (p.length < 3) return "ERR arg";
                    int code;
                    try {
                        code = Integer.parseInt(p[2].trim());
                    } catch (NumberFormatException e) {
                        return "ERR arg";
                    }
                    return keyLong(code);
                }
                case "MOVE": {
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
                    if (!a11yEnabled) return "ERR a11y";
                    lastDx = dx;
                    lastDy = dy;
                    moveCount++;
                    return "OK";
                }
                case "MTAP": {
                    // SPEC-023：光标处单击，节点优先、手势必兜底；无参数
                    if (!a11yEnabled) return "ERR a11y";
                    tapCount++;
                    return "OK";
                }
                case "MLONG": {
                    // SPEC-023：光标处长按，节点优先、手势兜底；无参数
                    if (!a11yEnabled) return "ERR a11y";
                    longTapCount++;
                    return "OK";
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
                    if (!a11yEnabled) return "ERR a11y";
                    lastScroll = steps;
                    scrollCount++;
                    return "OK";
                }
                case "MCLICK": {
                    if (p.length < 3) return "ERR arg";
                    String st = p[2].trim();
                    if (!st.equals("0") && !st.equals("1")) return "ERR arg";
                    if (!a11yEnabled) return "ERR a11y";
                    mouseDown = st.equals("1");
                    return "OK";
                }
                case "MHIDE": {
                    // SPEC-023：退出触控板立即隐藏光标
                    if (p.length > 2) return "ERR arg";
                    if (!a11yEnabled) return "ERR a11y";
                    hideCount++;
                    return "OK";
                }
                case "TEXT": {
                    if (p.length < 3) return "ERR arg";
                    if (p[2].length() > 2048) return "ERR toolong";
                    String txt = new String(Base64.getDecoder().decode(p[2]), StandardCharsets.UTF_8);
                    if (txt.codePointCount(0, txt.length()) > 256) return "ERR toolong";
                    return text(txt);
                }
                case "APP":
                    if (p.length < 3) return "ERR arg";
                    if (!validPkg(p[2])) return "ERR arg";
                    return launch(p[2]) ? "OK" : "ERR exec";
                case "LIST":
                    // SPEC-016：返回可配置的「包名|base64(应用名)」载荷，与盒子端语义一致
                    return "OK " + packages;
                case "ICON":
                    // SPEC-019：按包名返回模拟图标，未知包与盒子端一致返回 ERR arg
                    if (p.length < 3) return "ERR arg";
                    if (iconBase64 == null || !p[2].trim().equals(iconPkg)) return "ERR arg";
                    return "OK " + iconBase64;
                default:
                    return "ERR cmd";
            }
        } catch (Exception e) {
            return "ERR " + e.getClass().getSimpleName();
        }
    }

    /** SPEC-006：包名白名单字符与长度 */
    private static boolean validPkg(String pkg) {
        return pkg.length() <= 128 && pkg.matches("[A-Za-z0-9._]+");
    }

    private boolean isPairLocked(String ip) {
        Long until = pairLockUntil.get(ip);
        if (until == null) return false;
        if (System.currentTimeMillis() < until) return true;
        pairLockUntil.remove(ip);
        pairFails.remove(ip);
        return false;
    }

    private void notePairFail(String ip) {
        int n = pairFails.containsKey(ip) ? pairFails.get(ip) : 0;
        n++;
        pairFails.put(ip, n);
        if (n >= PAIR_MAX_FAILS) pairLockUntil.put(ip, System.currentTimeMillis() + PAIR_LOCK_MS);
    }

    /** 测试辅助：清空配对失败计数，避免用例之间互相影响 */
    public void resetPairGuard() {
        pairFails.clear();
        pairLockUntil.clear();
    }

    /**
     * 模拟无障碍注入：音量/静音走 AudioManager 不依赖无障碍；
     * 其余受支持 keycode 需要无障碍已开启；未知 keycode 返回 ERR cmd。
     */
    private String keyevent(int code) {
        lastKeycode = String.valueOf(code);
        if (code == 24 || code == 25 || code == 164) return "OK";
        if (!a11yEnabled) return "ERR a11y";
        switch (code) {
            case 3: case 4: case 26: case 82:
            case 19: case 20: case 21: case 22: case 23:
                return "OK";
            default:
                return "ERR cmd";
        }
    }

    /** SPEC-018：模拟长按注入（ACTION_LONG_CLICK）：只支持 OK（23） */
    private String keyLong(int code) {
        lastLongKeycode = String.valueOf(code);
        if (code != 23) return "ERR cmd";
        if (!a11yEnabled) return "ERR a11y";
        return "OK";
    }

    /** 模拟无障碍 ACTION_SET_TEXT：原样写入，不转义；无障碍关闭时不可用 */
    private String text(String t) {
        if (!a11yEnabled) return "ERR a11y";
        lastTextRaw = t;
        lastTextProcessed = t;
        return "OK";
    }

    /** 模拟 PackageManager 启动：Settings 特判走 action；合法包名走 LAUNCHER 意图 */
    private boolean launch(String pkg) {
        lastPkg = pkg;
        if (!pkg.matches("[A-Za-z0-9._]+")) return false;
        launchVia = pkg.equals("com.android.settings") ? "action" : "intent";
        return true;
    }

    /** 向指定地址发送一次发现广播（测试用单播代替全网段广播，行为等价） */
    public void broadcastOnce(String host, int discoveryPort) {
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket();
            String msg = "TVREMOTE|" + port() + "|TestBox";
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            ds.send(new DatagramPacket(data, data.length,
                    InetAddress.getByName(host), discoveryPort));
        } catch (Exception ignored) {
        } finally {
            if (ds != null) ds.close();
        }
    }

    /** 服务端主动断开所有客户端连接（模拟盒子重启/服务被杀后重连场景） */
    public void kickAll() {
        synchronized (clients) {
            for (Socket s : clients) {
                try { s.close(); } catch (Exception ignored) {}
            }
            clients.clear();
        }
    }

    @Override
    public void close() {
        kickAll();
        try { server.close(); } catch (Exception ignored) {}
    }
}
