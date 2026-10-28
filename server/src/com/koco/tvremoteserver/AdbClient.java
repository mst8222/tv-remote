package com.koco.tvremoteserver;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.zip.CRC32;

/**
 * 极简 ADB over TCP 客户端（SPEC-039，由 SPEC-002 时期的 AdbClient 复制改写）。
 *
 * 盒端自连 127.0.0.1:5555，拿到 shell 身份后执行 input 命令，注入真实按键事件。
 * 首次连接会在电视上弹系统「允许调试」确认框，用户点一次允许后永久免确认
 * （adbd 把本应用公钥写入 /data/misc/adb/adb_keys）。
 *
 * 与旧实现的差异：密钥改为运行时生成并存应用私有目录（不再依赖构建期 adbkey），
 * Base64 用 android.util.Base64（API 22 兼容，SPEC-035），
 * A_STLS（Android 11+ 无线调试）不在本期范围，遇到时直接报错。
 */
class AdbClient {

    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_WRTE = 0x45545257, A_CLSE = 0x45534c43, A_STLS = 0x534c5453;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final int VERSION = 0x01000000, MAXDATA = 256 * 1024;
    /** 首次连接时用户在电视上点「允许」的等待时长 */
    private static final int AUTH_WAIT_MS = 60000;

    private Socket sock;
    private DataInputStream in;
    private DataOutputStream out;
    private final PrivateKey priv;
    private final String pubStr;
    private int nextId = 1;
    String banner = "";

    private AdbClient(PrivateKey key) throws Exception {
        priv = key;
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) priv;
        RSAPublicKey pub = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        pubStr = encodePub(pub);
    }

    /** 按密钥文件打开客户端：文件不存在时生成 RSA 2048 并持久化 */
    static AdbClient open(File keyFile) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (keyFile.exists()) {
            byte[] pk8 = readFile(keyFile);
            try {
                return new AdbClient(kf.generatePrivate(new PKCS8EncodedKeySpec(pk8)));
            } catch (Exception ignored) {
                // 密钥文件损坏：重新生成（电视上会再确认一次）
            }
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        writeFile(keyFile, kp.getPrivate().getEncoded());
        return new AdbClient(kp.getPrivate());
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream fin = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fin.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }

    /** ADB 公钥编码：len(4 LE) + modulus(256B 小端) + exponent(4 LE) */
    private static String encodePub(RSAPublicKey k) {
        byte[] mod = fixed(k.getModulus(), 256);
        byte[] rev = new byte[256];
        for (int i = 0; i < 256; i++) rev[i] = mod[255 - i];
        ByteBuffer b = ByteBuffer.allocate(264).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).put(rev).putInt(k.getPublicExponent().intValue());
        return Base64.encodeToString(b.array(), Base64.NO_WRAP);
    }

    private static byte[] fixed(BigInteger v, int len) {
        byte[] r = v.toByteArray();
        if (r.length == len) return r;
        byte[] o = new byte[len];
        if (r.length > len) System.arraycopy(r, r.length - len, o, 0, len);
        else System.arraycopy(r, 0, o, len - r.length, r.length);
        return o;
    }

    boolean isConnected() {
        return sock != null && !sock.isClosed();
    }

    synchronized void connect(String host, int port, int timeoutMs) throws Exception {
        close();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setSoTimeout(timeoutMs);
        s.setTcpNoDelay(true);
        sock = s;
        in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        nextId = 1;

        send(A_CNXN, VERSION, MAXDATA, "host::\0".getBytes("UTF-8"));

        boolean askedKey = false;
        for (int i = 0; i < 10; i++) {
            Msg m = recv();
            if (m.cmd == A_CNXN) {
                banner = new String(m.data, "UTF-8").trim();
                return;
            }
            if (m.cmd == A_STLS) {
                throw new IOException("该设备需要无线调试配对");
            }
            if (m.cmd == A_AUTH && m.a0 == AUTH_TOKEN) {
                // 用本应用私钥签名 adbd 随机令牌
                Signature sig = Signature.getInstance("SHA1withRSA");
                sig.initSign(priv);
                sig.update(m.data);
                send(A_AUTH, AUTH_SIGNATURE, 0, sig.sign());
                continue;
            }
            if (m.cmd == A_AUTH && m.a0 == AUTH_RSAPUBLICKEY) {
                // 电视不认识本应用的密钥：递交公钥，电视上会弹「允许调试」
                send(A_AUTH, AUTH_RSAPUBLICKEY, 0,
                        (pubStr + " koco@tvremote\0").getBytes("UTF-8"));
                askedKey = true;
                sock.setSoTimeout(AUTH_WAIT_MS);
                continue;
            }
        }
        throw new IOException(askedKey ? "等待电视上点「允许」" : "ADB 握手失败");
    }

    /**
     * 执行一条 shell 命令并返回输出（stdout + stderr，已去掉 \r）。
     * 只处理本流（a1 = 本端 local id）的消息：adbd 可能把上一条流的残留消息
     * 拖到下一条 A_OPEN 之后送达，按流 id 过滤可避免输出整体错位。
     */
    synchronized String exec(String cmd, int timeoutMs) throws IOException {
        if (!isConnected()) throw new IOException("未连接");
        sock.setSoTimeout(timeoutMs);
        int lid = nextId++;
        send(A_OPEN, lid, 0, ("shell:" + cmd + "\0").getBytes("UTF-8"));
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Msg m;
            try {
                m = recv();
            } catch (java.net.SocketTimeoutException te) {
                break;
            }
            if (m.cmd == A_OKAY) {
                if (m.a1 == lid) continue;           // 本流建立成功
            } else if (m.cmd == A_WRTE) {
                if (m.a1 == lid) {
                    acc.write(m.data);
                    send(A_OKAY, lid, m.a0, null);
                    continue;
                }
            } else if (m.cmd == A_CLSE) {
                if (m.a1 == lid) break;
            }
            // 其它流的残留消息：回 OKAY 后丢弃，不参与输出
            if (m.cmd == A_WRTE) send(A_OKAY, m.a1, m.a0, null);
        }
        try {
            return acc.toString("UTF-8").replace("\r", "");
        } catch (IOException e) {
            return "";
        }
    }

    synchronized void close() {
        try { if (sock != null) sock.close(); } catch (IOException ignored) {}
        sock = null;
        in = null;
        out = null;
    }

    private static class Msg {
        int cmd, a0, a1, len;
        byte[] data = new byte[0];
    }

    private void send(int cmd, int a0, int a1, byte[] data) throws IOException {
        if (data == null) data = new byte[0];
        CRC32 crc = new CRC32();
        crc.update(data);
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length)
                .putInt((int) crc.getValue()).putInt(cmd ^ 0xFFFFFFFF);
        b.put(data);
        out.write(b.array());
        out.flush();
    }

    private Msg recv() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.a0 = b.getInt();
        m.a1 = b.getInt();
        m.len = b.getInt();
        b.getInt();
        b.getInt();
        if (m.len > 0 && m.len < 8 * 1024 * 1024) {
            m.data = new byte[m.len];
            in.readFully(m.data);
        }
        return m;
    }
}
