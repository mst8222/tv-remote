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
 * 手机端触控板视图（SPEC-028 触摸 + SPEC-029 鼠标）。
 *
 * 两种模式共用同一个视图，由 setMouseMode 切换：
 *   触摸（默认）：触控板是盒子屏幕的映射面，按下位置按万分比换算成盒子坐标，
 *     点按/长按/滑动直接映射到盒子同一位置，不累计相对位移、不显示光标；
 *   鼠标：手指拖动按 1.5 倍增益换算相对位移移动盒端光标，点按为单击，
 *     按住不动为长按，双指上下滑动为滚动，适合绝对触摸失效的盒子。
 *
 * 触摸判定：未超过 8dp 抬起 → 绝对点按；按住 500ms → 绝对长按；
 *   移动超过 8dp 后抬起 → 绝对滑动；第二根手指落下取消本次手势。
 * 鼠标判定：未超过 8dp 抬起 → 单击；按住 500ms 未移动 → 长按（抬起时发送）；
 *   长按待定期间移动 → 先按下左键转拖动，抬起时释放。
 */
public class TouchPadView extends View {

    public interface Sink {
        // ---- 触摸模式：绝对坐标，x/y 为 0..10000 的归一化屏幕坐标 ----

        /** 绝对点按 */
        void tap(int x, int y);

        /** 绝对长按 */
        void longPress(int x, int y);

        /** 绝对滑动，坐标归一化，时长毫秒 */
        void swipe(int x1, int y1, int x2, int y2, long ms);

        // ---- 鼠标模式：相对位移与光标操作 ----

        /** 相对移动光标 */
        void move(int dx, int dy);

        /** 光标处单击 */
        void tap();

        /** 光标处长按 */
        void longTap();

        /** 按下左键（拖动开始） */
        void press();

        /** 释放左键 */
        void release();

        /** 滚动，正数向下/向前 */
        void scroll(int steps);
    }

    private static final float SLOP_DP = 8;
    private static final float MOVE_GAIN = 1.5f;
    private static final float SCROLL_UNIT_DP = 24;
    /** 长按判定阈值，与盒端 600ms 长按手势匹配 */
    private static final long LONG_PRESS_MS = 500;
    private static final long SWIPE_MIN_MS = 50;
    private static final long SWIPE_MAX_MS = 1500;
    /** 鼠标模式滚动最小间隔 */
    private static final long SCROLL_MIN_MS = 100;

    private static final int BG_COLOR = 0xFF1B2432;
    private static final int STROKE_COLOR = 0xFF2A3547;
    private static final int ACCENT = 0xFF4F7CFF;
    private static final int SUB = 0xFF8A94A6;

    private static final int STATE_IDLE = 0;
    /** 单指按下，尚未判定点按/长按/滑动 */
    private static final int STATE_TOUCH = 1;
    /** 鼠标模式：单指移动光标 */
    private static final int STATE_MOVE = 2;
    /** 鼠标模式：拖动（左键已按下） */
    private static final int STATE_DRAG = 3;
    /** 鼠标模式：长按待定，抬起未移动才发送长按 */
    private static final int STATE_LONG_PENDING = 4;
    /** 触摸模式：长按已发送，本次手势不再产生命令 */
    private static final int STATE_LONG_HELD = 5;
    /** 鼠标模式：双指滚动 */
    private static final int STATE_SCROLL = 6;
    /** 多指手势已结束或取消，等待全部抬起 */
    private static final int STATE_CANCELLED = 7;

    private final Sink sink;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final float den;
    private final float slopPx;
    private final float scrollUnitPx;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private boolean mouseMode = false;

    private int state = STATE_IDLE;
    private boolean touched = false;
    private boolean moved = false;
    private float downX, downY;
    private float lastX, lastY;
    private long downAt;
    private float moveAccX, moveAccY;
    private float scrollAccum;
    private float scrollLastY;
    private long lastScrollAt;

    private String hintTop = "";
    private String hintBottom = "";

    private final Runnable touchLongTask;
    private final Runnable mouseLongTask;

    public TouchPadView(Context c, Sink sink) {
        super(c);
        this.sink = sink;
        touchLongTask = () -> {
            if (mouseMode || state != STATE_TOUCH || moved) return;
            state = STATE_LONG_HELD;
            sink.longPress(normX(downX), normY(downY));
            invalidate();
        };
        mouseLongTask = () -> {
            if (!mouseMode || state != STATE_TOUCH || moved) return;
            // 直连长按：到点只进入待定态，抬起未移动才发送 MLONG
            state = STATE_LONG_PENDING;
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
        dotPaint.setColor(ACCENT);
        dotPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(SUB);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        setClickable(true);
        setContentDescription(getResources().getString(R.string.input_touchpad));
    }

    /** 切换触摸（绝对点按）/ 鼠标（相对移动光标）手势 */
    public void setMouseMode(boolean mouse) {
        if (mouseMode == mouse) return;
        reset();
        mouseMode = mouse;
        invalidate();
    }

    public void setHintLines(String top, String bottom) {
        hintTop = top;
        hintBottom = bottom;
        invalidate();
    }

    /** 退出触控板、切设备、断开连接时调用：释放按压并中止本次手势 */
    public void reset() {
        handler.removeCallbacks(touchLongTask);
        handler.removeCallbacks(mouseLongTask);
        // 仅鼠标模式需要补发左键释放；触摸模式的长按已直接发送绝对命令
        if (mouseMode && (state == STATE_DRAG || state == STATE_LONG_HELD)) sink.release();
        state = STATE_IDLE;
        touched = false;
        moved = false;
        moveAccX = 0;
        moveAccY = 0;
        scrollAccum = 0;
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
        canvas.drawRoundRect(rect, r, r, strokePaint);

        // 触摸反馈：在按下的位置画一个点，提示「点哪打哪」；鼠标模式不画
        if (!mouseMode && touched && state != STATE_IDLE) {
            float r2 = 7 * den;
            canvas.drawCircle(lastX, lastY, r2, dotPaint);
        }

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float gap = 14 * den;
        if (!hintTop.isEmpty() && !hintBottom.isEmpty()) {
            canvas.drawText(hintTop, cx, cy - gap / 2, textPaint);
            canvas.drawText(hintBottom, cx, cy + gap + textPaint.getTextSize(), textPaint);
        } else if (!hintTop.isEmpty()) {
            canvas.drawText(hintTop, cx, cy + textPaint.getTextSize() / 3, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        getParent().requestDisallowInterceptTouchEvent(true);
        return mouseMode ? onMouseTouch(e) : onTouchTouch(e);
    }

    // ---------------- 触摸模式（SPEC-028 绝对映射） ----------------

    private boolean onTouchTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getWidth() <= 0 || getHeight() <= 0) return false;
                state = STATE_TOUCH;
                touched = true;
                moved = false;
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                downAt = SystemClock.uptimeMillis();
                handler.postDelayed(touchLongTask, LONG_PRESS_MS);
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                // 第二根手指落下：取消本次手势，不发命令
                handler.removeCallbacks(touchLongTask);
                state = STATE_CANCELLED;
                moved = false;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (state == STATE_TOUCH) {
                    lastX = e.getX();
                    lastY = e.getY();
                    if (!moved && Math.hypot(lastX - downX, lastY - downY) > slopPx) {
                        moved = true;
                        handler.removeCallbacks(touchLongTask);
                        invalidate();
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(touchLongTask);
                if (state == STATE_TOUCH) {
                    float ex = clamp(e.getX(), 0, getWidth());
                    float ey = clamp(e.getY(), 0, getHeight());
                    if (moved) {
                        long ms = Math.max(SWIPE_MIN_MS,
                                Math.min(SWIPE_MAX_MS, SystemClock.uptimeMillis() - downAt));
                        sink.swipe(normX(downX), normY(downY), normX(ex), normY(ey), ms);
                    } else {
                        sink.tap(normX(downX), normY(downY));
                    }
                }
                reset();
                return true;

            case MotionEvent.ACTION_CANCEL:
                reset();
                return true;

            default:
                return true;
        }
    }

    // ---------------- 鼠标模式（SPEC-029 相对移动光标） ----------------

    private boolean onMouseTouch(MotionEvent e) {
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
                // 手指不足两指时结束滚动；三指抬起一根仍可继续
                if (state == STATE_SCROLL && e.getPointerCount() - 1 < 2) {
                    state = STATE_CANCELLED;
                    scrollAccum = 0;
                }
                return true;

            case MotionEvent.ACTION_UP:
                endSingle();
                return true;

            case MotionEvent.ACTION_CANCEL:
                reset();
                return true;

            default:
                return true;
        }
    }

    private void beginSingle(float x, float y) {
        handler.removeCallbacks(mouseLongTask);
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
        handler.postDelayed(mouseLongTask, LONG_PRESS_MS);
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
            handler.removeCallbacks(mouseLongTask);
            if (state == STATE_LONG_PENDING) {
                // 长按待定期间移动转为拖动
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
        handler.removeCallbacks(mouseLongTask);
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
        if (now - lastScrollAt < SCROLL_MIN_MS) return;
        steps = Math.max(-5, Math.min(5, steps));
        sink.scroll(steps);
        scrollAccum += steps * scrollUnitPx;
        lastScrollAt = now;
    }

    private void endSingle() {
        handler.removeCallbacks(mouseLongTask);
        if (state == STATE_DRAG || state == STATE_LONG_HELD) {
            sink.release();
        } else if (state == STATE_LONG_PENDING) {
            sink.longTap();
        } else if (state == STATE_TOUCH) {
            // 双击由盒子/应用按两次连续单击自行识别
            sink.tap();
        }
        reset();
    }

    private static float avgY(MotionEvent e) {
        int n = e.getPointerCount();
        float sum = 0;
        for (int i = 0; i < n; i++) sum += e.getY(i);
        return sum / n;
    }

    private float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private int normX(float x) {
        int w = getWidth();
        if (w <= 0) return 0;
        return Math.round(clamp(x, 0, w) * 10000f / w);
    }

    private int normY(float y) {
        int h = getHeight();
        if (h <= 0) return 0;
        return Math.round(clamp(y, 0, h) * 10000f / h);
    }
}
