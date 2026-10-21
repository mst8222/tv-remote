package com.koco.tvremote;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.zip.CRC32;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 极简 ADB over TCP 客户端，仅供本 App 直连电视盒子发送 input 命令。
 * 密钥预置在盒子 /data/misc/adb/adb_keys 中，连接时无需在电视上确认。
 */
public class AdbClient {

    static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_WRTE = 0x45545257, A_CLSE = 0x45534c43,
            A_STLS = 0x534c5453;
    static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    static final int VERSION = 0x01000000, MAXDATA = 256 * 1024;

    private Socket sock;
    private DataInputStream in;
    private DataOutputStream out;
    private PrivateKey priv;
    private RSAPublicKey pub;
    private String pubStr;
    private int nextId = 1;
    public String banner = "";

    public AdbClient(String privB64) throws Exception {
        byte[] pk8 = Base64.getMimeDecoder().decode(privB64.replaceAll("\\s", ""));
        KeyFactory kf = KeyFactory.getInstance("RSA");
        priv = kf.generatePrivate(new PKCS8EncodedKeySpec(pk8));
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) priv;
        pub = (RSAPublicKey) kf.generatePublic(
                new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        pubStr = encodePub(pub);
    }

    /** ADB 公钥编码：len(4 LE) + modulus(256B 小端) + exponent(4 LE) */
    static String encodePub(RSAPublicKey k) {
        byte[] mod = fixed(k.getModulus(), 256);
        byte[] rev = new byte[256];
        for (int i = 0; i < 256; i++) rev[i] = mod[255 - i];
        ByteBuffer b = ByteBuffer.allocate(264).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).put(rev).putInt(k.getPublicExponent().intValue());
        return Base64.getEncoder().encodeToString(b.array());
    }

    static byte[] fixed(BigInteger v, int len) {
        byte[] r = v.toByteArray();
        if (r.length == len) return r;
        byte[] o = new byte[len];
        if (r.length > len) System.arraycopy(r, r.length - len, o, 0, len);
        else System.arraycopy(r, 0, o, len - r.length, r.length);
        return o;
    }

    public boolean isConnected() {
        return sock != null && sock.isConnected() && !sock.isClosed();
    }

    public synchronized void connect(String host, int port, int timeoutMs) throws Exception {
        close();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setSoTimeout(timeoutMs);
        s.setTcpNoDelay(true);
        sock = s;
        in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        nextId = 1;

        send(A_CNXN, VERSION, MAXDATA, "host::features=shell_v2,cmd\0".getBytes());

        boolean tlsDone = false, keySent = false;
        for (int i = 0; i < 12; i++) {
            Msg m = recv();
            if (m.cmd == A_CNXN) {
                banner = new String(m.data).trim();
                return;
            }
            if (m.cmd == A_STLS && !tlsDone) {
                // 安卓 11+ 无线调试：先升级 TLS，再重新走一遍 CNXN
                upgradeTls(host, port);
                tlsDone = true;
                send(A_CNXN, VERSION, MAXDATA, "host::features=shell_v2,cmd\0".getBytes());
                continue;
            }
            if (m.cmd == A_AUTH) {
                if (m.a0 == AUTH_TOKEN) {
                    Signature sig = Signature.getInstance("SHA1withRSA");
                    sig.initSign(priv);
                    sig.update(m.data);
                    send(A_AUTH, AUTH_SIGNATURE, 0, sig.sign());
                } else if (!keySent) {
                    // 设备不认识我们：递交公钥，电视上会弹「允许调试」
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, (pubStr + " koco@remote\0").getBytes());
                    keySent = true;
                } else {
                    break;
                }
            }
        }
        throw new IOException(keySent ? "首次连接：请在电视上点允许" : "握手失败");
    }

    /** A_STLS 之后：用 adbkey 作客户端证书，在同一 socket 上升级到 TLS 1.3 */
    private void upgradeTls(String h, int p) throws Exception {
        Certificate cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(
                        Base64.getDecoder().decode(KeyConst.CERT.replaceAll("\\s", ""))));
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        char[] pw = "koco".toCharArray();
        ks.setKeyEntry("adb", priv, pw, new Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);

        TrustManager tm = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };

        SSLContext ctx = null;
        try {
            ctx = SSLContext.getInstance("TLSv1.3");
            ctx.init(kmf.getKeyManagers(), new TrustManager[]{tm}, null);
        } catch (Exception e) {
            ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), new TrustManager[]{tm}, null);
        }
        SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(sock, h, p, true);
        try {
            ssl.setEnabledProtocols(new String[]{"TLSv1.3"});
        } catch (Exception ignored) {}
        ssl.setSoTimeout(8000);
        ssl.startHandshake();
        sock = ssl;
        in = new DataInputStream(new BufferedInputStream(ssl.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(ssl.getOutputStream()));
    }

    /** 执行一条 shell 命令并返回输出 */
    public synchronized String exec(String cmd, int timeoutMs) throws IOException {
        if (!isConnected()) throw new IOException("未连接");
        sock.setSoTimeout(timeoutMs);
        int lid = nextId++;
        send(A_OPEN, lid, 0, ("shell:" + cmd + "\0").getBytes());
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        int rid = 0;
        for (int i = 0; i < 200; i++) {
            Msg m;
            try {
                m = recv();
            } catch (java.net.SocketTimeoutException te) {
                break;
            }
            if (m.cmd == A_OKAY) {
                if (m.a0 != lid) rid = m.a0;
                continue;
            }
            if (m.cmd == A_WRTE) {
                acc.write(m.data);
                send(A_OKAY, lid, m.a0, null);
                continue;
            }
            if (m.cmd == A_CLSE) break;
        }
        String s = acc.toString("UTF-8");
        return s.replace("\r", "");
    }

    public synchronized void close() {
        try { if (sock != null) sock.close(); } catch (IOException ignored) {}
        sock = null;
    }

    static class Msg {
        int cmd, a0, a1, len;
        byte[] data = new byte[0];
    }

    void send(int cmd, int a0, int a1, byte[] data) throws IOException {
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

    Msg recv() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt(); m.a0 = b.getInt(); m.a1 = b.getInt();
        m.len = b.getInt(); b.getInt(); b.getInt();
        if (m.len > 0 && m.len < 8 * 1024 * 1024) {
            m.data = new byte[m.len];
            in.readFully(m.data);
        }
        return m;
    }
}
