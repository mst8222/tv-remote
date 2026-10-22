import com.koco.tvremote.LanClient;
import com.koco.tvremote.TouchSession;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * SPEC-001 局域网直连协议一致性自动测试。
 *
 * 被测对象：app/src/com/koco/tvremote/LanClient.java（真实客户端代码，随测试一同编译）
 * 参照实现：MockBoxServer.java（盒子端协议语义的纯 Java 迁移版）
 *
 * 运行方式：powershell -File tests\protocol\run.ps1
 * 断言失败仅计数不中断，最后以退出码报告（0 全过 / 1 存在失败）。
 *
 * 用例分组：
 *   [协议语义] PING/PAIR/KEY/TEXT/APP 的正确与错误分支
 *   [无障碍] 无障碍关闭时 KEY/TEXT 返回 ERR a11y，音量/APP/LIST 不受影响
 *   [边界情况] 空参、非数字 keycode、未知 keycode、非法 base64、空文本、超长文本、前导 0 配对码
 *   [连接管理] 长连接复用、断线自动重连、服务端踢线、并发连接、连接拒绝
 *   [UDP 发现] 收到广播取 IP、无广播超时、非 TVREMOTE 前缀忽略
 *   [NFR] 命令往返耗时
 */
public class ProtocolTest {

    static int passed = 0, failed = 0;
    static MockBoxServer box;

    interface Thunk { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        box = new MockBoxServer("123456");
        System.out.println("MockBoxServer 监听 127.0.0.1:" + box.port());
        System.out.println();

        // 协议语义
        tc_ping();
        tc_pair_ok();
        tc_pair_wrong_code();
        tc_pair_missing_code();
        tc_pair_leading_zeros();
        tc_pair_twice_same_token();
        tc_key_ok();
        tc_key_wrong_token();
        tc_key_missing_code();
        tc_key_non_numeric();
        tc_text_chinese_space_quote();
        tc_text_double_quote_kept();
        tc_text_wrong_token();
        tc_text_invalid_base64();
        tc_text_empty();
        tc_text_large();
        tc_text_boundary();
        tc_text_b64_too_long();
        tc_app_ok();
        tc_app_settings_action();
        tc_app_invalid_pkg();
        tc_app_pkg_too_long();
        tc_app_wrong_token();
        tc_pair_lockout();
        tc_list_ok();
        tc_list_wrong_token();
        tc_list_empty();
        tc_icon_ok();
        tc_icon_unknown_pkg();
        tc_icon_missing_pkg();
        tc_icon_wrong_token();
        tc_icon_without_a11y();
        tc_key_unsupported();
        tc_keylong_ok();
        tc_keylong_unsupported();
        tc_keylong_missing_code();
        tc_keylong_wrong_token();
        tc_keylong_a11y_off();
        tc_move_ok();
        tc_move_invalid();
        tc_move_a11y_off();
        tc_mtap();
        tc_mlong();
        tc_mclick();
        tc_scroll();
        tc_mhide();
        tc_touch_session();
        tc_a11y_off();
        tc_unknown_cmd();
        tc_empty_line();

        // 连接管理
        tc_keys_in_sequence();
        tc_reconnect_after_close();
        tc_reconnect_after_kick();
        tc_concurrent_clients();
        tc_connect_refused();
        tc_connect_unreachable();

        // UDP 发现
        tc_discover_no_broadcast();
        tc_discover_found();
        tc_discover_other_prefix();
        tc_discover_dedupe_multi();

        // NFR
        tc_rtt();

        box.close();
        System.out.println();
        System.out.println("结果：通过 " + passed + "，失败 " + failed
                + (failed == 0 ? " —— 全部通过" : " —— 存在失败"));
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------------- 协议语义 ----------------

    static void tc_ping() throws Exception {
        eq("ping_pong", "PONG", rawCmd("PING"));
    }

    static void tc_pair_ok() throws Exception {
        String r = rawCmd("PAIR 123456");
        check("pair_ok_prefix", r != null && r.startsWith("OK "), "响应应为 OK <token>，实际 " + r);
        if (r != null && r.startsWith("OK ")) {
            String tok = r.substring(3).trim();
            check("pair_token_format", tok.matches("[0-9a-f]{32}"),
                    "token 应为 32 位小写 hex，实际 " + tok);
            eq("pair_token_matches_server", box.token(), tok);
        }
    }

    static void tc_pair_wrong_code() throws Exception {
        box.resetPairGuard();
        eq("pair_wrong_code", "ERR badcode", rawCmd("PAIR 654321"));
        box.resetPairGuard();
    }

    static void tc_pair_missing_code() throws Exception {
        eq("pair_missing_code", "ERR arg", rawCmd("PAIR"));
    }

    static void tc_pair_leading_zeros() throws Exception {
        // 配对码用 %06d 生成，允许前导 0（如 000000）
        box.setPairCode("000000");
        try {
            check("pair_leading_zeros", rawCmd("PAIR 000000").startsWith("OK "),
                    "前导 0 配对码应可配对");
        } finally {
            box.setPairCode("123456");
        }
    }

    static void tc_pair_twice_same_token() throws Exception {
        String r1 = rawCmd("PAIR 123456");
        String r2 = rawCmd("PAIR 123456");
        eq("pair_twice_same_token", r1, r2);
    }

    static void tc_key_ok() throws Exception {
        LanClient lan = newClient();
        try {
            lan.key(23); // 不抛异常即通过
            record("key_ok", true, "KEY 23 应返回 OK");
            eq("key_records_keycode", "23", box.lastKeycode);
        } finally {
            lan.close();
        }
    }

    static void tc_key_wrong_token() throws Exception {
        eq("key_wrong_token", "ERR auth", rawCmd("KEY deadbeef 23"));
    }

    static void tc_key_missing_code() throws Exception {
        eq("key_missing_code", "ERR arg", rawCmd("KEY " + box.token()));
    }

    static void tc_key_non_numeric() throws Exception {
        // SPEC-014：keycode 必须是整数，非数字按参数错误拒绝
        eq("key_non_numeric", "ERR arg", rawCmd("KEY " + box.token() + " abc"));
        // 命令注入面：分号等元字符不属于纯数字，同样拒绝
        eq("key_injection_rejected", "ERR arg", rawCmd("KEY " + box.token() + " 23;reboot"));
    }

    static void tc_text_chinese_space_quote() throws Exception {
        LanClient lan = newClient();
        try {
            lan.text("你好 world it's"); // 不抛异常即通过
            record("text_ok", true, "TEXT 中文/空格/单引号应返回 OK");
            eq("text_raw_decoded", "你好 world it's", box.lastTextRaw);
            // SPEC-014：ACTION_SET_TEXT 原样写入，不再做 shell 转义
            eq("text_processed", "你好 world it's", box.lastTextProcessed);
        } finally {
            lan.close();
        }
    }

    static void tc_text_double_quote_kept() throws Exception {
        LanClient lan = newClient();
        try {
            lan.text("a\"b"); // 双引号在单引号包裹内是字面量，服务端不改动
            eq("text_double_quote_kept", "a\"b", box.lastTextProcessed);
        } finally {
            lan.close();
        }
    }

    static void tc_text_wrong_token() throws Exception {
        eq("text_wrong_token", "ERR auth",
                rawCmd("TEXT deadbeef " + b64("hi")));
    }

    static void tc_text_invalid_base64() throws Exception {
        // 非法 base64 解码抛 IllegalArgumentException，响应为 ERR <异常类名>
        eq("text_invalid_base64", "ERR IllegalArgumentException",
                rawCmd("TEXT " + box.token() + " !!!not-base64!!!"));
    }

    static void tc_text_empty() throws Exception {
        // 空文本 base64 后为空串，整行 trim 后缺第三段，按协议返回 ERR arg
        LanClient lan = newClient();
        try {
            expectThrow("text_empty_err_arg", "ERR arg", () -> lan.text(""));
        } finally {
            lan.close();
        }
    }

    static void tc_text_large() throws Exception {
        // SPEC-006：解码后超过 256 字符返回 ERR toolong
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 257; i++) sb.append("测");
        LanClient lan = newClient();
        try {
            expectThrow("text_large_err_toolong", "ERR toolong", () -> lan.text(sb.toString()));
        } finally {
            lan.close();
        }
    }

    /** SPEC-006：边界值 256 字符应通过 */
    static void tc_text_boundary() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 256; i++) sb.append("测");
        LanClient lan = newClient();
        try {
            lan.text(sb.toString());
            record("text_boundary_256_ok", true, "解码后 256 字符应返回 OK");
        } finally {
            lan.close();
        }
    }

    /** SPEC-006：base64 段超过 2048 直接拒绝，不做解码 */
    static void tc_text_b64_too_long() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2100; i++) sb.append('A');
        eq("text_b64_too_long", "ERR toolong",
                rawCmd("TEXT " + box.token() + " " + sb));
    }

    static void tc_app_ok() throws Exception {
        LanClient lan = newClient();
        try {
            lan.launch("com.example.tvapp");
            record("app_ok", true, "APP 合法包名应返回 OK");
            eq("app_records_pkg", "com.example.tvapp", box.lastPkg);
            eq("app_via_intent", "intent", box.launchVia);
        } finally {
            lan.close();
        }
    }

    static void tc_app_settings_action() throws Exception {
        LanClient lan = newClient();
        try {
            lan.launch("com.android.settings");
            eq("app_settings_via_action", "action", box.launchVia);
        } finally {
            lan.close();
        }
    }

    static void tc_app_invalid_pkg() throws Exception {
        LanClient lan = newClient();
        try {
            // SPEC-006：非法包名在协议层直接按参数错误拒绝
            expectThrow("app_invalid_pkg", "ERR arg", () -> lan.launch("bad pkg;reboot"));
        } finally {
            lan.close();
        }
    }

    /** SPEC-006：包名长度上限 128 */
    static void tc_app_pkg_too_long() throws Exception {
        StringBuilder sb = new StringBuilder("com.example.");
        while (sb.length() <= 128) sb.append('a');
        eq("app_pkg_too_long", "ERR arg", rawCmd("APP " + box.token() + " " + sb));
    }

    static void tc_app_wrong_token() throws Exception {
        eq("app_wrong_token", "ERR auth",
                rawCmd("APP deadbeef com.example.tvapp"));
    }

    /** SPEC-006：同一 IP 连续 5 次错误配对码后锁定，锁定期正确码也拒绝 */
    static void tc_pair_lockout() throws Exception {
        box.resetPairGuard();
        try {
            for (int i = 1; i <= 5; i++) {
                eq("pair_fail_" + i, "ERR badcode", rawCmd("PAIR 000000"));
            }
            eq("pair_locked_after_5", "ERR locked", rawCmd("PAIR 000000"));
            eq("pair_correct_while_locked", "ERR locked", rawCmd("PAIR 123456"));
        } finally {
            box.resetPairGuard();
        }
    }

    static void tc_unknown_cmd() throws Exception {
        // v1.0 实际行为：认证先于命令字识别 —— 不带 token 的未知命令返回 ERR auth，
        // 带有效 token 的未知命令才返回 ERR cmd（spec 已按此修正）
        eq("unknown_cmd_no_token", "ERR auth", rawCmd("HELLO"));
        eq("unknown_cmd_with_token", "ERR cmd", rawCmd("HELLO " + box.token()));
    }

    static void tc_empty_line() throws Exception {
        // v1.0 实际行为：空行 trim 后 split 得到单元素数组，
        // 落入认证分支返回 ERR auth（"ERR empty" 分支为不可达死代码，已记录在 spec）
        eq("empty_line_is_auth", "ERR auth", rawCmd(""));
    }

    // ---------------- 连接管理 ----------------

    /** SPEC-016：LIST 返回「包名|base64(应用名)」，逗号分隔 */
    static void tc_list_ok() throws Exception {
        box.packages = "com.fongmi.android.tv|" + b64("OK影视")
                + ",com.trim.tv|" + b64("飞牛TV")
                + ",org.xbmc.kodi19DV|" + b64("Kodi");
        try {
            LanClient lan = newClient();
            String pkgs = lan.list();
            check("list_ok", pkgs.contains("com.fongmi.android.tv|")
                    && pkgs.contains("com.trim.tv|") && pkgs.contains("org.xbmc.kodi19DV|"),
                    "LIST 应返回逗号分隔的「包名|base64(应用名)」");
            String label = new String(java.util.Base64.getDecoder().decode(
                    pkgs.split(",")[0].split("\\|")[1]),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("list_label_decode", "OK影视".equals(label),
                    "应用名 Base64 解码后应为中文标签");
            lan.close();
        } finally {
            box.packages = "";
        }
    }

    static void tc_list_wrong_token() throws Exception {
        String resp = rawCmd("LIST badtoken");
        check("list_wrong_token", "ERR auth".equals(resp), "LIST 错 token 应返回 ERR auth");
    }

    static void tc_list_empty() throws Exception {
        LanClient lan = newClient();
        String pkgs = lan.list();
        check("list_empty", pkgs.isEmpty(), "无第三方应用时 LIST 应返回 OK + 空串");
        lan.close();
    }

    /** SPEC-019：ICON 返回 PNG 的 Base64，客户端应解码出与盒端一致的字节 */
    static void tc_icon_ok() throws Exception {
        LanClient lan = newClient();
        try {
            byte[] png = lan.icon(box.iconPkg);
            byte[] expect = java.util.Base64.getDecoder().decode(box.iconBase64);
            check("icon_ok", java.util.Arrays.equals(expect, png),
                    "ICON 返回的 Base64 解码后应与盒端 PNG 一致");
        } finally {
            lan.close();
        }
    }

    static void tc_icon_unknown_pkg() throws Exception {
        eq("icon_unknown_pkg", "ERR arg",
                rawCmd("ICON " + box.token() + " com.not.exist"));
    }

    static void tc_icon_missing_pkg() throws Exception {
        eq("icon_missing_pkg", "ERR arg", rawCmd("ICON " + box.token()));
    }

    static void tc_icon_wrong_token() throws Exception {
        eq("icon_wrong_token", "ERR auth",
                rawCmd("ICON deadbeef " + box.iconPkg));
    }

    /** SPEC-019：图标只读 PackageManager，不受无障碍开关影响（与 LIST 一致） */
    static void tc_icon_without_a11y() throws Exception {
        box.a11yEnabled = false;
        try {
            String resp = rawCmd("ICON " + box.token() + " " + box.iconPkg);
            check("icon_without_a11y", resp.startsWith("OK "),
                    "无障碍关闭时 ICON 仍应返回 OK，实际 " + resp);
        } finally {
            box.a11yEnabled = true;
        }
    }

    /** SPEC-014：未知 keycode 明确返回 ERR cmd，不再假装执行成功 */
    static void tc_key_unsupported() throws Exception {
        eq("key_unsupported", "ERR cmd", rawCmd("KEY " + box.token() + " 99"));
        eq("key_unsupported_numeric", "ERR cmd", rawCmd("KEY " + box.token() + " 9999"));
    }

    /** SPEC-018：KEYLONG 仅支持 OK（23），走无障碍 ACTION_LONG_CLICK */
    static void tc_keylong_ok() throws Exception {
        LanClient lan = newClient();
        try {
            lan.keyLong(23); // 不抛异常即通过
            record("keylong_ok", true, "KEYLONG 23 应返回 OK");
            eq("keylong_records_keycode", "23", box.lastLongKeycode);
        } finally {
            lan.close();
        }
    }

    static void tc_keylong_unsupported() throws Exception {
        eq("keylong_unsupported", "ERR cmd", rawCmd("KEYLONG " + box.token() + " 19"));
    }

    static void tc_keylong_missing_code() throws Exception {
        eq("keylong_missing_code", "ERR arg", rawCmd("KEYLONG " + box.token()));
        eq("keylong_non_numeric", "ERR arg", rawCmd("KEYLONG " + box.token() + " abc"));
    }

    static void tc_keylong_wrong_token() throws Exception {
        eq("keylong_wrong_token", "ERR auth", rawCmd("KEYLONG deadbeef 23"));
    }

    static void tc_keylong_a11y_off() throws Exception {
        box.a11yEnabled = false;
        try {
            eq("keylong_a11y_off", "ERR a11y", rawCmd("KEYLONG " + box.token() + " 23"));
        } finally {
            box.a11yEnabled = true;
        }
    }

    /** SPEC-014：无障碍关闭时，仅需要无障碍的命令失败；音量/APP/LIST 不受影响 */
    static void tc_a11y_off() throws Exception {
        box.a11yEnabled = false;
        try {
            eq("key_a11y_off", "ERR a11y", rawCmd("KEY " + box.token() + " 19"));
            eq("key_volume_without_a11y", "OK", rawCmd("KEY " + box.token() + " 24"));
            eq("key_mute_without_a11y", "OK", rawCmd("KEY " + box.token() + " 164"));
            eq("text_a11y_off", "ERR a11y", rawCmd("TEXT " + box.token() + " " + b64("hi")));
            box.packages = "com.example.app|" + b64("Example");
            eq("list_without_a11y", "OK com.example.app|" + b64("Example"),
                    rawCmd("LIST " + box.token()));
            eq("app_without_a11y", "OK", rawCmd("APP " + box.token() + " com.example.app"));
        } finally {
            box.a11yEnabled = true;
            box.packages = "";
        }
    }

    static void tc_keys_in_sequence() throws Exception {
        LanClient lan = newClient();
        try {
            lan.key(19);
            lan.key(20);
            lan.key(23);
            record("keys_in_sequence", true, "连发 3 条 KEY 应全部 OK（每条命令独立短连接）");
        } finally {
            lan.close();
        }
    }

    static void tc_reconnect_after_close() throws Exception {
        LanClient lan = newClient();
        try {
            lan.key(21);
            lan.close();
            lan.key(22); // SPEC-011 短连接：每条命令独立建立连接，close 后发命令不受影响
            record("reconnect_after_close", true, "close 后再发命令应正常成功");
        } finally {
            lan.close();
        }
    }

    static void tc_reconnect_after_kick() throws Exception {
        LanClient lan = newClient();
        try {
            lan.key(19);
            box.kickAll(); // 服务端断开既有连接（模拟盒子重启）
            // SPEC-011 短连接：每条命令新建连接，被踢后下一条命令直接成功
            lan.key(20);
            record("reconnect_after_kick", true, "服务端踢连接后下一条命令应正常成功");
        } finally {
            lan.close();
        }
    }

    static void tc_concurrent_clients() throws Exception {
        LanClient a = newClient(), b = newClient();
        try {
            a.key(19);
            b.key(20);
            a.text("并发");
            record("concurrent_clients", true, "两个客户端并发使用同一 token 应互不影响");
        } finally {
            a.close();
            b.close();
        }
    }

    static void tc_connect_refused() throws Exception {
        // 找一个当前空闲的端口（连接会被拒绝）
        int free;
        try (ServerSocket ss = new ServerSocket(0)) { free = ss.getLocalPort(); }
        expectThrow("connect_refused", null,
                () -> LanClient.pair("127.0.0.1", free, "123456", 1500));
    }

    static void tc_connect_unreachable() throws Exception {
        // TEST-NET-3 保留网段，正常网络下不可达，应在超时内抛异常
        expectThrow("connect_unreachable", null,
                () -> LanClient.pair("203.0.113.1", 8899, "123456", 800));
    }

    // ---------------- SPEC-021 飞鼠 ----------------

    static void tc_move_ok() throws Exception {
        eq("move_ok", "OK", rawCmd("MOVE " + box.token() + " 3 -4"));
        check("move_record",
                box.lastDx == 3 && box.lastDy == -4,
                "服务端应记录位移 3/-4，实际 " + box.lastDx + "/" + box.lastDy);
        // SPEC-023：单轴上限放宽到 127
        eq("move_max_ok", "OK", rawCmd("MOVE " + box.token() + " 127 -127"));
        check("move_max_record",
                box.lastDx == 127 && box.lastDy == -127,
                "服务端应记录位移 127/-127，实际 " + box.lastDx + "/" + box.lastDy);
    }

    static void tc_move_invalid() throws Exception {
        eq("move_missing", "ERR arg", rawCmd("MOVE " + box.token()));
        eq("move_one_arg", "ERR arg", rawCmd("MOVE " + box.token() + " 3"));
        eq("move_non_numeric", "ERR arg", rawCmd("MOVE " + box.token() + " a b"));
        eq("move_out_of_range", "ERR arg", rawCmd("MOVE " + box.token() + " 128 0"));
        eq("move_wrong_token", "ERR auth", rawCmd("MOVE deadbeef 1 1"));
    }

    static void tc_move_a11y_off() throws Exception {
        box.a11yEnabled = false;
        try {
            eq("move_a11y_off", "ERR a11y", rawCmd("MOVE " + box.token() + " 1 1"));
            eq("mclick_a11y_off", "ERR a11y", rawCmd("MCLICK " + box.token() + " 1"));
        } finally {
            box.a11yEnabled = true;
        }
    }

    static void tc_mclick() throws Exception {
        eq("mclick_up_idle", "OK", rawCmd("MCLICK " + box.token() + " 0"));
        eq("mclick_down", "OK", rawCmd("MCLICK " + box.token() + " 1"));
        check("mclick_down_state", box.mouseDown, "按下后应记录 mouseDown=true");
        eq("mclick_up", "OK", rawCmd("MCLICK " + box.token() + " 0"));
        check("mclick_up_state", !box.mouseDown, "抬起后应记录 mouseDown=false");
        eq("mclick_invalid", "ERR arg", rawCmd("MCLICK " + box.token() + " 2"));
        eq("mclick_missing", "ERR arg", rawCmd("MCLICK " + box.token()));
        eq("mclick_wrong_token", "ERR auth", rawCmd("MCLICK deadbeef 1"));
    }

    /** SPEC-023：光标处单击 */
    static void tc_mtap() throws Exception {
        box.resetTouchRecords();
        eq("mtap_ok", "OK", rawCmd("MTAP " + box.token()));
        check("mtap_record", box.tapCount == 1, "应记录一次单击，实际 " + box.tapCount);
        eq("mtap_wrong_token", "ERR auth", rawCmd("MTAP deadbeef"));
        box.a11yEnabled = false;
        try {
            eq("mtap_a11y_off", "ERR a11y", rawCmd("MTAP " + box.token()));
        } finally {
            box.a11yEnabled = true;
        }
    }

    /** SPEC-023：光标处长按 */
    static void tc_mlong() throws Exception {
        box.resetTouchRecords();
        eq("mlong_ok", "OK", rawCmd("MLONG " + box.token()));
        check("mlong_record", box.longTapCount == 1,
                "应记录一次长按，实际 " + box.longTapCount);
        eq("mlong_wrong_token", "ERR auth", rawCmd("MLONG deadbeef"));
        box.a11yEnabled = false;
        try {
            eq("mlong_a11y_off", "ERR a11y", rawCmd("MLONG " + box.token()));
        } finally {
            box.a11yEnabled = true;
        }
    }

    /** SPEC-023：退出触控板立即隐藏光标 */
    static void tc_mhide() throws Exception {
        box.resetTouchRecords();
        eq("mhide_ok", "OK", rawCmd("MHIDE " + box.token()));
        check("mhide_record", box.hideCount == 1, "应记录一次隐藏，实际 " + box.hideCount);
        eq("mhide_extra_arg", "ERR arg", rawCmd("MHIDE " + box.token() + " 1"));
        eq("mhide_wrong_token", "ERR auth", rawCmd("MHIDE deadbeef"));
        box.a11yEnabled = false;
        try {
            eq("mhide_a11y_off", "ERR a11y", rawCmd("MHIDE " + box.token()));
        } finally {
            box.a11yEnabled = true;
        }
    }

    /** SPEC-023：滚动方向与范围 */
    static void tc_scroll() throws Exception {
        box.resetTouchRecords();
        eq("scroll_ok", "OK", rawCmd("SCROLL " + box.token() + " 1"));
        check("scroll_record_down", box.lastScroll == 1,
                "正数应记录为向下/向前，实际 " + box.lastScroll);
        eq("scroll_up_ok", "OK", rawCmd("SCROLL " + box.token() + " -2"));
        check("scroll_record_up", box.lastScroll == -2,
                "负数应记录为向上/向后，实际 " + box.lastScroll);
        check("scroll_count", box.scrollCount == 2,
                "两次滚动应记录 2 次，实际 " + box.scrollCount);
        eq("scroll_max_ok", "OK", rawCmd("SCROLL " + box.token() + " 5"));
        eq("scroll_over", "ERR arg", rawCmd("SCROLL " + box.token() + " 6"));
        eq("scroll_under", "ERR arg", rawCmd("SCROLL " + box.token() + " -6"));
        eq("scroll_non_numeric", "ERR arg", rawCmd("SCROLL " + box.token() + " a"));
        eq("scroll_missing", "ERR arg", rawCmd("SCROLL " + box.token()));
        eq("scroll_wrong_token", "ERR auth", rawCmd("SCROLL deadbeef 1"));
        box.a11yEnabled = false;
        try {
            eq("scroll_a11y_off", "ERR a11y", rawCmd("SCROLL " + box.token() + " 1"));
        } finally {
            box.a11yEnabled = true;
        }
    }

    /** SPEC-023：TouchSession 单连接内连续移动 + 点击 + 滚动全部到达 */
    static void tc_touch_session() throws Exception {
        box.resetTouchRecords();
        int before = box.connectionCount;
        TouchSession ts = new TouchSession("127.0.0.1", box.port(), box.token(), null);
        try {
            ts.move(200, -150); // 超过 127，应分批发送，不丢位移
            ts.move(10, 10);
            ts.tap();
            ts.scroll(2);
            long end = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < end
                    && (box.moveCount < 2 || box.tapCount < 1 || box.scrollCount < 1)) {
                Thread.sleep(20);
            }
            check("touch_session_move", box.moveCount >= 2,
                    "分批移动应至少发送 2 条 MOVE，实际 " + box.moveCount);
            check("touch_session_tap", box.tapCount == 1,
                    "应到达一次 MTAP，实际 " + box.tapCount);
            check("touch_session_scroll", box.scrollCount == 1 && box.lastScroll == 2,
                    "应到达一次 SCROLL 2，实际 " + box.scrollCount + "/" + box.lastScroll);
            check("touch_session_single_conn", box.connectionCount - before == 1,
                    "同一会话应只建立 1 条连接，实际 " + (box.connectionCount - before));
        } finally {
            ts.close();
        }
    }

    // ---------------- UDP 发现 ----------------

    static void tc_discover_no_broadcast() throws Exception {
        String ip = LanClient.discover(600);
        check("discover_no_broadcast_null", ip == null, "无广播时 discover 应返回 null，实际 " + ip);
    }

    static void tc_discover_found() throws Exception {
        Thread sender = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                box.broadcastOnce("127.0.0.1", LanClient.DISCOVERY_PORT);
                try { Thread.sleep(100); } catch (InterruptedException ignored) { return; }
            }
        });
        sender.setDaemon(true);
        sender.start();
        String ip = LanClient.discover(2500);
        sender.join(3000);
        eq("discover_found_ip", "127.0.0.1", ip);
    }

    static void tc_discover_other_prefix() throws Exception {
        Thread sender = new Thread(() -> {
            for (int i = 0; i < 8; i++) {
                sendUdp("127.0.0.1", LanClient.DISCOVERY_PORT, "OTHER|1|junk");
                try { Thread.sleep(100); } catch (InterruptedException ignored) { return; }
            }
        });
        sender.setDaemon(true);
        sender.start();
        String ip = LanClient.discover(1200);
        sender.join(2000);
        check("discover_other_prefix_ignored", ip == null,
                "非 TVREMOTE 前缀广播应被忽略，实际 " + ip);
    }

    /** SPEC-007：两台盒子都能入列，同一 IP 重复广播只保留一条 */
    static void tc_discover_dedupe_multi() throws Exception {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        LanClient.collectDiscovery("TVREMOTE|8899|BoxA", "192.168.1.10", out, seen);
        LanClient.collectDiscovery("TVREMOTE|8899|BoxA-dup", "192.168.1.10", out, seen);
        LanClient.collectDiscovery("TVREMOTE|8899|BoxB", "192.168.1.11", out, seen);
        LanClient.collectDiscovery("OTHER|1|junk", "192.168.1.12", out, seen);
        check("discover_multi_count", out.size() == 2,
                "两台盒子去重后应为 2 条，实际 " + out.size());
        check("discover_multi_names", out.size() == 2
                        && "BoxA".equals(out.get(0)[0]) && "BoxB".equals(out.get(1)[0]),
                "设备名应取广播中的名称，实际 " + (out.size() == 2
                        ? out.get(0)[0] + "/" + out.get(1)[0] : "条数不符"));
    }

    // ---------------- NFR ----------------

    static void tc_rtt() throws Exception {
        LanClient lan = newClient();
        try {
            lan.key(19); // 预热（建立连接）
            long t0 = System.currentTimeMillis();
            lan.key(20);
            long dt = System.currentTimeMillis() - t0;
            check("nfr_rtt_under_1000ms", dt < 1000,
                    "本地回环 KEY 往返 " + dt + "ms 应 < 1000ms（局域网预算 4000ms）");
        } finally {
            lan.close();
        }
    }

    // ---------------- 工具方法 ----------------

    static LanClient newClient() {
        return new LanClient("127.0.0.1", box.port(), box.token());
    }

    static String b64(String s) {
        return java.util.Base64.getEncoder()
                .encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 原始行协议交互：发一行、读一行 */
    static String rawCmd(String line) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", box.port()), 3000);
            s.setSoTimeout(3000);
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), "UTF-8"));
            PrintWriter w = new PrintWriter(
                    new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
            w.println(line);
            return r.readLine();
        }
    }

    static void sendUdp(String host, int port, String msg) {
        try (java.net.DatagramSocket ds = new java.net.DatagramSocket()) {
            byte[] data = msg.getBytes("UTF-8");
            ds.send(new java.net.DatagramPacket(data, data.length,
                    java.net.InetAddress.getByName(host), port));
        } catch (Exception ignored) {
        }
    }

    static void record(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name + " —— " + detail);
        }
    }

    static void check(String name, boolean ok, String detail) {
        record(name, ok, detail);
    }

    static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        record(name, ok, "期望 " + expected + "，实际 " + actual);
    }

    static void expectThrow(String name, String msgPart, Thunk t) {
        try {
            t.run();
            record(name, false, "应抛出异常但正常返回");
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            boolean ok = msgPart == null || m.contains(msgPart);
            record(name, ok, "异常消息「" + m + "」应包含「" + msgPart + "」");
        }
    }
}
