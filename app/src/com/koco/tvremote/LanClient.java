package com.koco.tvremote;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 直连盒子端「TV遥控服务」的客户端。
 * 不走 ADB，不需要开发者选项，配对一次后长期有效。
 */
public class LanClient {

    public static final int DEFAULT_PORT = 8899;
    public static final int DISCOVERY_PORT = 8898;

    private Socket sock;
    private BufferedReader in;
    private PrintWriter out;
    private String host;
    private int port;
    private String token;

    public LanClient(String host, int port, String token) {
        this.host = host;
        this.port = port;
        this.token = token;
    }

    /** 配对：用 6 位配对码换取 token */
    public static String pair(String host, int port, String code, int timeoutMs) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            PrintWriter w = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true);
            w.println("PAIR " + code);
            String resp = r.readLine();
            if (resp != null && resp.startsWith("OK ")) {
                return resp.substring(3).trim();
            }
            throw new Exception(resp == null ? "无响应" : resp);
        }
    }

    private void open(int timeoutMs) throws Exception {
        close();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setSoTimeout(timeoutMs);
        s.setTcpNoDelay(true);
        sock = s;
        in = new BufferedReader(new InputStreamReader(s.getInputStream()));
        out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true);
    }

    /** SPEC-011 FR-11-7：每条命令短连接——发前建立、应答即关。
     *  服务器 30 秒空闲即回收连接，长连接复用会让客户端停留在假活状态
     *  （isConnected 对对端关闭不可见），下一次按键报「连接断开」。 */
    private String cmd(String line, int timeoutMs) throws Exception {
        open(timeoutMs);
        try {
            out.println(line);
            String resp = in.readLine();
            if (resp == null) throw new Exception("连接断开");
            return resp;
        } finally {
            close();
        }
    }

    public void key(int code) throws Exception {
        String r = cmd("KEY " + token + " " + code, 4000);
        if (!r.startsWith("OK")) throw new Exception(r);
    }

    /** SPEC-018：长按（仅 OK 23），盒端执行 ACTION_LONG_CLICK */
    public void keyLong(int code) throws Exception {
        String r = cmd("KEYLONG " + token + " " + code, 4000);
        if (!r.startsWith("OK")) throw new Exception(r);
    }

    public void text(String t) throws Exception {
        String b64 = Base64.getEncoder().encodeToString(t.getBytes("UTF-8"));
        String r = cmd("TEXT " + token + " " + b64, 5000);
        if (!r.startsWith("OK")) throw new Exception(r);
    }

    public void launch(String pkg) throws Exception {
        String r = cmd("APP " + token + " " + pkg, 6000);
        if (!r.startsWith("OK")) throw new Exception(r);
    }

    /** SPEC-016：列出盒子第三方应用，载荷为「包名|base64(应用名)」逗号分隔 */
    public String list() throws Exception {
        String r = cmd("LIST " + token, 6000);
        if (!r.startsWith("OK")) throw new Exception(r);
        return r.substring(3).trim();
    }

    /** SPEC-019：取盒子端应用图标（144x144 PNG），包不存在时抛异常 */
    public byte[] icon(String pkg) throws Exception {
        String r = cmd("ICON " + token + " " + pkg, 6000);
        if (!r.startsWith("OK")) throw new Exception(r);
        return Base64.getDecoder().decode(r.substring(3).trim());
    }

    public void close() {
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        sock = null;
    }

    /** 监听盒子广播，返回 "ip" 或 null（3 秒超时） */
    public static String discover(int waitMs) {
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket(DISCOVERY_PORT);
            ds.setSoTimeout(waitMs);
            ds.setBroadcast(true);
            byte[] buf = new byte[256];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            ds.receive(p);
            String msg = new String(p.getData(), 0, p.getLength(), "UTF-8");
            if (msg.startsWith("TVREMOTE|")) {
                return p.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        } finally {
            if (ds != null) ds.close();
        }
        return null;
    }

    /** SPEC-013 FR-13-5：在 waitMs 内收集所有盒子广播，返回 [名称, IP] 列表（按 IP 去重） */
    public static List<String[]> discoverAll(int waitMs) {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket(DISCOVERY_PORT);
            ds.setBroadcast(true);
            ds.setSoTimeout(400);
            long end = System.currentTimeMillis() + waitMs;
            byte[] buf = new byte[256];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    ds.receive(p);
                } catch (SocketTimeoutException te) {
                    continue;
                }
                String msg = new String(p.getData(), 0, p.getLength(), "UTF-8");
                if (!msg.startsWith("TVREMOTE|")) continue;
                String ip = p.getAddress().getHostAddress();
                if (!seen.add(ip)) continue;
                String[] parts = msg.split("\\|", 3);
                String name = parts.length > 2 && !parts[2].isEmpty() ? parts[2] : ip;
                out.add(new String[]{name, ip});
            }
        } catch (Exception ignored) {
        } finally {
            if (ds != null) ds.close();
        }
        return out;
    }
}
