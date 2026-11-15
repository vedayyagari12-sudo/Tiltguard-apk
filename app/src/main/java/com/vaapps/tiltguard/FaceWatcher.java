package com.vaapps.tiltguard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.FaceDetector;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Watches the front camera and reports how many faces it sees.
 *
 * Uses two detectors and trusts whichever sees more faces:
 *  1. the camera chip's own face detection (fast, free, but flaky on some phones)
 *  2. Android's built-in FaceDetector on a small grayscale frame twice a second
 *
 * Nothing is saved or sent anywhere; frames are dropped right after checking.
 */
class FaceWatcher {

    interface Listener {
        /** Called on a background thread. */
        void onFaces(int count);
    }

    private static final long SOFTWARE_INTERVAL_MS = 450;
    private static final int TARGET_LONG_SIDE = 320;

    private final Context context;
    private final Listener listener;

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;

    private int sensorOrientation = 270;
    private int hardwareMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF;
    private volatile int hardwareCount = 0;
    private volatile long hardwareTime = 0;
    private long lastSoftware = 0;

    private FaceDetector detector;
    private int detW, detH;
    private int[] argb;

    /** Elapsed-realtime of the last camera frame; 0 if the camera never delivered. */
    volatile long lastFrameTime = 0;

    FaceWatcher(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Returns false if there is no usable front camera. */
    boolean start() {
        CameraManager cm = context.getSystemService(CameraManager.class);
        String frontId = null;
        Size size = null;
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_FRONT) continue;
                StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;
                frontId = id;
                size = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888));
                Integer o = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (o != null) sensorOrientation = o;

                int[] modes = ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
                Integer max = ch.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT);
                if (modes != null && max != null && max > 0) {
                    for (int m : modes) {
                        if (m == CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
                                || m == CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL) {
                            hardwareMode = m;
                            if (m == CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE) break;
                        }
                    }
                }
                break;
            }
        } catch (CameraAccessException | RuntimeException e) {
            return false;
        }
        if (frontId == null || size == null) return false;

        thread = new HandlerThread("TiltGuardFace");
        thread.start();
        handler = new Handler(thread.getLooper());

        reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
        reader.setOnImageAvailableListener(this::onImage, handler);

        try {
            cm.openCamera(frontId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice c) {
                    camera = c;
                    startSession();
                }

                @Override
                public void onDisconnected(CameraDevice c) {
                    c.close();
                    if (camera == c) camera = null;
                }

                @Override
                public void onError(CameraDevice c, int error) {
                    c.close();
                    if (camera == c) camera = null;
                }
            }, handler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            stop();
            return false;
        }
        return true;
    }

    void stop() {
        try {
            if (session != null) session.close();
        } catch (RuntimeException ignored) { }
        try {
            if (camera != null) camera.close();
        } catch (RuntimeException ignored) { }
        if (reader != null) reader.close();
        if (thread != null) thread.quitSafely();
        session = null;
        camera = null;
        reader = null;
        thread = null;
        lastFrameTime = 0;
    }

    /** True while the camera is actually delivering frames. */
    boolean isWorking() {
        return lastFrameTime != 0 && SystemClock.elapsedRealtime() - lastFrameTime < 2500;
    }

    // ----------------------------------------------------------------------------

    private static Size pickSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        for (Size s : sizes) {
            int longSide = Math.max(s.getWidth(), s.getHeight());
            if (longSide < 320) continue;
            if (best == null || longSide < Math.max(best.getWidth(), best.getHeight())) best = s;
        }
        return best != null ? best : sizes[0];
    }

    @SuppressWarnings("deprecation")
    private void startSession() {
        if (camera == null || reader == null) return;
        try {
            camera.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                CaptureRequest.Builder b =
                                        camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                b.addTarget(reader.getSurface());
                                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                if (hardwareMode != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) {
                                    b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, hardwareMode);
                                }
                                s.setRepeatingRequest(b.build(), captureCallback, handler);
                            } catch (CameraAccessException | RuntimeException ignored) { }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) { }
                    }, handler);
        } catch (CameraAccessException | RuntimeException ignored) { }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r,
                                               TotalCaptureResult result) {
                    if (hardwareMode == CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) return;
                    Face[] faces = result.get(CaptureResult.STATISTICS_FACES);
                    int n = 0;
                    if (faces != null) {
                        for (Face f : faces) if (f.getScore() >= 30) n++;
                    }
                    hardwareCount = n;
                    hardwareTime = SystemClock.elapsedRealtime();
                    if (n > 0) listener.onFaces(n);
                }
            };

    private void onImage(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (RuntimeException e) {
            return;
        }
        if (img == null) return;
        long now = SystemClock.elapsedRealtime();
        lastFrameTime = now;
        try {
            if (now - lastSoftware < SOFTWARE_INTERVAL_MS) return;
            lastSoftware = now;
            int sw = softwareCount(img);
            int hw = now - hardwareTime < 1000 ? hardwareCount : 0;
            listener.onFaces(Math.max(sw, hw));
        } catch (RuntimeException ignored) {
        } finally {
            img.close();
        }
    }

    /** Grayscale, downscaled, rotated upright, then Android's FaceDetector. */
    private int softwareCount(Image img) {
        Image.Plane y = img.getPlanes()[0];
        ByteBuffer buf = y.getBuffer();
        int rowStride = y.getRowStride();
        int pixStride = y.getPixelStride();
        int srcW = img.getWidth();
        int srcH = img.getHeight();

        int step = Math.max(1, Math.max(srcW, srcH) / TARGET_LONG_SIDE);
        int sw = srcW / step;
        int sh = srcH / step;

        // Front camera on a phone held upright: rotate so faces are upright.
        int rot = sensorOrientation % 360;
        boolean swap = rot == 90 || rot == 270;
        int outW = swap ? sh : sw;
        int outH = swap ? sw : sh;
        outW &= ~1; // FaceDetector needs an even width
        if (outW <= 0 || outH <= 0) return 0;

        if (detector == null || detW != outW || detH != outH) {
            detector = new FaceDetector(outW, outH, 3);
            detW = outW;
            detH = outH;
            argb = new int[outW * outH];
        }

        for (int sy = 0; sy < sh; sy++) {
            int rowBase = sy * step * rowStride;
            for (int sx = 0; sx < sw; sx++) {
                int v = buf.get(rowBase + sx * step * pixStride) & 0xFF;
                int dx, dy;
                switch (rot) {
                    case 90:  dx = sh - 1 - sy; dy = sx; break;
                    case 180: dx = sw - 1 - sx; dy = sh - 1 - sy; break;
                    case 270: dx = sy; dy = sw - 1 - sx; break;
                    default:  dx = sx; dy = sy;
                }
                if (dx >= outW || dy >= outH) continue;
                argb[dy * outW + dx] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }

        Bitmap rgb = Bitmap.createBitmap(argb, outW, outH, Bitmap.Config.ARGB_8888);
        Bitmap b565 = rgb.copy(Bitmap.Config.RGB_565, false);
        rgb.recycle();
        FaceDetector.Face[] found = new FaceDetector.Face[3];
        int n = detector.findFaces(b565, found);
        b565.recycle();
        int good = 0;
        for (int i = 0; i < n; i++) {
            if (found[i] != null && found[i].confidence() >= 0.3f) good++;
        }
        return good;
    }
}
