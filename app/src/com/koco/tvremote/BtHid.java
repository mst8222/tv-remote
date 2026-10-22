package com.koco.tvremote;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 手机作为蓝牙 HID 键盘/遥控器（Android 9+ / API 28+）。
 * 不依赖网络、不需要开发者选项、配对后重启自动回连。
 * SPEC-018：描述符同时提供键盘、消费者控制与鼠标报告，供飞鼠使用。
 *
 * 注意：Android 文档明确说明「应用退到后台会被系统自动注销 HID 注册」，
 * 因此本类需要配合前台 Activity 使用。
 */
public class BtHid {

    private static final String TAG = "BtHid";

    /** 键盘 Report ID */
    public static final int RID_KEY = 1;
    /** 消费者控制 Report ID（音量等） */
    public static final int RID_CONSUMER = 2;
    /** 鼠标 Report ID（SPEC-018 飞鼠） */
    public static final int RID_MOUSE = 3;

    /** 鼠标按键位：左键（飞鼠模式下 OK 键） */
    public static final int MOUSE_LEFT = 1;

    /** HID 报告描述符（键盘 Report 1 + 消费者控制 Report 2 + 鼠标 Report 3），先用 int 写再转 byte */
    private static final int[] DESC_RAW = {
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01,
            0x85, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7,
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
            0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
            0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01,
            0x85, 0x02,
            0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03,
            0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
            0xC0,
            // SPEC-018 鼠标：按键 3 位 + 相对 X/Y（int8）；SPEC-023 增加滚轮
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01,
            0x85, 0x03,
            0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
            0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
            0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x02, 0x81, 0x06,
            0x09, 0x38,
            0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
            0xC0,
            0xC0
    };

    private static final byte[] DESCRIPTOR = toBytes(DESC_RAW);

    private static byte[] toBytes(int[] src) {
        byte[] b = new byte[src.length];
        for (int i = 0; i < src.length; i++) b[i] = (byte) src[i];
        return b;
    }

    /** HID Usage ID（USB HID Keyboard/Keypad page） */
    public static final int K_UP = 0x52, K_DOWN = 0x51, K_LEFT = 0x50, K_RIGHT = 0x4F,
            K_ENTER = 0x28, K_ESC = 0x29, K_BACKSPACE = 0x2A, K_HOME = 0x4A,
            K_DELETE = 0x4C, K_TAB = 0x2B, K_SPACE = 0x2C;

    /** 消费者控制 Usage */
    public static final int C_VOL_UP = 0xE9, C_VOL_DOWN = 0xEA, C_MUTE = 0xE2;
    /** 消费者控制 AC Home / AC Back：手机类受控端识别为系统主页/返回，
     *  键盘页的 Home(0x4A)/Esc(0x29) 在手机上只会当光标移动（SPEC-005） */
    public static final int C_HOME = 0x0223, C_BACK = 0x0224;

    public interface StateListener {
        void onSupport(boolean supported, String reason);

        void onRegistered(boolean ok);

        void onConnectionChanged(BluetoothDevice dev, boolean connected);
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor exec = command -> main.post(command);

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice target;
    private StateListener listener;
    private boolean registered;
    private boolean supported = true;
    /** SPEC-018：鼠标按键状态位（1=左键） */
    private int mouseButtons = 0;
    /** SPEC-018：两次鼠标报告之间的累计位移与上次发送时间 */
    private int pendX = 0, pendY = 0;
    private long lastMouseAt = 0;
    /** SPEC-023：有待发位移时在下一个周期补发，避免快速滑动丢位移 */
    private boolean drainScheduled = false;

    public BtHid(Context c) {
        this.ctx = c.getApplicationContext();
    }

    public boolean isSupported() { return supported; }

    public boolean isReady() { return hid != null && registered; }

    public BluetoothDevice connectedDevice() { return target; }

    /** 初始化并注册 HID 应用；不支持时会回调 onSupport(false) */
    public void start(StateListener l) {
        this.listener = l;
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            supported = false;
            l.onSupport(false, "这台手机没有蓝牙适配器");
            return;
        }
        if (!adapter.isEnabled()) {
            supported = false;
            l.onSupport(false, "蓝牙未开启，请先打开蓝牙");
            return;
        }
        adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile != BluetoothProfile.HID_DEVICE) return;
                hid = (BluetoothHidDevice) proxy;
                register();
            }

            @Override
            public void onServiceDisconnected(int profile) {
                hid = null;
                registered = false;
            }
        }, BluetoothProfile.HID_DEVICE);
    }

    private void register() {
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "Koco Remote", "TV Remote", "Koco",
                BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR);

        BluetoothHidDevice.Callback cb = new BluetoothHidDevice.Callback() {
            @Override
            public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean reg) {
                registered = reg;
                Log.i(TAG, "onAppStatusChanged registered=" + reg);
                if (listener != null) listener.onRegistered(reg);
            }

            @Override
            public void onConnectionStateChanged(BluetoothDevice device, int state) {
                Log.i(TAG, "onConnectionStateChanged " + device + " -> " + state);
                // 只上报「已连接 / 已断开」，忽略 CONNECTING/DISCONNECTING 中间态，
                // 否则连接过程中会被误判为断开（SPEC-005）
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    target = device;
                    if (listener != null) listener.onConnectionChanged(device, true);
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    // SPEC-018：断开后清掉按键/鼠标残留状态，避免重连后光标或按键卡住
                    mouseButtons = 0;
                    pendX = 0;
                    pendY = 0;
                    if (listener != null) listener.onConnectionChanged(device, false);
                }
            }
        };

        try {
            boolean queued = hid.registerApp(sdp, null, null, exec, cb);
            Log.i(TAG, "registerApp descLen=" + DESCRIPTOR.length + " queued=" + queued);
        } catch (Exception e) {
            supported = false;
            if (listener != null) listener.onSupport(false, "注册 HID 失败：" + e.getMessage());
        }
    }

    /** 连接到已配对的盒子 */
    public void connect(BluetoothDevice dev) {
        if (hid == null || !registered) return;
        target = dev;
        try {
            hid.connect(dev);
        } catch (Exception e) {
            Log.e(TAG, "connect fail", e);
        }
    }

    public void disconnect() {
        // 先清空目标：切换设备后未连接新目标时，按键不得继续发往旧盒子（SPEC-015）
        releaseAll(); // SPEC-018：先释放按键/鼠标状态再断开
        BluetoothDevice d = target;
        target = null;
        if (hid != null && d != null) {
            try { hid.disconnect(d); } catch (Exception ignored) {}
        }
    }

    /** 已配对的设备列表 */
    public Set<BluetoothDevice> bondedDevices() {
        return adapter != null ? adapter.getBondedDevices() : null;
    }

    /** 发送键盘按键（按下 + 自动抬起），返回是否成功入队 */
    public boolean sendKey(int usage) {
        if (!sendKeyDown(usage)) return false;
        final BluetoothDevice d = target;
        main.postDelayed(() -> sendKeyUpTo(d), 35);
        return true;
    }

    /** SPEC-018：只发按下，由调用方在抬手时发释放（实体遥控器的长按语义） */
    public boolean sendKeyDown(int usage) {
        if (!isReady()) return false;
        BluetoothDevice d = target;
        if (d == null) return false;
        final byte[] down = new byte[]{0, 0, (byte) usage, 0, 0, 0, 0, 0};
        try {
            boolean ok = hid.sendReport(d, RID_KEY, down);
            Log.i(TAG, "sendKeyDown usage=0x" + Integer.toHexString(usage) + " ok=" + ok);
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "sendKeyDown fail", e);
            return false;
        }
    }

    /** SPEC-018：释放键盘按键（全 0 报告即无按键按下） */
    public boolean sendKeyUp() {
        return sendKeyUpTo(target);
    }

    private boolean sendKeyUpTo(BluetoothDevice d) {
        if (!isReady() || d == null) return false;
        final byte[] up = new byte[]{0, 0, 0, 0, 0, 0, 0, 0};
        try {
            return hid.sendReport(d, RID_KEY, up);
        } catch (Exception e) {
            Log.e(TAG, "sendKeyUp fail", e);
            return false;
        }
    }

    /**
     * SPEC-018 飞鼠：发送鼠标相对位移（按键状态保持），单次上限 20。
     * 传感器事件约 20ms 一次；更密集的事件先累计，到间隔再合并发送，
     * 避免 HID 队列堆积造成光标的滞后漂移。
     */
    public boolean sendMouseMove(int dx, int dy) {
        if (!isReady()) return false;
        if (target == null) return false;
        pendX += dx;
        pendY += dy;
        long now = SystemClock.uptimeMillis();
        if (now - lastMouseAt < 20) {
            scheduleDrain(20);
            return true;
        }
        lastMouseAt = now;
        return flushMove(20);
    }

    /** 取当前应发送的一段位移，保留超出上限的余量 */
    private boolean flushMove(int maxStep) {
        BluetoothDevice d = target;
        if (!isReady() || d == null) return false;
        int x = clampMove(pendX, maxStep);
        int y = clampMove(pendY, maxStep);
        pendX -= x;
        pendY -= y;
        boolean ok = sendMouseReport(d, x, y, 0);
        if (pendX != 0 || pendY != 0) scheduleDrain(maxStep);
        return ok;
    }

    private void scheduleDrain(int maxStep) {
        if (drainScheduled) return;
        drainScheduled = true;
        main.postDelayed(() -> {
            drainScheduled = false;
            if (pendX != 0 || pendY != 0) {
                lastMouseAt = SystemClock.uptimeMillis();
                flushMove(maxStep);
            }
        }, 20);
    }

    /** SPEC-018/023：设置鼠标按键（1=左键按下，0=释放）；先补发已累计位移 */
    public boolean sendMouseButton(int buttons) {
        if (!isReady()) return false;
        BluetoothDevice d = target;
        if (d == null) return false;
        int x = clampMove(pendX, 127);
        int y = clampMove(pendY, 127);
        pendX -= x;
        pendY -= y;
        mouseButtons = buttons & 0x03;
        lastMouseAt = SystemClock.uptimeMillis();
        boolean ok = sendMouseReport(d, x, y, 0);
        if (pendX != 0 || pendY != 0) scheduleDrain(127);
        return ok;
    }

    private boolean sendMouseReport(BluetoothDevice d, int x, int y, int wheel) {
        final byte[] r = new byte[]{(byte) mouseButtons, (byte) x, (byte) y, (byte) wheel};
        try {
            boolean ok = hid.sendReport(d, RID_MOUSE, r);
            if (!ok) Log.w(TAG, "sendMouseReport not queued");
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "sendMouseReport fail", e);
            return false;
        }
    }

    /** 单次报告位移上限，过大时盒子光标会瞬移 */
    private static int clampMove(int v, int maxStep) {
        return Math.max(-maxStep, Math.min(maxStep, v));
    }

    /** SPEC-018：释放所有按键与鼠标状态（退出飞鼠/断开前调用，防卡住） */
    public void releaseAll() {
        BluetoothDevice d = target;
        mouseButtons = 0;
        pendX = 0;
        pendY = 0;
        drainScheduled = false;
        if (hid == null || d == null) return;
        try { hid.sendReport(d, RID_MOUSE, new byte[]{0, 0, 0, 0}); } catch (Exception ignored) {}
        try {
            hid.sendReport(d, RID_KEY, new byte[]{0, 0, 0, 0, 0, 0, 0, 0});
        } catch (Exception ignored) {}
    }

    /** 发送消费者控制（音量等），同样按下 + 抬起，返回是否成功入队 */
    public boolean sendConsumer(int usage) {
        BluetoothDevice d = target;
        if (!isReady() || d == null) return false;
        final byte[] down = new byte[]{(byte) (usage & 0xFF), (byte) ((usage >> 8) & 0xFF)};
        final byte[] up = new byte[]{0, 0};
        try {
            boolean ok = hid.sendReport(d, RID_CONSUMER, down);
            Log.i(TAG, "sendConsumer usage=0x" + Integer.toHexString(usage) + " ok=" + ok);
            main.postDelayed(() -> {
                try { hid.sendReport(d, RID_CONSUMER, up); } catch (Exception ignored) {}
            }, 35);
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "sendConsumer fail", e);
            return false;
        }
    }

    public void stop() {
        releaseAll(); // SPEC-018：退出前释放按键/鼠标状态
        try {
            if (hid != null) {
                hid.disconnect(target);
                hid.unregisterApp();
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
            }
        } catch (Exception ignored) {}
        hid = null;
        registered = false;
        target = null;
    }
}
