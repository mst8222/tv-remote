package com.koco.tvremote;

import android.content.Context;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 异常到用户可读文案的映射：一句话说清发生了什么，并给出下一步怎么做。
 * 原始异常消息通过 detail() 保留为次要明细。
 */
public final class Errors {

    private Errors() {}

    /** 把异常翻译成「发生了什么 + 怎么办」的本地化文案 */
    public static String friendly(Context c, Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().trim();
        String low = msg.toLowerCase();
        if (e instanceof UnknownHostException) return c.getString(R.string.err_no_host);
        if (e instanceof SocketTimeoutException) return c.getString(R.string.err_timeout);
        if (e instanceof ConnectException) {
            return low.contains("refused") ? c.getString(R.string.err_refused)
                    : c.getString(R.string.err_connect);
        }
        // 直连协议错误（LanClient 把服务器响应原文抛出，形如 "ERR badcode"）
        if (msg.startsWith("ERR ")) {
            if (msg.startsWith("ERR badcode")) return c.getString(R.string.err_badcode);
            if (msg.startsWith("ERR locked")) return c.getString(R.string.err_locked);
            if (msg.startsWith("ERR toolong")) return c.getString(R.string.err_toolong);
            if (msg.startsWith("ERR auth")) return c.getString(R.string.err_auth_lost);
            if (msg.startsWith("ERR a11y")) return c.getString(R.string.err_a11y);
            if (msg.startsWith("ERR exec")) return c.getString(R.string.err_exec);
            return c.getString(R.string.err_protocol, msg);
        }
        if (msg.contains("连接断开") || msg.contains("无响应") || low.contains("broken pipe")
                || low.contains("connection reset") || low.contains("eofexception")) {
            return c.getString(R.string.err_conn_lost);
        }
        return c.getString(R.string.err_generic, detail(e));
    }

    /** 原始异常消息（次要明细，超长省略） */
    public static String detail(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        return m.length() > 26 ? m.substring(0, 26) : m;
    }
}
