package com.koco.tvremoteserver;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * 盒端非 root 注入服务（SPEC-014）。
 *
 * 通过系统无障碍接口完成按键与文字注入，不依赖 root：
 *   - 返回/主页/最近任务/锁屏/方向键 → performGlobalAction；
 *   - 音量/静音 → AudioManager；
 *   - 文字 → 输入焦点节点 ACTION_SET_TEXT。
 *
 * 用户需在盒子「设置 → 无障碍」中开启一次，之后由系统常驻绑定。
 * 服务未开启时返回 RES_NO_A11Y，由协议层回 ERR a11y。
 */
public class TvRemoteAccessibilityService extends AccessibilityService {

    private static final String TAG = "TvRemoteA11y";

    /** 执行结果：成功 */
    public static final int RES_OK = 0;
    /** 执行失败（节点不存在、动作被拒等） */
    public static final int RES_FAIL = 1;
    /** 无障碍服务未开启 */
    public static final int RES_NO_A11Y = 2;
    /** 不支持的按键 */
    public static final int RES_UNSUPPORTED = 3;

    private static volatile TvRemoteAccessibilityService instance;

    // SPEC-021：应用层飞鼠光标与触摸状态
    private static final int CURSOR_DP = 24;
    private static final long CURSOR_IDLE_MS = 5000;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hideCursorTask = () -> removeCursor();
    private WindowManager wm;
    private View cursorView;
    private WindowManager.LayoutParams cursorParams;
    private float cx;
    private float cy;
    private boolean down;
    private float downX;
    private float downY;
    private long downAt;
    /** cx/cy 是否已初始化；光标 5 秒隐藏后重建时保持上次位置，不能回到屏幕中心 */
    private boolean cursorInited;

    public static boolean isReady() {
        return instance != null;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onDestroy() {
        cleanupAir();
        instance = null;
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        cleanupAir();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 只做命令注入，不处理事件
    }

    @Override
    public void onInterrupt() {
    }

    /**
     * 执行一个手机端发来的 keycode。
     * 音量/静音不依赖无障碍；其余按键需要无障碍服务已连接。
     */
    public static int key(Context c, int code) {
        switch (code) {
            case 24: return volume(c, AudioManager.ADJUST_RAISE);
            case 25: return volume(c, AudioManager.ADJUST_LOWER);
            case 164: return volume(c, AudioManager.ADJUST_TOGGLE_MUTE);
            case 4: return global(AccessibilityService.GLOBAL_ACTION_BACK);
            case 3: return global(AccessibilityService.GLOBAL_ACTION_HOME);
            // 非 root 没有「菜单键」全局动作，用最近任务替代（SPEC-014 已知限制）
            case 82: return global(AccessibilityService.GLOBAL_ACTION_RECENTS);
            case 26: return global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
            case 19: return dpad(AccessibilityService.GLOBAL_ACTION_DPAD_UP, View.FOCUS_UP);
            case 20: return dpad(AccessibilityService.GLOBAL_ACTION_DPAD_DOWN, View.FOCUS_DOWN);
            case 21: return dpad(AccessibilityService.GLOBAL_ACTION_DPAD_LEFT, View.FOCUS_LEFT);
            case 22: return dpad(AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT, View.FOCUS_RIGHT);
            case 23: return pressKey();
            default: return RES_UNSUPPORTED;
        }
    }

    /**
     * SPEC-018：OK 短按。部分电视桌面（实测当贝桌面）performGlobalAction(DPAD_CENTER)
     * 返回 true 却不产生点击，改用焦点节点点击 + 合成点按手势。
     */
    public static int pressKey() {
        TvRemoteAccessibilityService s = instance;
        if (s == null) return RES_NO_A11Y;
        return s.pressFocused(false);
    }

    /**
     * SPEC-018：长按。只支持 OK（23），对当前焦点节点执行长按。
     * 节点不可长按时视为无操作（RES_OK），与方向键到边界的行为一致；
     * 窗口不暴露节点树时按 SPEC-032 退化为系统 OK 键。
     */
    public static int keyLong(Context c, int code) {
        if (code != 23) return RES_UNSUPPORTED;
        TvRemoteAccessibilityService s = instance;
        if (s == null) return RES_NO_A11Y;
        return s.pressFocused(true);
    }

    /**
     * 对当前焦点执行点按/长按。
     * 先按焦点中心命中可点击/可长按的节点走节点动作（应用内按钮、卡片），
     * 找不到节点时在焦点中心合成触摸手势——部分 TV 桌面只响应触摸，
     * 节点动作返回 true 却不产生实际效果（实测当贝桌面）。
     */
    private int pressFocused(boolean longPress) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            // SPEC-032：部分应用与系统窗口不向无障碍暴露节点树
            // （实测云视听小电视退出弹窗、Android TV 通知面板），
            // 节点路径不可用，直接注入系统 OK 键；长按退化为短按。
            return performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)
                    ? RES_OK : RES_FAIL;
        }
        AccessibilityNodeInfo focus = findCurrentFocus(root);
        if (focus == null) return RES_OK; // 界面没有焦点，视为无操作
        AccessibilityNodeInfo target = findFocusedDescendant(focus, 0);
        if (target == null) return RES_OK;
        Rect b = new Rect();
        target.getBoundsInScreen(b);
        if (b.isEmpty()) return RES_OK;
        final int x = b.centerX();
        final int y = b.centerY();
        int action = longPress ? AccessibilityNodeInfo.ACTION_LONG_CLICK
                : AccessibilityNodeInfo.ACTION_CLICK;
        AccessibilityNodeInfo node = findActionable(target, x, y, action, 0);
        if (node != null) {
            if (node.isFocusable()) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (node.performAction(action)) return RES_OK;
        }
        ui.post(() -> {
            boolean ok = longPress ? dispatchLongPress(x, y) : dispatchTap(x, y);
            // 固件不支持手势注入时退回系统 OK 动作
            if (!ok) performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER);
        });
        return RES_OK;
    }

    /** 在焦点子树内找包含 (x,y) 且支持指定动作的最小节点 */
    private AccessibilityNodeInfo findActionable(
            AccessibilityNodeInfo node, int x, int y, int action, int depth) {
        if (node == null || depth > 4) return null;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (!r.contains(x, y)) return null;
        AccessibilityNodeInfo best = supportsAction(node, action) ? node : null;
        long bestArea = best == null ? Long.MAX_VALUE : area(r);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            Rect cr = new Rect();
            child.getBoundsInScreen(cr);
            if (!cr.contains(x, y) || cr.isEmpty()) continue;
            AccessibilityNodeInfo found = findActionable(child, x, y, action, depth + 1);
            if (found == null) continue;
            Rect fr = new Rect();
            found.getBoundsInScreen(fr);
            if (area(fr) < bestArea) {
                best = found;
                bestArea = area(fr);
            }
        }
        return best;
    }

    // ---------------- SPEC-021 飞鼠光标 ----------------

    /** 相对移动光标；dx/dy 已由协议层限制在 -127..127 */
    public static int airMove(int dx, int dy) {
        TvRemoteAccessibilityService s = instance;
        if (s == null) return RES_NO_A11Y;
        s.moveCursor(dx, dy);
        return RES_OK;
    }

    /** SPEC-023：光标处单击，命中可点击节点时走节点动作，否则合成点按手势 */
    public static int airTap() {
        TvRemoteAccessibilityService s = instance;
        if (s == null) return RES_NO_A11Y;
        return s.tapCursor();
    }

    /** SPEC-023：光标处长按，命中可长按节点时走节点动作，否则合成 600ms 按住手势 */
    public static int airLongTap() {
        TvRemoteAccessibilityService s = instance;
        if (s == null) return RES_NO_A11Y;
        return s.longTapCursor();
    }

    /** SPEC-023：滚动，正数向下/向前；命中可滚动节点时走节点动作，否则合成短滑动手势 */
    public static int airScroll(int steps) {
        TvRemoteAccessibilityService s = instance;
        if (s == null) return RES_NO_A11Y;
        return s.scrollCursor(steps);
    }

    /** 左键：down=true 记录按下位置，down=false 完成点击或拖动 */
    public static int airClick(boolean downState) {
        TvRemoteAccessibilityService s = instance;
        if (s == null) return RES_NO_A11Y;
        return s.clickCursor(downState);
    }

    /** 由 ServerService 停止或 MHIDE 调用，确保光标与按压状态不残留 */
    public static int hideAirCursor() {
        TvRemoteAccessibilityService s = instance;
        if (s == null) return RES_NO_A11Y;
        s.ui.post(s::cleanupAir);
        return RES_OK;
    }

    // ---------------- SPEC-028 绝对触摸 ----------------

    /** 绝对点按：nx/ny 为 0..10000 的归一化屏幕坐标 */
    public static int touchTap(int nx, int ny) {
        TvRemoteAccessibilityService s = instance;
        if (s == null) return RES_NO_A11Y;
        return s.absoluteTap(nx, ny);
    }

    /** 绝对长按：nx/ny 为 0..10000 的归一化屏幕坐标 */
    public static int touchLong(int nx, int ny) {
        TvRemoteAccessibilityService s = instance;
        if (s == null) return RES_NO_A11Y;
        return s.absoluteLong(nx, ny);
    }

    /** 绝对滑动：起点/终点归一化坐标 + 时长（毫秒） */
    public static int touchSwipe(int nx1, int ny1, int nx2, int ny2, int ms) {
        TvRemoteAccessibilityService s = instance;
        if (s == null) return RES_NO_A11Y;
        return s.absoluteSwipe(nx1, ny1, nx2, ny2, ms);
    }

    /**
     * 当前屏幕像素尺寸。
     * H96 实测：服务进程的 Resources / Display / 窗口指标仍是物理分辨率 1280x960，
     * 而节点边界与手势坐标按窗口覆盖后的 2048x1536 计算，直接用前者会让
     * 点击落点整体偏移。因此以当前窗口根节点边界（与节点同一坐标系）为准。
     */
    private int[] screenSize() {
        int w = 0;
        int h = 0;
        // 优先用当前窗口根节点边界：它与节点坐标同一坐标系（窗口覆盖后的真实分辨率）
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            Rect r = new Rect();
            root.getBoundsInScreen(r);
            w = r.right;
            h = r.bottom;
        }
        if (w <= 0 || h <= 0) {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                Rect r = new Rect();
                for (AccessibilityWindowInfo win : windows) {
                    win.getBoundsInScreen(r);
                    w = Math.max(w, r.right);
                    h = Math.max(h, r.bottom);
                }
            }
        }
        if (w <= 0 || h <= 0) {
            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wmService = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wmService != null) {
                wmService.getDefaultDisplay().getRealMetrics(dm);
            }
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        return new int[]{w, h};
    }

    /** 归一化坐标转当前显示像素坐标，边界夹取到屏幕内 */
    private float[] toScreen(int nx, int ny) {
        int[] size = screenSize();
        int w = size[0];
        int h = size[1];
        return new float[]{
                nx * (w - 1) / 10000f,
                ny * (h - 1) / 10000f
        };
    }

    private int absoluteTap(int nx, int ny) {
        float[] p = toScreen(nx, ny);
        final float x = p[0];
        final float y = p[1];
        AccessibilityNodeInfo node = hitNode(x, y, AccessibilityNodeInfo.ACTION_CLICK);
        // 跳过整屏可点击容器（桌面根布局常带 clickable，点它不会命中具体条目）
        if (node != null && !isNearlyFullScreen(node)) {
            if (node.isFocusable()) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return RES_OK;
        }
        // 无命中节点时按触摸点合成手势，和手机点屏幕一致
        ui.post(() -> dispatchTap(x, y));
        return RES_OK;
    }

    /** 节点是否几乎覆盖整块屏幕（>80%），这类容器不应作为点击目标 */
    private boolean isNearlyFullScreen(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        int[] size = screenSize();
        return area(r) > (long) size[0] * size[1] * 8 / 10;
    }

    private int absoluteLong(int nx, int ny) {
        float[] p = toScreen(nx, ny);
        final float x = p[0];
        final float y = p[1];
        AccessibilityNodeInfo node = hitNode(x, y, AccessibilityNodeInfo.ACTION_LONG_CLICK);
        if (node != null) {
            if (node.isFocusable()) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return RES_OK;
        }
        ui.post(() -> dispatchLongPress(x, y));
        return RES_OK;
    }

    /**
     * 绝对滑动：竖直滑动先尝试对起点下方可滚动节点做步进滚动（不依赖合成手势，
     * 部分固件不支持 dispatchGesture），否则合成从起点到终点的滑动手势。
     */
    private int absoluteSwipe(int nx1, int ny1, int nx2, int ny2, int ms) {
        float[] a = toScreen(nx1, ny1);
        float[] b = toScreen(nx2, ny2);
        float dx = b[0] - a[0];
        float dy = b[1] - a[1];
        float density = getResources().getDisplayMetrics().density;
        if (Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) >= 8 * density) {
            AccessibilityNodeInfo node =
                    hitNode(a[0], a[1], AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            if (node != null) {
                int steps = Math.max(1, Math.min(3,
                        Math.round(Math.abs(dy) / (80 * density))));
                int action = dy < 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                boolean ok = false;
                for (int i = 0; i < steps; i++) {
                    if (!node.performAction(action)) break;
                    ok = true;
                }
                if (ok) return RES_OK;
            }
        }
        final float x1 = a[0];
        final float y1 = a[1];
        final float x2 = b[0];
        final float y2 = b[1];
        final long duration = Math.max(50, Math.min(1500, ms));
        ui.post(() -> dispatchDrag(x1, y1, x2, y2, duration));
        return RES_OK;
    }

    /**
     * 光标坐标在调用线程内同步更新（同一连接内 MOVE 后紧跟 MTAP 必须看到新位置），
     * 窗口布局与拖动分段投递到 UI 线程，按投递顺序执行。
     */
    private void moveCursor(int dx, int dy) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        final float nx;
        final float ny;
        final float sx;
        final float sy;
        final boolean drag;
        synchronized (this) {
            cx = clamp(cx + dx, 0, w - 1);
            cy = clamp(cy + dy, 0, h - 1);
            nx = cx;
            ny = cy;
            drag = down;
            sx = downX;
            sy = downY;
            if (down) {
                downX = cx;
                downY = cy;
            }
        }
        ui.post(() -> {
            ensureCursor();
            layoutCursor(nx, ny);
            scheduleHide();
            if (drag) dispatchDrag(sx, sy, nx, ny, 40);
        });
    }

    private int clickCursor(boolean downState) {
        if (downState) {
            synchronized (this) {
                down = true;
                downX = cx;
                downY = cy;
                downAt = SystemClock.uptimeMillis();
            }
            ui.post(() -> {
                ensureCursor();
                scheduleHide();
            });
            return RES_OK;
        }
        final float x;
        final float y;
        final float sx;
        final float sy;
        final long elapsed;
        synchronized (this) {
            if (!down) return RES_OK; // 未按下时抬起视为无操作
            down = false;
            x = cx;
            y = cy;
            sx = downX;
            sy = downY;
            elapsed = Math.max(50, SystemClock.uptimeMillis() - downAt);
        }
        final boolean tap = Math.abs(x - sx) < 3 && Math.abs(y - sy) < 3;
        // 手势统一投递到 UI 线程串行执行，避免与前面投递的拖动分段抢占触摸通道
        ui.post(() -> {
            if (tap) {
                dispatchTap(x, y);
            } else {
                dispatchDrag(sx, sy, x, y, Math.min(600, elapsed));
            }
            scheduleHide();
        });
        return RES_OK;
    }

    /** SPEC-023：节点优先的单击 */
    private int tapCursor() {
        float x;
        float y;
        synchronized (this) {
            x = cx;
            y = cy;
        }
        ui.post(() -> {
            ensureCursor();
            layoutCursor(x, y);
            scheduleHide();
        });
        AccessibilityNodeInfo node = hitNode(x, y, AccessibilityNodeInfo.ACTION_CLICK);
        if (node != null) {
            if (node.isFocusable()) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return RES_OK;
        }
        ui.post(() -> dispatchTap(x, y));
        return RES_OK;
    }

    /** SPEC-023：节点优先的长按 */
    private int longTapCursor() {
        float x;
        float y;
        synchronized (this) {
            x = cx;
            y = cy;
        }
        ui.post(() -> {
            ensureCursor();
            layoutCursor(x, y);
            scheduleHide();
        });
        AccessibilityNodeInfo node = hitNode(x, y, AccessibilityNodeInfo.ACTION_LONG_CLICK);
        if (node != null) {
            if (node.isFocusable()) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return RES_OK;
        }
        ui.post(() -> dispatchLongPress(x, y));
        return RES_OK;
    }

    /** SPEC-023：节点优先的滚动 */
    private int scrollCursor(int steps) {
        if (steps == 0) return RES_OK;
        float x;
        float y;
        synchronized (this) {
            x = cx;
            y = cy;
        }
        ui.post(() -> {
            ensureCursor();
            layoutCursor(x, y);
            scheduleHide();
        });
        AccessibilityNodeInfo node = hitNode(x, y, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        if (node != null) {
            int action = steps > 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            boolean ok = false;
            for (int i = 0; i < Math.abs(steps); i++) {
                if (!node.performAction(action)) break;
                ok = true;
            }
            if (ok) return RES_OK;
        }
        ui.post(() -> dispatchScrollSwipe(x, y, steps));
        return RES_OK;
    }

    /**
     * 取包含 (x,y) 的最小面积节点，并要求该节点或最近两级祖先支持指定动作。
     * 命中点内若只能找到很大的容器（如列表根节点），点击它会误触其它内容，
     * 因此最多上溯两级，找不到就交给合成手势。
     */
    private AccessibilityNodeInfo hitNode(float x, float y, int action) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo hit = smallestNodeAt(root, Math.round(x), Math.round(y), 0);
        AccessibilityNodeInfo n = hit;
        for (int i = 0; i < 3 && n != null; i++) {
            if (supportsAction(n, action)) return n;
            n = n.getParent();
        }
        return null;
    }

    private static boolean supportsAction(AccessibilityNodeInfo n, int action) {
        switch (action) {
            case AccessibilityNodeInfo.ACTION_CLICK: return n.isClickable();
            case AccessibilityNodeInfo.ACTION_LONG_CLICK: return n.isLongClickable();
            case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD: return n.isScrollable();
            default: return false;
        }
    }

    private static AccessibilityNodeInfo smallestNodeAt(
            AccessibilityNodeInfo node, int x, int y, int depth) {
        if (node == null || depth > 30) return null;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (!r.contains(x, y)) return null;
        AccessibilityNodeInfo best = node;
        long bestArea = area(r);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            Rect cr = new Rect();
            child.getBoundsInScreen(cr);
            if (!cr.contains(x, y) || cr.isEmpty()) continue;
            AccessibilityNodeInfo found = smallestNodeAt(child, x, y, depth + 1);
            if (found != null) {
                Rect fr = new Rect();
                found.getBoundsInScreen(fr);
                if (area(fr) < bestArea) {
                    best = found;
                    bestArea = area(fr);
                }
            }
        }
        return best;
    }

    private static long area(Rect r) {
        return (long) r.width() * r.height();
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 在 UI 线程创建覆盖层光标；返回是否已创建 */
    private boolean ensureCursorSync() {
        if (cursorView != null) return true;
        try {
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return false;
            int size = Math.round(CURSOR_DP * getResources().getDisplayMetrics().density);
            cursorView = new CursorView(this, size);
            cursorParams = new WindowManager.LayoutParams(
                    size, size,
                    Build.VERSION.SDK_INT >= 22
                            ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT);
            cursorParams.gravity = Gravity.TOP | Gravity.START;
            synchronized (this) {
                if (!cursorInited) {
                    cx = getResources().getDisplayMetrics().widthPixels / 2f;
                    cy = getResources().getDisplayMetrics().heightPixels / 2f;
                    cursorInited = true;
                }
            }
            cursorParams.x = Math.round(cx);
            cursorParams.y = Math.round(cy);
            wm.addView(cursorView, cursorParams);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cursor add failed: " + e.getMessage());
            cursorView = null;
            return false;
        }
    }

    private void ensureCursor() {
        if (cursorView == null) ensureCursorSync();
    }

    private void layoutCursor(float x, float y) {
        if (cursorView == null || cursorParams == null || wm == null) return;
        try {
            cursorParams.x = Math.round(x);
            cursorParams.y = Math.round(y);
            wm.updateViewLayout(cursorView, cursorParams);
        } catch (Exception e) {
            removeCursor();
        }
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideCursorTask);
        ui.postDelayed(hideCursorTask, CURSOR_IDLE_MS);
    }

    private void removeCursor() {
        ui.removeCallbacks(hideCursorTask);
        if (cursorView != null && wm != null) {
            try { wm.removeView(cursorView); } catch (Exception ignored) {}
        }
        cursorView = null;
        cursorParams = null;
        synchronized (this) {
            down = false;
        }
    }

    private void cleanupAir() {
        ui.removeCallbacks(hideCursorTask);
        removeCursor();
        synchronized (this) {
            down = false;
        }
    }

    /** SPEC-023：屏幕旋转/分辨率变化后把光标夹回新屏幕范围 */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        final float x;
        final float y;
        synchronized (this) {
            cx = clamp(cx, 0, w - 1);
            cy = clamp(cy, 0, h - 1);
            x = cx;
            y = cy;
        }
        ui.post(() -> {
            if (cursorView != null) layoutCursor(x, y);
        });
    }

    private boolean dispatchTap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, 60);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),
                null, null);
    }

    private boolean dispatchDrag(float x1, float y1, float x2, float y2, long duration) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, Math.max(20, duration));
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),
                null, null);
    }

    /** SPEC-023：合成 600ms 长按 */
    private boolean dispatchLongPress(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, 600);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),
                null, null);
    }

    /** SPEC-023：合成短滑动；正数向上滑（内容下移/向前），负数向下滑 */
    private boolean dispatchScrollSwipe(float x, float y, int steps) {
        float unit = 80 * getResources().getDisplayMetrics().density;
        float dist = Math.min(3, Math.abs(steps)) * unit;
        float dir = steps > 0 ? -1 : 1;
        Path p = new Path();
        p.moveTo(x, y - dir * dist / 2);
        p.lineTo(x, y + dir * dist / 2);
        long duration = 120 + 40L * (Math.abs(steps) - 1);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, duration);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),
                null, null);
    }

    /** 简易箭头光标：白色三角 + 深色描边，随系统截图可见 */
    private static final class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();

        CursorView(Context c, int size) {
            super(c);
            fill.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
            stroke.setColor(0xCC000000);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(2, size / 12f));
            shape.moveTo(0, 0);
            shape.lineTo(0, size);
            shape.lineTo(size * 0.72f, size * 0.74f);
            shape.close();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawPath(shape, fill);
            canvas.drawPath(shape, stroke);
        }
    }

    private static int global(int action) {
        TvRemoteAccessibilityService s = instance;
        if (s == null) return RES_NO_A11Y;
        return s.performGlobalAction(action) ? RES_OK : RES_FAIL;
    }

    /**
     * 方向键：优先用系统全局动作；部分电视固件（实测 H96 Android 12）
     * performGlobalAction 对 DPAD 返回 false，改用窗口焦点遍历完成。
     */
    private static int dpad(int action, int focusDirection) {
        TvRemoteAccessibilityService s = instance;
        if (s == null) return RES_NO_A11Y;
        if (s.performGlobalAction(action)) return RES_OK;
        return s.moveFocus(focusDirection);
    }

    private int moveFocus(int direction) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return RES_FAIL;
        // 当贝桌面：首页向下、应用列表向上直接翻页。
        // 这两页是同一个 ViewPager 的两页，页面的选中项和边界由桌面自己按键驱动，
        // 无障碍遍历拿到的焦点顺序与空间方向对不上（会误入顶部快捷栏），
        // 因此按当前挂载的页面判定，不再依赖 focusSearch。
        AccessibilityNodeInfo pager = findByIdSuffix(root, ":id/activity_main_view_pager", 0);
        if (pager != null) {
            boolean inList = findByIdSuffix(root, ":id/layout_second_screen_recycler_view", 0) != null;
            if (direction == View.FOCUS_DOWN && !inList) {
                return swipePage(root, true) ? RES_OK : RES_FAIL;
            }
            if (direction == View.FOCUS_UP && inList) {
                return swipePage(root, false) ? RES_OK : RES_FAIL;
            }
        }
        AccessibilityNodeInfo cur = findCurrentFocus(root);
        if (cur == null) {
            // 当前界面没有焦点时，把焦点交给第一个可聚焦节点
            AccessibilityNodeInfo first = findFocusable(root, 0);
            if (first == null) return RES_OK; // 界面无可聚焦元素，视为无操作
            return first.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ? RES_OK : RES_FAIL;
        }
        AccessibilityNodeInfo next = cur.focusSearch(direction);
        if (next != null && next.isVisibleToUser()) {
            boolean ok = next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (!ok) ok = next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            if (ok) return RES_OK;
        }
        return RES_OK;
    }

    /**
     * 翻页：当贝桌面的 ViewPager 没有实现 ACTION_SCROLL，只能用合成竖直滑动。
     * 起点取屏高约 65%（向下）/45%（向上），避开首页底部应用托盘——
     * 滑动起点落在托盘图标上会被桌面当成点击而打开应用。
     * 注意：H96 定制固件不执行无障碍合成手势（dispatchGesture 返回 true 但无输入，
     * 点按与滑动均无效），该机型直连无法翻页，只能走蓝牙 HID（SPEC-026 已知限制）。
     */
    private boolean swipePage(AccessibilityNodeInfo root, boolean down) {
        Rect b = new Rect();
        root.getBoundsInScreen(b);
        if (b.isEmpty()) return false;
        float x = b.centerX();
        float fromY = b.top + b.height() * (down ? 0.65f : 0.45f);
        float toY = b.top + b.height() * (down ? 0.13f : 0.85f);
        Path p = new Path();
        p.moveTo(x, fromY);
        p.lineTo(x, toY);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, 300);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),
                null, null);
    }

    /** 当贝桌面层级很深（图标卡片多层嵌套），深度上限放宽到 60 才能命中页面容器。 */
    private static AccessibilityNodeInfo findByIdSuffix(AccessibilityNodeInfo node,
                                                        String suffix, int depth) {
        if (node == null || depth > 60) return null;
        CharSequence id = node.getViewIdResourceName();
        if (id != null && id.toString().endsWith(suffix)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findByIdSuffix(node.getChild(i), suffix, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo findCurrentFocus(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null) node = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        return node;
    }

    /**
     * 部分 TV 桌面把焦点标记在整个内容容器上，容器中心未必是被选中的条目。
     * 向下取真正 focused 的最小子节点，保证点按/长按落在当前选中项上。
     */
    private static AccessibilityNodeInfo findFocusedDescendant(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 10) return null;
        AccessibilityNodeInfo best = node.isFocused() ? node : null;
        long bestArea = best == null ? Long.MAX_VALUE : areaOf(best);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findFocusedDescendant(node.getChild(i), depth + 1);
            if (found == null) continue;
            long a = areaOf(found);
            if (a < bestArea) {
                best = found;
                bestArea = a;
            }
        }
        return best;
    }

    private static long areaOf(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        return r.isEmpty() ? Long.MAX_VALUE : area(r);
    }

    private static AccessibilityNodeInfo findFocusable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 20) return null;
        if (node.isFocusable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findFocusable(node.getChild(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static int volume(Context c, int direction) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return RES_FAIL;
        try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction,
                    AudioManager.FLAG_SHOW_UI);
            return RES_OK;
        } catch (Exception e) {
            Log.w(TAG, "volume fail", e);
            return RES_FAIL;
        }
    }

    /** 把文字写入当前输入框；无可用输入框返回 RES_FAIL */
    public static int text(String t) {
        TvRemoteAccessibilityService s = instance;
        if (s == null) return RES_NO_A11Y;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return RES_FAIL;
        AccessibilityNodeInfo target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (target == null || !target.isEditable()) target = findEditable(root, 0);
        if (target == null) return RES_FAIL;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t);
        boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        return ok ? RES_OK : RES_FAIL;
    }

    /** 深度优先查找可编辑节点（含子节点），最多 20 层，避免异常层级递归 */
    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 20) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findEditable(child, depth + 1);
            if (found != null) return found;
        }
        return null;
    }
}
