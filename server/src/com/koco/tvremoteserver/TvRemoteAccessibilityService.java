package com.koco.tvremoteserver;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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

    public static boolean isReady() {
        return instance != null;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "accessibility service connected");
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
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
            case 23: return dpad(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER, 0);
            default: return RES_UNSUPPORTED;
        }
    }

    private static int global(int action) {
        TvRemoteAccessibilityService s = instance;
        if (s == null) return RES_NO_A11Y;
        return s.performGlobalAction(action) ? RES_OK : RES_FAIL;
    }

    /**
     * 方向键/OK：优先用系统全局动作；部分电视固件（实测 H96 Android 12）
     * performGlobalAction 对 DPAD 返回 false，改用窗口焦点遍历完成。
     */
    private static int dpad(int action, int focusDirection) {
        TvRemoteAccessibilityService s = instance;
        if (s == null) return RES_NO_A11Y;
        if (s.performGlobalAction(action)) return RES_OK;
        return focusDirection == 0 ? s.clickFocused() : s.moveFocus(focusDirection);
    }

    private int moveFocus(int direction) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return RES_FAIL;
        AccessibilityNodeInfo cur = findCurrentFocus(root);
        if (cur == null) {
            // 当前界面没有焦点时，把焦点交给第一个可聚焦节点
            AccessibilityNodeInfo first = findFocusable(root, 0);
            if (first == null) return RES_OK; // 界面无可聚焦元素，视为无操作
            return first.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ? RES_OK : RES_FAIL;
        }
        AccessibilityNodeInfo next = cur.focusSearch(direction);
        if (next == null) return RES_OK; // 已到界面边界，视为无操作
        boolean ok = next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        if (!ok) ok = next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        return ok ? RES_OK : RES_FAIL;
    }

    private int clickFocused() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return RES_FAIL;
        AccessibilityNodeInfo node = findCurrentFocus(root);
        while (node != null && !node.isClickable()) node = node.getParent();
        if (node == null) return RES_OK; // 当前焦点不可点击，视为无操作
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ? RES_OK : RES_FAIL;
    }

    private static AccessibilityNodeInfo findCurrentFocus(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null) node = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        return node;
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
