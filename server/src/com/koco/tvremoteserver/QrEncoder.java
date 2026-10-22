package com.koco.tvremoteserver;

import java.nio.charset.StandardCharsets;

/**
 * 最小 QR 码生成器（SPEC-024）：字节模式、纠错等级 M、版本 1~3。
 * 纯 Java 实现，不依赖 Android API 与第三方库，可在 PC JVM 上直接跑测试。
 */
public final class QrEncoder {

    /** 版本 1~3 在纠错等级 M 下的数据码字数量 */
    private static final int[] DATA_CW = {0, 16, 28, 44};
    /** 版本 1~3 在纠错等级 M 下的纠错码字数量 */
    private static final int[] ECC_CW = {0, 10, 16, 26};
    /** 定位图形里的两种 11 位近似模式，用于掩码惩罚规则 3 */
    private static final boolean[] PENALTY_A = {true, false, true, true, true, false, true,
            false, false, false, false};
    private static final boolean[] PENALTY_B = {false, false, false, false, true, false, true,
            true, true, false, true};

    private static final int[] GF_EXP = new int[512];
    private static final int[] GF_LOG = new int[256];

    static {
        // GF(256) 以 0x11D 为本原多项式建表
        int x = 1;
        for (int i = 0; i < 255; i++) {
            GF_EXP[i] = x;
            GF_LOG[x] = i;
            x <<= 1;
            if (x >= 0x100) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) GF_EXP[i] = GF_EXP[i - 255];
    }

    private QrEncoder() {
    }

    /**
     * 生成模块矩阵，true 为黑模块；返回不含四周静区。
     * 内容为空或超过版本 3 容量（42 字节）时抛 IllegalArgumentException。
     */
    public static boolean[][] encode(String text) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("empty");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int version = pickVersion(bytes.length);
        if (version == 0) throw new IllegalArgumentException("too long");
        int size = version * 4 + 17;

        boolean[][] fn = new boolean[size][size];
        boolean[][] mod = new boolean[size][size];

        // 三个定位图形（含分隔符）
        drawFinder(mod, fn, 0, 0);
        drawFinder(mod, fn, size - 7, 0);
        drawFinder(mod, fn, 0, size - 7);
        // 时序图形，跳过定位图形范围
        for (int i = 8; i < size - 8; i++) {
            set(mod, fn, i, 6, i % 2 == 0);
            set(mod, fn, 6, i, i % 2 == 0);
        }
        // 对齐图形：版本 2/3 各只有一个，位于右下
        if (version == 2) drawAlignment(mod, fn, 18, 18);
        if (version == 3) drawAlignment(mod, fn, 22, 22);
        // 格式信息区域先占位并全部标记为功能模块，数据不再写入
        drawFormat(mod, 0, size);
        markFormat(fn, size);

        drawData(mod, fn, addEcc(buildData(bytes, version), ECC_CW[version]), size);

        // 8 种掩码逐一试算，取惩罚分最低者
        boolean[][] best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            boolean[][] cand = copy(mod);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (!fn[y][x] && mask(m, x, y)) cand[y][x] = !cand[y][x];
                }
            }
            drawFormat(cand, m, size);
            int score = penalty(cand, size);
            if (score < bestScore) {
                bestScore = score;
                best = cand;
            }
        }
        return best;
    }

    /** 返回能容纳该字节长度的最小版本，0 表示超出版本 3 容量 */
    private static int pickVersion(int len) {
        for (int v = 1; v <= 3; v++) {
            if (4 + 8 + len * 8 <= DATA_CW[v] * 8) return v;
        }
        return 0;
    }

    private static void set(boolean[][] m, boolean[][] fn, int x, int y, boolean dark) {
        m[y][x] = dark;
        fn[y][x] = true;
    }

    private static void drawFinder(boolean[][] m, boolean[][] fn, int ox, int oy) {
        int size = m.length;
        for (int dy = -1; dy <= 7; dy++) {
            for (int dx = -1; dx <= 7; dx++) {
                int x = ox + dx;
                int y = oy + dy;
                if (x < 0 || y < 0 || x >= size || y >= size) continue;
                boolean inner = dx >= 0 && dx <= 6 && dy >= 0 && dy <= 6;
                boolean dark = inner && (dx == 0 || dx == 6 || dy == 0 || dy == 6
                        || (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4));
                set(m, fn, x, y, dark);
            }
        }
    }

    private static void drawAlignment(boolean[][] m, boolean[][] fn, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                boolean dark = Math.max(Math.abs(dx), Math.abs(dy)) != 1;
                set(m, fn, cx + dx, cy + dy, dark);
            }
        }
    }

    /** 写入 15 位格式信息（纠错等级 M 固定 0b00） */
    private static void drawFormat(boolean[][] m, int mask, int size) {
        int data = mask; // (0b00 << 3) | mask
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) m[i][8] = bit(bits, i);
        m[7][8] = bit(bits, 6);
        m[8][8] = bit(bits, 7);
        m[8][7] = bit(bits, 8);
        for (int i = 9; i < 15; i++) m[8][14 - i] = bit(bits, i);
        for (int i = 0; i < 8; i++) m[8][size - 1 - i] = bit(bits, i);
        for (int i = 8; i < 15; i++) m[size - 15 + i][8] = bit(bits, i);
        m[size - 8][8] = true; // 固定暗模块
    }

    /** 标记格式信息占用格，避免数据填充覆盖 */
    private static void markFormat(boolean[][] fn, int size) {
        for (int i = 0; i <= 5; i++) fn[i][8] = true;
        fn[7][8] = true;
        fn[8][8] = true;
        fn[8][7] = true;
        for (int i = 9; i < 15; i++) fn[8][14 - i] = true;
        for (int i = 0; i < 8; i++) fn[8][size - 1 - i] = true;
        for (int i = 8; i < 15; i++) fn[size - 15 + i][8] = true;
        fn[size - 8][8] = true;
    }

    private static boolean bit(int v, int i) {
        return ((v >>> i) & 1) != 0;
    }

    private static boolean[][] copy(boolean[][] src) {
        boolean[][] out = new boolean[src.length][];
        for (int i = 0; i < src.length; i++) out[i] = src[i].clone();
        return out;
    }

    // ---------------- 数据编码 ----------------

    /** 模式指示符 + 字符计数 + 数据 + 终止符 + 填充字节 */
    private static byte[] buildData(byte[] bytes, int version) {
        int capacity = DATA_CW[version] * 8;
        int[] bits = new int[capacity];
        int n = 0;
        n = append(bits, n, 0x4, 4);
        n = append(bits, n, bytes.length, 8);
        for (byte b : bytes) n = append(bits, n, b & 0xFF, 8);
        n = Math.min(capacity, n + 4);
        n = Math.min(capacity, n + (8 - n % 8) % 8);
        byte[] out = new byte[DATA_CW[version]];
        for (int i = 0; i < n; i += 8) {
            int v = 0;
            for (int j = 0; j < 8; j++) v = (v << 1) | bits[i + j];
            out[i / 8] = (byte) v;
        }
        for (int i = n / 8, k = 0; i < out.length; i++, k++) {
            out[i] = (byte) (k % 2 == 0 ? 0xEC : 0x11);
        }
        return out;
    }

    private static int append(int[] bits, int n, int value, int len) {
        for (int i = len - 1; i >= 0; i--) bits[n++] = (value >>> i) & 1;
        return n;
    }

    /** 数据码字后追加 RS 纠错码字（版本 1~3 的 M 级均为单块，无需交织） */
    private static byte[] addEcc(byte[] data, int eccLen) {
        int[] div = rsDivisor(eccLen);
        int[] rem = new int[eccLen];
        for (byte b : data) {
            int factor = (b & 0xFF) ^ rem[0];
            System.arraycopy(rem, 1, rem, 0, eccLen - 1);
            rem[eccLen - 1] = 0;
            for (int i = 0; i < eccLen; i++) rem[i] ^= gfMul(div[i], factor);
        }
        byte[] out = new byte[data.length + eccLen];
        System.arraycopy(data, 0, out, 0, data.length);
        for (int i = 0; i < eccLen; i++) out[data.length + i] = (byte) rem[i];
        return out;
    }

    /** 生成多项式 g(x) = (x-r^0)(x-r^1)...(x-r^{n-1})，返回去掉最高次项的系数 */
    private static int[] rsDivisor(int degree) {
        int[] result = new int[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < result.length; j++) {
                result[j] = gfMul(result[j], root);
                if (j + 1 < result.length) result[j] ^= result[j + 1];
            }
            root = gfMul(root, 2);
        }
        return result;
    }

    private static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return GF_EXP[GF_LOG[a] + GF_LOG[b]];
    }

    // ---------------- 矩阵填充 ----------------

    /** 从右下角按两列一组蛇形填充，跳过功能模块 */
    private static void drawData(boolean[][] m, boolean[][] fn, byte[] all, int size) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < all.length * 8) {
                        m[y][x] = ((all[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
            }
        }
    }

    private static boolean mask(int mask, int x, int y) {
        switch (mask) {
            case 0: return (x + y) % 2 == 0;
            case 1: return y % 2 == 0;
            case 2: return x % 3 == 0;
            case 3: return (x + y) % 3 == 0;
            case 4: return (y / 2 + x / 3) % 2 == 0;
            case 5: return (x * y) % 2 + (x * y) % 3 == 0;
            case 6: return ((x * y) % 2 + (x * y) % 3) % 2 == 0;
            default: return ((x + y) % 2 + (x * y) % 3) % 2 == 0;
        }
    }

    /** 掩码惩罚分（标准四条规则） */
    private static int penalty(boolean[][] m, int size) {
        int r = 0;
        for (int y = 0; y < size; y++) {
            int run = 1;
            for (int x = 1; x < size; x++) {
                if (m[y][x] == m[y][x - 1]) {
                    run++;
                    if (run >= 5) r += run == 5 ? 3 : 1;
                } else {
                    run = 1;
                }
            }
        }
        for (int x = 0; x < size; x++) {
            int run = 1;
            for (int y = 1; y < size; y++) {
                if (m[y][x] == m[y - 1][x]) {
                    run++;
                    if (run >= 5) r += run == 5 ? 3 : 1;
                } else {
                    run = 1;
                }
            }
        }
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean v = m[y][x];
                if (v == m[y][x + 1] && v == m[y + 1][x] && v == m[y + 1][x + 1]) r += 3;
            }
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x + 11 <= size; x++) {
                if (matchPattern(m, y, x, true)) r += 40;
            }
        }
        for (int x = 0; x < size; x++) {
            for (int y = 0; y + 11 <= size; y++) {
                if (matchPattern(m, y, x, false)) r += 40;
            }
        }
        int dark = 0;
        for (boolean[] row : m) {
            for (boolean b : row) if (b) dark++;
        }
        int total = size * size;
        r += Math.abs(dark * 100 / total - 50) / 5 * 10;
        return r;
    }

    private static boolean matchPattern(boolean[][] m, int y, int x, boolean horizontal) {
        boolean a = true;
        boolean b = true;
        for (int k = 0; k < 11; k++) {
            boolean v = horizontal ? m[y][x + k] : m[y + k][x];
            if (v != PENALTY_A[k]) a = false;
            if (v != PENALTY_B[k]) b = false;
        }
        return a || b;
    }
}
