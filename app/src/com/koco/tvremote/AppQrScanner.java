package com.koco.tvremote;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 手机相机扫码取景器（SPEC-036）。
 * Camera2 连续预览 + 每 400ms 抓一张 JPEG，用 ZXing 解码二维码；
 * 识别成功后回调文本但继续抓帧，由调用方校验内容并决定是否关闭。
 * 所有相机操作都在独立线程执行。
 */
public class AppQrScanner implements TextureView.SurfaceTextureListener {

    private static final String TAG = "AppQrScanner";
    /** 两次抓帧的最小间隔：太快会堆积未完成的 capture，太慢影响识别速度 */
    private static final long FRAME_INTERVAL_MS = 400;

    public interface Listener {
        void onResult(String text);
        void onError(String msg);
    }

    private final Activity activity;
    private final TextureView view;
    private final Listener listener;
    private final MultiFormatReader reader = new MultiFormatReader();

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader jpegReader;
    private Surface previewSurface;
    private CaptureRequest stillRequest;
    private int sensorOrientation = 0;
    private boolean stopped = false;
    private boolean capturePending = false;
    private boolean started = false;

    public AppQrScanner(Activity activity, TextureView view, Listener listener) {
        this.activity = activity;
        this.view = view;
        this.listener = listener;
        Map<DecodeHintType, Object> hints = new HashMap<>();
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);
    }

    public void start() {
        if (started) return;
        started = true;
        stopped = false;
        if (!view.isAvailable()) {
            view.setSurfaceTextureListener(this);
            return;
        }
        // 投递到相机线程执行，保证在 stop 的释放动作之后按顺序重新打开
        ensureHandler().post(this::open);
    }

    public void stop() {
        started = false;
        stopped = true;
        if (handler != null) {
            handler.post(this::closeAll);
        } else {
            closeAll();
        }
    }

    private Handler ensureHandler() {
        if (handler == null) {
            thread = new HandlerThread("qr-scan");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        return handler;
    }

    // ---------------- TextureView 回调 ----------------

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (started) ensureHandler().post(this::open);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    // ---------------- 相机初始化 ----------------

    private void open() {
        if (!started || stopped) return;
        if (camera != null || previewSurface != null) return;
        try {
            CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                fail("无法打开相机");
                return;
            }
            String cameraId = pickCamera(manager);
            if (cameraId == null) {
                fail("无法打开相机");
                return;
            }
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 0 : so;
            StreamConfigurationMap map =
                    chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                fail("无法打开相机");
                return;
            }
            Size preview = pickSize(map.getOutputSizes(SurfaceTexture.class), 1280, 16f / 9f);
            Size jpeg = pickSize(map.getOutputSizes(ImageFormat.JPEG), 1280, 16f / 9f);
            if (preview == null || jpeg == null) {
                fail("无法打开相机");
                return;
            }
            view.getSurfaceTexture().setDefaultBufferSize(preview.getWidth(), preview.getHeight());
            previewSurface = new Surface(view.getSurfaceTexture());
            jpegReader = ImageReader.newInstance(jpeg.getWidth(), jpeg.getHeight(),
                    ImageFormat.JPEG, 2);
            jpegReader.setOnImageAvailableListener(this::onJpeg, handler);
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice c) {
                    // 打开过程中弹窗已关闭：直接释放，避免相机泄漏
                    if (stopped) {
                        c.close();
                        return;
                    }
                    camera = c;
                    createSession();
                }

                @Override
                public void onDisconnected(CameraDevice c) {
                    c.close();
                    camera = null;
                    fail("相机已断开");
                }

                @Override
                public void onError(CameraDevice c, int error) {
                    c.close();
                    camera = null;
                    fail("无法打开相机");
                }
            }, handler);
        } catch (Exception e) {
            Log.e(TAG, "open fail", e);
            fail("无法打开相机");
        }
    }

    private String pickCamera(CameraManager manager) throws Exception {
        String first = null;
        for (String id : manager.getCameraIdList()) {
            if (first == null) first = id;
            Integer facing = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return first;
    }

    /** 选择不超过 maxWidth、长宽比最接近 16:9 的尺寸；没有合适尺寸时取最小的一档 */
    private static Size pickSize(Size[] sizes, int maxWidth, float ratio) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        float bestScore = Float.MAX_VALUE;
        Size smallest = sizes[0];
        for (Size s : sizes) {
            if (s.getWidth() < smallest.getWidth()) smallest = s;
            if (s.getWidth() > maxWidth) continue;
            float score = Math.abs((float) s.getWidth() / s.getHeight() - ratio);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best != null ? best : smallest;
    }

    private void createSession() {
        if (stopped || camera == null || previewSurface == null || jpegReader == null) return;
        try {
            List<Surface> targets = new ArrayList<>(Arrays.asList(
                    previewSurface, jpegReader.getSurface()));
            camera.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (stopped) {
                        s.close();
                        return;
                    }
                    session = s;
                    startPreview();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    fail("无法打开相机");
                }
            }, handler);
        } catch (Exception e) {
            Log.e(TAG, "createSession fail", e);
            fail("无法打开相机");
        }
    }

    private void startPreview() {
        if (stopped || camera == null || session == null) return;
        try {
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewSurface);
            b.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            session.setRepeatingRequest(b.build(), null, handler);

            // 抓帧请求与预览分开：只带 JPEG 目标，避免每帧都走拍照路径
            CaptureRequest.Builder sb =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            sb.addTarget(jpegReader.getSurface());
            sb.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            stillRequest = sb.build();
            captureNext();
        } catch (Exception e) {
            Log.e(TAG, "startPreview fail", e);
            fail("无法打开相机");
        }
    }

    /** 定时抓一帧；上一帧还没回来时跳过，避免队列堆积 */
    private void captureNext() {
        if (stopped || session == null || stillRequest == null) return;
        if (capturePending) return;
        capturePending = true;
        try {
            session.capture(stillRequest, new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s,
                                               CaptureRequest request,
                                               android.hardware.camera2.TotalCaptureResult result) {
                    capturePending = false;
                    if (!stopped) handler.postDelayed(AppQrScanner.this::captureNext,
                            FRAME_INTERVAL_MS);
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request,
                                            android.hardware.camera2.CaptureFailure failure) {
                    // 单帧失败不终止扫描，下一轮继续
                    capturePending = false;
                    if (!stopped) handler.postDelayed(AppQrScanner.this::captureNext,
                            FRAME_INTERVAL_MS);
                }
            }, handler);
        } catch (Exception e) {
            Log.e(TAG, "capture fail", e);
            capturePending = false;
        }
    }

    private void onJpeg(ImageReader ir) {
        Image image = null;
        try {
            image = ir.acquireLatestImage();
            if (image == null || stopped) return;
            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            String text = decode(data);
            if (text != null && !stopped) {
                // 不自动停止：内容不合法时由调用方提示并继续扫描
                activity.runOnUiThread(() -> listener.onResult(text));
            }
        } catch (Exception e) {
            Log.w(TAG, "decode frame fail", e);
        } finally {
            if (image != null) image.close();
        }
    }

    /** 把 JPEG 帧转成灰度并交给 ZXing；只取二维码格式，识别失败返回 null */
    private String decode(byte[] data) {
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bmp == null) return null;
        try {
            int degrees = rotateDegrees();
            if (degrees != 0) {
                Matrix m = new Matrix();
                m.postRotate(degrees);
                Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0,
                        bmp.getWidth(), bmp.getHeight(), m, true);
                if (rotated != bmp) {
                    bmp.recycle();
                    bmp = rotated;
                }
            }
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int[] pixels = new int[w * h];
            bmp.getPixels(pixels, 0, w, 0, 0, w, h);
            BinaryBitmap binary = new BinaryBitmap(new HybridBinarizer(
                    new RGBLuminanceSource(w, h, pixels)));
            Result r = reader.decodeWithState(binary);
            return r == null ? null : r.getText();
        } catch (NotFoundException ignored) {
            return null;
        } finally {
            bmp.recycle();
        }
    }

    /** 传感器方向与屏幕方向的差值：竖屏拿手机时通常为 90 度 */
    private int rotateDegrees() {
        int rotation = 0;
        switch (activity.getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: rotation = 90; break;
            case Surface.ROTATION_180: rotation = 180; break;
            case Surface.ROTATION_270: rotation = 270; break;
            default: break;
        }
        return (sensorOrientation - rotation + 360) % 360;
    }

    private void fail(String msg) {
        stopped = true;
        activity.runOnUiThread(() -> listener.onError(msg));
    }

    /** 释放相机资源；没有重新开始时结束抓帧线程 */
    private void closeAll() {
        if (session != null) {
            try { session.close(); } catch (Exception ignored) {}
            session = null;
        }
        if (camera != null) {
            try { camera.close(); } catch (Exception ignored) {}
            camera = null;
        }
        if (jpegReader != null) {
            try { jpegReader.close(); } catch (Exception ignored) {}
            jpegReader = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        stillRequest = null;
        capturePending = false;
        if (!started && thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
    }
}
