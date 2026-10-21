package com.koco.tvremote;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Base64;

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

    public boolean isConnected() {
        return sock != null && sock.isConnected() && !sock.isClosed();
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

    private String cmd(String line, int timeoutMs) throws Exception {
        if (!isConnected()) open(timeoutMs);
        out.println(line);
        String resp = in.readLine();
        if (resp == null) {
            close();
            throw new Exception("连接断开");
        }
        return resp;
    }

    public void key(int code) throws Exception {
        String r = cmd("KEY " + token + " " + code, 4000);
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
}
