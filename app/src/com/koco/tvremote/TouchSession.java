package com.koco.tvremote;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;

/**
 * 触控板专用的直连长连接会话（SPEC-028 触摸 + SPEC-029 鼠标）。
 *
 * 触摸模式一次手势只对应一条绝对坐标命令；鼠标模式移动频率高（20ms 级），
 * 因此单开一条连接 + 独立发送线程：
 *   - 鼠标移动只累加，每 FLUSH_MS 合并发送，单轴超过 127 的部分留到下一次，不丢位移；
 *   - 点击/长按/按下/抬起/滚动按顺序执行，执行前先发送已累计的移动，保证顺序；
 *   - IDLE_CLOSE_MS 无命令自动关闭，其它按键仍走 LanClient 短连接；
 *   - 连接失败自动重连一次，错误经 Listener 回调并节流。
 */
public class TouchSession {

    public interface Listener {
        /** 命令失败或连接异常；同一错误 2 秒内只回调一次 */
        void onError(String message);
    }

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 4000;
    /** 鼠标移动合并发送周期 */
    private static final long FLUSH_MS = 20;
    /** 空闲自动关闭，早于服务器 30 秒回收 */
    private static final long IDLE_CLOSE_MS = 20000;
    /** MOVE 单轴上限，超出部分分批发送 */
    private static final int MOVE_MAX = 127;
    private static final long ERROR_THROTTLE_MS = 2000;

    private final String host;
    private final int port;
    private final String token;
    private final Listener listener;

    private final ArrayDeque<String> queue = new ArrayDeque<>();
    /** 鼠标模式待发送的累计位移 */
    private int pendX = 0;
    private int pendY = 0;

    private volatile boolean closed = false;
    private Socket sock;
    private BufferedReader in;
    private PrintWriter out;

    private String lastError;
    private long lastErrorAt;

    private final Thread worker;

    public TouchSession(String host, int port, String token, Listener listener) {
        this.host = host;
        this.port = port;
        this.token = token;
        this.listener = listener;
        worker = new Thread(this::loop, "touch-session");
        worker.setDaemon(true);
        worker.start();
    }

    /** 鼠标模式：累加相对位移，由发送线程每 20ms 合并发出 */
    public void move(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        synchronized (queue) {
            if (closed) return;
            pendX += dx;
            pendY += dy;
            queue.notifyAll();
        }
    }

    /** 鼠标模式：光标处单击 */
    public void tap() {
        enqueue("MTAP " + token);
    }

    /** 鼠标模式：光标处长按 */
    public void longTap() {
        enqueue("MLONG " + token);
    }

    /** 鼠标模式：按下左键 */
    public void press() {
        enqueue("MCLICK " + token + " 1");
    }

    /** 鼠标模式：释放左键 */
    public void release() {
        enqueue("MCLICK " + token + " 0");
    }

    /** 鼠标模式：滚动，正数向下/向前 */
    public void scroll(int steps) {
        int s = Math.max(-5, Math.min(5, steps));
        if (s == 0) return;
        enqueue("SCROLL " + token + " " + s);
    }

    /** 鼠标模式：0 位移，让盒端显示光标并校验无障碍状态 */
    public void showCursor() {
        enqueue("MOVE " + token + " 0 0");
    }

    /** 绝对点按，x/y 为 0..10000 的归一化屏幕坐标 */
    public void tapAt(int x, int y) {
        enqueue("MTAPN " + token + " " + x + " " + y);
    }

    /** 绝对长按，x/y 为 0..10000 的归一化屏幕坐标 */
    public void longAt(int x, int y) {
        enqueue("MLONGN " + token + " " + x + " " + y);
    }

    /** 绝对滑动，坐标归一化，时长 50..1500ms */
    public void swipe(int x1, int y1, int x2, int y2, long ms) {
        enqueue("MSWIPEN " + token + " " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms);
    }

    /** 进入/退出触控板：让盒端立即隐藏可能残留的旧光标 */
    public void hide() {
        enqueue("MHIDE " + token);
    }

    private void enqueue(String cmd) {
        synchronized (queue) {
            if (closed) return;
            queue.add(cmd);
            queue.notifyAll();
        }
    }

    /**
     * 结束会话：先等待队列与累计位移排空（最多 500ms），确保退出触控板前
     * 发出的 MCLICK 0 真正到达盒子，避免触摸卡在按下状态；随后关闭连接。
     */
    public void close() {
        synchronized (queue) {
            queue.notifyAll();
        }
        long end = System.currentTimeMillis() + 500;
        while (System.currentTimeMillis() < end) {
            synchronized (queue) {
                if (queue.isEmpty() && pendX == 0 && pendY == 0) break;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                break;
            }
        }
        synchronized (queue) {
            closed = true;
            queue.clear();
            pendX = 0;
            pendY = 0;
            queue.notifyAll();
        }
        closeSocket();
        worker.interrupt();
    }

    private void loop() {
        long lastActive = System.currentTimeMillis();
        while (!closed) {
            String cmd = null;
            synchronized (queue) {
                if (queue.isEmpty() && pendX == 0 && pendY == 0) {
                    try {
                        queue.wait(FLUSH_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (!closed && !queue.isEmpty()) cmd = queue.poll();
            }
            if (closed) return;

            boolean hadMove;
            synchronized (queue) {
                hadMove = pendX != 0 || pendY != 0;
            }
            if (!flushMove()) {
                // 发送失败：保持本次命令，等待下一次重试
                if (cmd != null) {
                    synchronized (queue) {
                        if (!closed) queue.addFirst(cmd);
                    }
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            // 有命令或有移动都算活跃，避免拖动过程中被空闲关闭
            if (hadMove) lastActive = System.currentTimeMillis();
            if (cmd != null) {
                lastActive = System.currentTimeMillis();
                sendWithRetry(cmd);
            }
            // 空闲超时：关闭连接，下一条命令重新建连
            if (System.currentTimeMillis() - lastActive > IDLE_CLOSE_MS) {
                closeSocket();
                lastActive = System.currentTimeMillis();
            }
        }
        closeSocket();
    }

    /** 把累计移动按单轴 127 分批发出；失败时把余量放回，返回 false */
    private boolean flushMove() {
        int dx;
        int dy;
        synchronized (queue) {
            dx = pendX;
            dy = pendY;
            pendX = 0;
            pendY = 0;
        }
        while (dx != 0 || dy != 0) {
            int sx = Math.max(-MOVE_MAX, Math.min(MOVE_MAX, dx));
            int sy = Math.max(-MOVE_MAX, Math.min(MOVE_MAX, dy));
            if (!sendWithRetry("MOVE " + token + " " + sx + " " + sy)) {
                synchronized (queue) {
                    pendX += dx;
                    pendY += dy;
                }
                return false;
            }
            dx -= sx;
            dy -= sy;
        }
        return true;
    }

    /** 发送一条命令；IO 失败时重连重试一次，仍失败返回 false */
    private boolean sendWithRetry(String cmd) {
        if (send(cmd) != SendResult.IO_FAIL) return true;
        if (closed) return false;
        return send(cmd) != SendResult.IO_FAIL;
    }

    private enum SendResult { OK, IO_FAIL }

    private SendResult send(String cmd) {
        if (closed) return SendResult.IO_FAIL;
        try {
            if (out == null && !open()) return SendResult.IO_FAIL;
            // 关闭可能发生在建连过程中：此时不能再发送，直接收尾避免连接泄漏
            if (closed) {
                closeSocket();
                return SendResult.IO_FAIL;
            }
            out.println(cmd);
            String resp = in.readLine();
            if (resp == null) throw new Exception("连接断开");
            if (!resp.startsWith("OK")) notifyError(resp);
            return SendResult.OK;
        } catch (Exception e) {
            closeSocket();
            notifyError("连接断开");
            return SendResult.IO_FAIL;
        }
    }

    private boolean open() {
        closeSocket();
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            sock = s;
            in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true);
            return true;
        } catch (Exception e) {
            closeSocket();
            notifyError("连接断开");
            return false;
        }
    }

    private void closeSocket() {
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        sock = null;
        in = null;
        out = null;
    }

    private void notifyError(String message) {
        if (listener == null) return;
        long now = System.currentTimeMillis();
        if (message.equals(lastError) && now - lastErrorAt < ERROR_THROTTLE_MS) return;
        lastError = message;
        lastErrorAt = now;
        listener.onError(message);
    }
}
