import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.zip.CRC32;

/**
 * ADB over TCP 客户端原型（用于验证 App 内直连方案）
 * 用法：
 *   java AdbTest.java gen             生成密钥，输出 ADB 公钥
 *   java AdbTest.java shell "命令"     连接盒子执行 shell
 */
public class AdbTest {
    static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_WRTE = 0x45545257, A_CLSE = 0x45534c43,
            A_STLS = 0x534c5453;
    static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    static final int VERSION = 0x01000000;
    static final int MAXDATA = 256 * 1024;

    static final String HOST = "192.168.3.125";
    static final int PORT = 5555;
    static final Path KEYF = Paths.get("adbkey.pk8");

    static KeyPair loadKey() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        if (Files.exists(KEYF)) {
            byte[] pk8 = Base64.getMimeDecoder().decode(Files.readAllBytes(KEYF));
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(pk8));
            return rebuild(priv);
        }
        KeyPair kp = kpg.generateKeyPair();
        Files.write(KEYF, Base64.getMimeEncoder().encode(kp.getPrivate().getEncoded()));
        return kp;
    }

    /** 从私钥推导公钥（只存私钥，公钥由 modulus/exponent 反推） */
    static KeyPair rebuild(PrivateKey priv) throws Exception {
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) priv;
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPublicKeySpec spec = new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
        return new KeyPair(kf.generatePublic(spec), priv);
    }

    /** ADB 公钥格式：len(4 LE) + modulus(256 字节小端) + exponent(4 LE)，再 base64 */
    static String adbPublicKey(RSAPublicKey pub) {
        byte[] mod = toFixed(pub.getModulus(), 256);
        byte[] rev = new byte[256];
        for (int i = 0; i < 256; i++) rev[i] = mod[255 - i];
        ByteBuffer b = ByteBuffer.allocate(4 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.put(rev);
        b.putInt(pub.getPublicExponent().intValue());
        return Base64.getEncoder().encodeToString(b.array());
    }

    static byte[] toFixed(java.math.BigInteger v, int len) {
        byte[] raw = v.toByteArray();
        if (raw.length == len) return raw;
        byte[] out = new byte[len];
        if (raw.length > len) System.arraycopy(raw, raw.length - len, out, 0, len);
        else System.arraycopy(raw, 0, out, len - raw.length, raw.length);
        return out;
    }

    static void send(DataOutputStream out, int cmd, int a0, int a1, byte[] data) throws IOException {
        if (data == null) data = new byte[0];
        CRC32 crc = new CRC32();
        crc.update(data);
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt((int) crc.getValue()).putInt(cmd ^ 0xFFFFFFFF);
        b.put(data);
        out.write(b.array());
        out.flush();
    }

    static class Msg {
        int cmd, a0, a1, len;
        byte[] data;
    }

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

    public static void main(String[] args) throws Exception {
        KeyPair kp = loadKey();
        String pubStr = adbPublicKey((RSAPublicKey) kp.getPublic());

        if (args.length > 0 && args[0].equals("gen")) {
            System.out.println(pubStr + " koco@remote");
            return;
        }

        String cmd = args.length > 1 ? args[1] : "input keyevent 20";
        Socket s = new Socket();
        s.connect(new InetSocketAddress(HOST, PORT), 5000);
        s.setSoTimeout(8000);
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));

        send(out, A_CNXN, VERSION, MAXDATA, "host::features=shell_v2,cmd,stat_v2\0".getBytes());

        boolean authed = false;
        for (int i = 0; i < 8; i++) {
            Msg m = recv(in);
            String c = new String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(m.cmd).array());
            if (m.cmd == A_CNXN) { System.out.println("OK CNXN: " + new String(m.data).trim()); authed = true; break; }
            if (m.cmd == A_AUTH) {
                if (m.a0 == AUTH_TOKEN) {
                    Signature sig = Signature.getInstance("SHA1withRSA");
                    sig.initSign(kp.getPrivate());
                    sig.update(m.data);
                    byte[] s1 = sig.sign();
                    send(out, A_AUTH, AUTH_SIGNATURE, 0, s1);
                    System.out.println("-> 发送签名，等待结果…");
                } else {
                    send(out, A_AUTH, AUTH_RSAPUBLICKEY, 0, (pubStr + " koco@remote\0").getBytes());
                    System.out.println("!! 未授权，已发送公钥，需在设备上确认");
                }
                continue;
            }
            System.out.println("<< " + c + " a0=" + m.a0 + " len=" + m.len);
        }
        if (!authed) { System.out.println("认证失败"); return; }

        // 打开 shell
        int lid = 1;
        send(out, A_OPEN, lid, 0, ("shell:" + cmd + "\0").getBytes());
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        for (int i = 0; i < 50; i++) {
            Msg m = recv(in);
            if (m.cmd == A_OKAY) { if (m.a1 != 0) lid = m.a0; continue; }
            if (m.cmd == A_WRTE) {
                acc.write(m.data);
                send(out, A_OKAY, lid, m.a0, null);
                continue;
            }
            if (m.cmd == A_CLSE) break;
        }
        System.out.println("[shell 输出] " + acc.toString("UTF-8").trim());
        s.close();
    }
}
