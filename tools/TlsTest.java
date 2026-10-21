import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.zip.CRC32;
import javax.net.ssl.*;

/** 测试安卓 11+ 无线调试的 TLS 握手（STLS → TLS1.3 → CNXN） */
public class TlsTest {
    static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_STLS = 0x534c5453;
    static final int VERSION = 0x01000000, MAXDATA = 256 * 1024;

    static PrivateKey priv;
    static Certificate cert;

    public static void main(String[] a) throws Exception {
        String host = a.length > 0 ? a[0] : "192.168.3.38";
        int port = a.length > 1 ? Integer.parseInt(a[1]) : 36301;

        byte[] pk8 = Base64.getMimeDecoder().decode(
                new String(Files.readAllBytes(Paths.get("adbkey.pk8"))).replaceAll("\\s", ""));
        priv = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pk8));
        cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(new FileInputStream("cert.der"));

        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 5000);
        s.setSoTimeout(8000);
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));

        send(out, A_CNXN, VERSION, MAXDATA, "host::features=shell_v2,cmd\0".getBytes());
        Msg m = recv(in);
        System.out.println("首轮响应: " + name(m.cmd));

        if (m.cmd == A_STLS) {
            System.out.println("→ 需要 TLS 升级，开始握手…");
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            char[] pw = "koco".toCharArray();
            ks.setKeyEntry("adb", priv, pw, new Certificate[]{cert});
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pw);
            TrustManager tm = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String x) {}
                public void checkServerTrusted(X509Certificate[] c, String x) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext ctx = SSLContext.getInstance("TLSv1.3");
            ctx.init(kmf.getKeyManagers(), new TrustManager[]{tm}, null);
            SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(s, host, port, true);
            ssl.setEnabledProtocols(new String[]{"TLSv1.3"});
            ssl.setSoTimeout(10000);
            ssl.startHandshake();
            System.out.println("→ TLS 握手成功: " + ssl.getSession().getProtocol()
                    + " / " + ssl.getSession().getCipherSuite());
            in = new DataInputStream(new BufferedInputStream(ssl.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(ssl.getOutputStream()));
            send(out, A_CNXN, VERSION, MAXDATA, "host::features=shell_v2,cmd\0".getBytes());
            m = recv(in);
            System.out.println("TLS 后响应: " + name(m.cmd) + " a0=" + m.a0);
            if (m.cmd == A_AUTH) {
                System.out.println(m.a0 == 1 ? "→ 收到 TOKEN，可以签名认证"
                        : "→ 需要递交公钥");
            }
        }
        s.close();
    }

    static String name(int c) {
        if (c == A_CNXN) return "CNXN";
        if (c == A_AUTH) return "AUTH";
        if (c == A_STLS) return "STLS";
        return String.format("0x%08x", c);
    }

    static void send(DataOutputStream o, int cmd, int a0, int a1, byte[] d) throws IOException {
        if (d == null) d = new byte[0];
        CRC32 crc = new CRC32();
        crc.update(d);
        ByteBuffer b = ByteBuffer.allocate(24 + d.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(d.length).putInt((int) crc.getValue()).putInt(cmd ^ 0xFFFFFFFF);
        b.put(d);
        o.write(b.array());
        o.flush();
    }

    static class Msg { int cmd, a0, a1, len; byte[] data = new byte[0]; }

    static Msg recv(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt(); m.a0 = b.getInt(); m.a1 = b.getInt();
        m.len = b.getInt(); b.getInt(); b.getInt();
        if (m.len > 0) { m.data = new byte[m.len]; in.readFully(m.data); }
        return m;
    }
}
