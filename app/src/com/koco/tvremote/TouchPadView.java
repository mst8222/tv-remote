package com.koco.tvremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * SPEC-023：手机端触控板视图。
 *
 * 纯手势视图，不依赖系统控件；通过 Sink 把判定结果交给直连或蓝牙通道：
 *   单指拖动 → 移动光标；点按 → 单击；按住不动到阈值 → 长按；
 *   移动后按住 → 拖动；双指上下滑 → 滚动。
 *
 * 长按在两个通道的时机不同，由 Sink.armLongPress() 的返回值区分：
 *   蓝牙返回 true：到点立即按下左键（盒子端从按下时刻再计 500ms）；
 *   直连返回 false：到点只进入待定态，抬起未移动时才发 MLONG。
 */
public class TouchPadView extends View {

    public interface Sink {
        void move(int dx, int dy);

        void tap();

        void longTap();

        void press();

        void release();

        void scroll(int steps);

        /** 到长按定时点调用：蓝牙立即按下并返回 true；直连只返回 false */
        boolean armLongPress();
    }

    private static final float SLOP_DP = 8;
    private static final float MOVE_GAIN = 1.5f;
    private static final float SCROLL_UNIT_DP = 24;

    private static final int BG_COLOR = 0xFF1B2432;
    private static final int STROKE_COLOR = 0xFF2A3547;
    private static final int ACCENT = 0xFF4F7CFF;
    private static final int SUB = 0xFF8A94A6;

    private static final int STATE_IDLE = 0;
    /** 单指按下，尚未超过滑动阈值 */
    private static final int STATE_TOUCH = 1;
    /** 单指移动光标 */
    private static final int STATE_MOVE = 2;
    /** 拖动（左键已按下） */
    private static final int STATE_DRAG = 3;
    /** 直连长按待定：到点未发命令，抬起未移动才发 MLONG */
    private static final int STATE_LONG_PENDING = 4;
    /** 蓝牙长按已按下左键 */
    private static final int STATE_LONG_HELD = 5;
    /** 双指滚动 */
    private static final int STATE_SCROLL = 6;
    /** 多指手势已结束，等待全部抬起 */
    private static final int STATE_CANCELLED = 7;

    private final Sink sink;
    private long longArmMs;
    private long scrollMinMs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final float den;
    private final float slopPx;
    private final float scrollUnitPx;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int state = STATE_IDLE;
    private boolean touched = false;
    private boolean moved = false;
    private float downX, downY;
    private float lastX, lastY;
    private float moveAccX, moveAccY;
    private float scrollAccum;
    private float scrollLastY;
    private long lastScrollAt;

    private String hintTop = "";
    private String hintBottom = "";

    private final Runnable longTask;

    public TouchPadView(Context c, Sink sink, long longArmMs, long scrollMinMs) {
        super(c);
        this.sink = sink;
        this.longArmMs = longArmMs;
        this.scrollMinMs = scrollMinMs;
        longTask = () -> {
            if (state != STATE_TOUCH || moved) return;
            if (sink.armLongPress()) {
                state = STATE_LONG_HELD;
            } else {
                state = STATE_LONG_PENDING;
            }
            invalidate();
        };
        den = getResources().getDisplayMetrics().density;
        slopPx = SLOP_DP * den;
        scrollUnitPx = SCROLL_UNIT_DP * den;

        bgPaint.setColor(BG_COLOR);
        bgPaint.setStyle(Paint.Style.FILL);
        strokePaint.setColor(STROKE_COLOR);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1, den));
        textPaint.setColor(SUB);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        setClickable(true);
        setContentDescription(getResources().getString(R.string.input_touchpad));
    }

    public void setHintLines(String top, String bottom) {
        hintTop = top;
        hintBottom = bottom;
        invalidate();
    }

    /** 切换连接方式时更新长按定时点与滚动最小间隔（蓝牙 350/40，直连 500/100） */
    public void setTiming(long longArmMs, long scrollMinMs) {
        this.longArmMs = longArmMs;
        this.scrollMinMs = scrollMinMs;
    }

    /** 退出触控板、切设备、断开连接时调用：释放按压并中止本次手势 */
    public void reset() {
        handler.removeCallbacks(longTask);
        if (state == STATE_DRAG || state == STATE_LONG_HELD) sink.release();
        state = STATE_IDLE;
        touched = false;
        moved = false;
        scrollAccum = 0;
        moveAccX = 0;
        moveAccY = 0;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        reset();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = 28 * den;
        rect.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(rect, r, r, bgPaint);
        float inset = strokePaint.getStrokeWidth() / 2;
        rect.inset(inset, inset);
        strokePaint.setColor(touched ? ACCENT : STROKE_COLOR);
        canvas.drawRoundRect(rect, r - inset, r - inset, strokePaint);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float line = textPaint.getTextSize() * 1.5f;
        canvas.drawText(hintTop, cx, cy - line / 4, textPaint);
        canvas.drawText(hintBottom, cx, cy - line / 4 + line, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        getParent().requestDisallowInterceptTouchEvent(true);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginSingle(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                beginScroll(e);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (state == STATE_SCROLL) {
                    doScroll(e);
                } else if (state != STATE_CANCELLED) {
                    doSingleMove(e.getX(), e.getY());
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                // 两指手势在手指不足两指时结束；三指抬起一根仍可继续滚动
                if (state == STATE_SCROLL && e.getPointerCount() - 1 < 2) endScroll();
                return true;
            case MotionEvent.ACTION_UP:
                endSingle();
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelAll();
                return true;
            default:
                return true;
        }
    }

    private void beginSingle(float x, float y) {
        handler.removeCallbacks(longTask);
        touched = true;
        moved = false;
        state = STATE_TOUCH;
        downX = x;
        downY = y;
        lastX = x;
        lastY = y;
        moveAccX = 0;
        moveAccY = 0;
        scrollAccum = 0;
        handler.postDelayed(longTask, longArmMs);
        invalidate();
    }

    private void doSingleMove(float x, float y) {
        if (state != STATE_TOUCH && state != STATE_MOVE
                && state != STATE_DRAG && state != STATE_LONG_PENDING
                && state != STATE_LONG_HELD) {
            return;
        }
        if (!moved) {
            float dx = x - downX;
            float dy = y - downY;
            if (Math.hypot(dx, dy) <= slopPx) return;
            moved = true;
            handler.removeCallbacks(longTask);
            if (state == STATE_LONG_PENDING) {
                // 直连：长按待定期间移动转为拖动
                sink.press();
                state = STATE_DRAG;
            } else if (state == STATE_LONG_HELD) {
                state = STATE_DRAG;
            } else {
                state = STATE_MOVE;
            }
            emitMove(dx, dy);
            lastX = x;
            lastY = y;
            return;
        }
        emitMove(x - lastX, y - lastY);
        lastX = x;
        lastY = y;
    }

    private void emitMove(float dx, float dy) {
        moveAccX += dx * MOVE_GAIN;
        moveAccY += dy * MOVE_GAIN;
        int mx = (int) moveAccX;
        int my = (int) moveAccY;
        if (mx == 0 && my == 0) return;
        moveAccX -= mx;
        moveAccY -= my;
        sink.move(mx, my);
    }

    private void beginScroll(MotionEvent e) {
        handler.removeCallbacks(longTask);
        if (state == STATE_DRAG || state == STATE_LONG_HELD) sink.release();
        state = STATE_SCROLL;
        touched = true;
        moved = true;
        scrollAccum = 0;
        scrollLastY = avgY(e);
        lastScrollAt = 0;
        invalidate();
    }

    private void doScroll(MotionEvent e) {
        float y = avgY(e);
        scrollAccum += y - scrollLastY;
        scrollLastY = y;
        int steps = (int) (-scrollAccum / scrollUnitPx);
        if (steps == 0) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastScrollAt < scrollMinMs) return;
        steps = Math.max(-5, Math.min(5, steps));
        sink.scroll(steps);
        scrollAccum += steps * scrollUnitPx;
        lastScrollAt = now;
    }

    private void endScroll() {
        state = STATE_CANCELLED;
        scrollAccum = 0;
    }

    private void endSingle() {
        handler.removeCallbacks(longTask);
        if (state == STATE_DRAG) {
            sink.release();
        } else if (state == STATE_LONG_HELD) {
            sink.release();
        } else if (state == STATE_LONG_PENDING) {
            sink.longTap();
        } else if (state == STATE_TOUCH) {
            // 双击由盒子/应用按两次连续单击自行识别
            sink.tap();
        }
        state = STATE_IDLE;
        touched = false;
        moved = false;
        scrollAccum = 0;
        invalidate();
    }

    private void cancelAll() {
        handler.removeCallbacks(longTask);
        if (state == STATE_DRAG || state == STATE_LONG_HELD) sink.release();
        state = STATE_IDLE;
        touched = false;
        moved = false;
        scrollAccum = 0;
        invalidate();
    }

    private static float avgY(MotionEvent e) {
        int n = e.getPointerCount();
        float sum = 0;
        for (int i = 0; i < n; i++) sum += e.getY(i);
        return sum / n;
    }
}
