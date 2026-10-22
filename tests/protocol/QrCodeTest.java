import com.koco.tvremoteserver.QrEncoder;

/**
 * SPEC-024：盒端二维码生成器的结构与边界检查。
 * 生成器为纯 Java，可直接在 PC JVM 运行；不校验图形外观，只校验 QR 规范结构。
 */
public class QrCodeTest {

    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) {
        tc_size_v1();
        tc_size_v3_url();
        tc_size_v3_pair_url();
        tc_finders();
        tc_timing();
        tc_dark_module();
        tc_format_info();
        tc_deterministic();
        tc_boundary_42();
        tc_too_long();
        System.out.println("结果：通过 " + pass + "，失败 " + fail
                + (fail == 0 ? " —— 全部通过" : ""));
        if (fail > 0) System.exit(1);
    }

    static void check(String name, boolean ok, String msg) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + " —— " + msg);
        }
    }

    /** 短内容走版本 1：21x21 */
    static void tc_size_v1() {
        boolean[][] m = QrEncoder.encode("HELLO");
        check("qr_size_v1", m.length == 21 && m[0].length == 21,
                "版本 1 应为 21x21，实际 " + m.length);
    }

    /** 盒子网页长地址 28 字节，落入版本 3：29x29 */
    static void tc_size_v3_url() {
        boolean[][] m = QrEncoder.encode("http://192.168.100.100:8080/");
        check("qr_size_v3_url", m.length == 29 && m[0].length == 29,
                "版本 3 应为 29x29，实际 " + m.length);
    }

    /** 带配对码的最长扫码地址（IPv4 全 255 + 6 位码，39 字节）仍应落在版本 3 */
    static void tc_size_v3_pair_url() {
        boolean[][] m = QrEncoder.encode("http://255.255.255.255:8080/#pair=123456");
        check("qr_size_v3_pair_url", m.length == 29,
                "带配对码的地址应为版本 3（29x29），实际 " + m.length);
    }

    static void tc_finders() {
        boolean[][] m = QrEncoder.encode("http://192.168.100.100:8080/");
        int size = m.length;
        check("qr_finder_tl", finderOk(m, 0, 0), "左上定位图形不符合规范");
        check("qr_finder_tr", finderOk(m, size - 7, 0), "右上定位图形不符合规范");
        check("qr_finder_bl", finderOk(m, 0, size - 7), "左下定位图形不符合规范");
        check("qr_finder_separator", !m[7][7] && !m[7][size - 8] && !m[size - 8][7],
                "定位图形外侧分隔符应为浅色");
    }

    static void tc_timing() {
        boolean[][] m = QrEncoder.encode("http://192.168.100.100:8080/");
        int size = m.length;
        boolean row = true;
        boolean col = true;
        for (int i = 8; i < size - 8; i++) {
            if (m[6][i] != (i % 2 == 0)) row = false;
            if (m[i][6] != (i % 2 == 0)) col = false;
        }
        check("qr_timing_row", row, "第 6 行时序图形不符合交替规律");
        check("qr_timing_col", col, "第 6 列时序图形不符合交替规律");
    }

    static void tc_dark_module() {
        boolean[][] m = QrEncoder.encode("http://192.168.100.100:8080/");
        int size = m.length;
        check("qr_dark_module", m[size - 8][8], "固定暗模块 (8, size-8) 应为黑色");
    }

    /** 纠错等级 M 的格式信息（两份拷贝）与 BCH 校验 */
    static void tc_format_info() {
        boolean[][] m = QrEncoder.encode("http://192.168.100.100:8080/");
        int size = m.length;
        int bits = 0;
        for (int i = 0; i <= 5; i++) if (m[i][8]) bits |= 1 << i;
        if (m[7][8]) bits |= 1 << 6;
        if (m[8][8]) bits |= 1 << 7;
        if (m[8][7]) bits |= 1 << 8;
        for (int i = 9; i < 15; i++) if (m[8][14 - i]) bits |= 1 << i;
        int val = bits ^ 0x5412;
        check("qr_format_ecc_m", (val >>> 13) == 0,
                "纠错等级应为 M（00），实际 " + Integer.toBinaryString(val >>> 13));
        int data = val >>> 10;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        check("qr_format_bch", ((data << 10) | rem) == val, "格式信息 BCH 校验不通过");

        int copy2 = 0;
        for (int i = 0; i < 8; i++) if (m[8][size - 1 - i]) copy2 |= 1 << i;
        for (int i = 8; i < 15; i++) if (m[size - 15 + i][8]) copy2 |= 1 << i;
        check("qr_format_copy", bits == copy2, "两份格式信息应完全一致");
    }

    static void tc_deterministic() {
        boolean[][] a = QrEncoder.encode("http://10.0.0.2:8080/");
        boolean[][] b = QrEncoder.encode("http://10.0.0.2:8080/");
        boolean same = a.length == b.length;
        for (int y = 0; same && y < a.length; y++) {
            for (int x = 0; x < a.length; x++) {
                if (a[y][x] != b[y][x]) {
                    same = false;
                    break;
                }
            }
        }
        check("qr_deterministic", same, "相同内容两次生成结果应一致");
    }

    /** 42 字节正好是版本 3 的容量上限 */
    static void tc_boundary_42() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 42; i++) sb.append('a');
        boolean[][] m = QrEncoder.encode(sb.toString());
        check("qr_boundary_42", m.length == 29, "42 字节应仍为版本 3，实际 " + m.length);
    }

    static void tc_too_long() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 43; i++) sb.append('a');
        boolean thrown = false;
        try {
            QrEncoder.encode(sb.toString());
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        check("qr_too_long", thrown, "超过 42 字节应抛 IllegalArgumentException");
    }

    private static boolean finderOk(boolean[][] m, int ox, int oy) {
        for (int dy = 0; dy < 7; dy++) {
            for (int dx = 0; dx < 7; dx++) {
                boolean dark = dx == 0 || dx == 6 || dy == 0 || dy == 6
                        || (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4);
                if (m[oy + dy][ox + dx] != dark) return false;
            }
        }
        return true;
    }
}
