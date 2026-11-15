package com.vaapps.tiltguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.Manifest;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.view.View;
import android.view.WindowManager;
import android.content.ComponentName;

/**
 * Keeps a full-screen black overlay on top of everything and fades it in when
 * the phone is tilted away from the angle the user was holding it at.
 *
 * Tilt is measured with the gyro-fused "game rotation vector" sensor (falls
 * back to the plain rotation vector, then to the accelerometer).
 */
public class PrivacyService extends Service implements SensorEventListener {

    public static final String ACTION_STOP = "com.vaapps.tiltguard.STOP";
    public static final String ACTION_RECALIBRATE = "com.vaapps.tiltguard.RECALIBRATE";

    static final String PREFS = "tiltguard";
    static final String KEY_SENSITIVITY = "sensitivity"; // degrees of free tilt
    static final int DEFAULT_SENSITIVITY = 20;
    static final String KEY_FACE = "face_check";
    static final String KEY_MULTI = "multi_face";
    static final String KEY_SIDES = "side_width"; // percent of screen width per side
    static final int DEFAULT_SIDES = 15;

    private static final String CHANNEL_ID = "privacy";
    private static final int NOTIF_ID = 1;
    private static final float FADE_RANGE_DEG = 14f; // degrees from "clear" to "fully dark"
    private static final long NO_FACE_GRACE_MS = 1500; // black out after this long without a face
    private static final long MULTI_FACE_HOLD_MS = 1200;
    private static final long FACE_RETRY_MS = 5000;

    static volatile boolean running = false;
    static volatile int sensitivityDeg = DEFAULT_SENSITIVITY;
    static volatile boolean faceCheck = true;
    static volatile boolean multiFace = true;
    static volatile boolean faceActive = false; // camera actually running
    static volatile int sidePercent = DEFAULT_SIDES;

    private WindowManager windowManager;
    private View shade;                       // full-screen black, fades in
    private WindowManager.LayoutParams shadeParams;
    private ShadeView leftBand, rightBand;    // always-on black edges
    private WindowManager.LayoutParams leftParams, rightParams;
    private int appliedBandPx = -1;
    private SensorManager sensorManager;
    private boolean usingRotationVector;

    private final float[] rotation = new float[9];
    private final float[] gravity = new float[3];
    private float[] baseline;           // "looking straight at it" direction
    private float sides = 0f;
    private float full = 0f;

    private FaceWatcher faceWatcher;
    private volatile long lastFaceTime;
    private volatile long lastMultiTime;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context c) {
        Intent i = new Intent(c, PrivacyService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        if (running) c.startService(new Intent(c, PrivacyService.class).setAction(ACTION_STOP));
    }

    /** Restart so camera on/off changes take effect. */
    public static void restart(Context c) {
        if (!running) return;
        stop(c);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> start(c), 400);
    }

    static boolean hasCamera(Context c) {
        return c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    public static void recalibrate(Context c) {
        if (running) c.startService(new Intent(c, PrivacyService.class).setAction(ACTION_RECALIBRATE));
    }

    static void loadSettings(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        sensitivityDeg = p.getInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY);
        faceCheck = p.getBoolean(KEY_FACE, true);
        multiFace = p.getBoolean(KEY_MULTI, true);
        sidePercent = p.getInt(KEY_SIDES, DEFAULT_SIDES);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        loadSettings(this);
        boolean wantFace = faceCheck && hasCamera(this);
        boolean cameraFgs = startAsForeground(wantFace);

        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        addShade();
        startSensor();
        running = true;
        if (wantFace && cameraFgs) startFace();
        refreshTile();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RECALIBRATE.equals(action)) {
            baseline = null; // next sensor reading becomes the new "straight on" angle
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        faceActive = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (faceWatcher != null) faceWatcher.stop();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        for (View v : new View[]{shade, leftBand, rightBand}) {
            if (v != null && v.isAttachedToWindow()) windowManager.removeView(v);
        }
        refreshTile();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- overlay

    private void addShade() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        shade = new View(this);
        shade.setBackgroundColor(android.graphics.Color.BLACK);
        shadeParams = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, android.view.Gravity.FILL);
        shadeParams.alpha = 0f;
        windowManager.addView(shade, shadeParams);

        leftBand = new ShadeView(this, true);
        leftParams = overlayParams(1, android.view.Gravity.START | android.view.Gravity.FILL_VERTICAL);
        windowManager.addView(leftBand, leftParams);

        rightBand = new ShadeView(this, false);
        rightParams = overlayParams(1, android.view.Gravity.END | android.view.Gravity.FILL_VERTICAL);
        windowManager.addView(rightBand, rightParams);
    }

    private WindowManager.LayoutParams overlayParams(int width, int gravity) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return p;
    }

    /**
     * sidesV 0..1: how far past the normal edge bands the sides have closed in (tilt).
     * fullV  0..1: black over the whole screen (no face / tilted all the way).
     *
     * The edge bands are their own narrow windows at full opacity, so they're truly black
     * while taps in the middle of the screen still go through to your apps.
     */
    private void applyShade(float sidesV, float fullV) {
        if (shade == null) return;
        int curW = getResources().getDisplayMetrics().widthPixels;
        float base = sidePercent / 100f;
        float frac = base + (0.55f - base) * sidesV;      // grows to just past half: bands meet
        int bandPx = Math.max(1, Math.round(curW * frac));
        if (bandPx != appliedBandPx) {
            appliedBandPx = bandPx;
            leftParams.width = bandPx;
            rightParams.width = bandPx;
            float a = (sidePercent == 0 && sidesV <= 0.001f) ? 0f : 1f;
            leftParams.alpha = a;
            rightParams.alpha = a;
            windowManager.updateViewLayout(leftBand, leftParams);
            windowManager.updateViewLayout(rightBand, rightParams);
        }
        if (Math.abs(fullV - shadeParams.alpha) >= 0.01f
                || (fullV == 0f && shadeParams.alpha != 0f)
                || (fullV == 1f && shadeParams.alpha != 1f)) {
            shadeParams.alpha = fullV;
            windowManager.updateViewLayout(shade, shadeParams);
        }
    }

    // ---------------------------------------------------------------- face

    private void startFace() {
        faceWatcher = new FaceWatcher(this, count -> {
            long now = SystemClock.elapsedRealtime();
            if (count >= 1) lastFaceTime = now;
            if (count >= 2) lastMultiTime = now;
        });
        lastFaceTime = SystemClock.elapsedRealtime() + 1500; // extra grace while the camera warms up
        faceActive = faceWatcher.start();
        if (!faceActive) {
            faceWatcher = null;
            mainHandler.postDelayed(this::retryFace, FACE_RETRY_MS);
        }
    }

    private void retryFace() {
        if (!running || faceWatcher != null) return;
        startFace();
    }

    /** 1 when the face check says the screen should be black, else 0. */
    private float faceBlackout() {
        if (faceWatcher == null) return 0f;
        if (!faceWatcher.isWorking()) {
            // Camera busy (video call, camera app) or not started yet: don't punish the user.
            if (faceWatcher.lastFrameTime == 0) return 0f;
            faceWatcher.stop();
            faceWatcher = null;
            faceActive = false;
            mainHandler.postDelayed(this::retryFace, FACE_RETRY_MS);
            return 0f;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastFaceTime > NO_FACE_GRACE_MS) return 1f;
        if (multiFace && now - lastMultiTime < MULTI_FACE_HOLD_MS) return 1f;
        return 0f;
    }

    // ---------------------------------------------------------------- sensors

    private void startSensor() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor s = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (s == null) s = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        usingRotationVector = s != null;
        if (s == null) s = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (s != null) sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float gx, gy, gz;
        if (usingRotationVector) {
            SensorManager.getRotationMatrixFromVector(rotation, e.values);
            // World "up" expressed in the phone's own axes = bottom row of R.
            gx = rotation[6];
            gy = rotation[7];
            gz = rotation[8];
        } else {
            // Low-pass the accelerometer to isolate gravity.
            final float k = 0.15f;
            gravity[0] += k * (e.values[0] - gravity[0]);
            gravity[1] += k * (e.values[1] - gravity[1]);
            gravity[2] += k * (e.values[2] - gravity[2]);
            gx = gravity[0];
            gy = gravity[1];
            gz = gravity[2];
        }
        float len = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (len < 1e-3f) return;
        gx /= len;
        gy /= len;
        gz /= len;

        if (baseline == null) {
            baseline = new float[]{gx, gy, gz};
        }

        float dot = gx * baseline[0] + gy * baseline[1] + gz * baseline[2];
        dot = Math.max(-1f, Math.min(1f, dot));
        float tiltDeg = (float) Math.toDegrees(Math.acos(dot));

        // c: 0 = inside the allowed angle, 1 = tilted all the way past the fade range.
        float c = tiltDeg <= sensitivityDeg ? 0f
                : Math.min(1f, (tiltDeg - sensitivityDeg) / FADE_RANGE_DEG);

        // Like a real privacy filter: the sides close in first, then it all goes black.
        float targetSides = Math.min(1f, c / 0.7f);
        float targetFull = c >= 1f ? 1f : 0f;
        targetFull = Math.max(targetFull, faceBlackout());

        sides = ease(sides, targetSides);
        full = ease(full, targetFull);
        applyShade(sides, full);
    }

    /** Darken fast, clear a touch slower, snap at the ends so black is truly black. */
    private static float ease(float cur, float target) {
        float speed = target > cur ? 0.35f : 0.2f;
        cur += (target - cur) * speed;
        if (target >= 1f && cur > 0.97f) cur = 1f;
        if (target <= 0f && cur < 0.01f) cur = 0f;
        return cur;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    // ----------------------------------------------------------- notification

    /** Returns true if the camera foreground type was granted. */
    private boolean startAsForeground(boolean withCamera) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Privacy screen", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, PrivacyService.class).setAction(ACTION_STOP), piFlags);
        PendingIntent recal = PendingIntent.getService(this, 2,
                new Intent(this, PrivacyService.class).setAction(ACTION_RECALIBRATE), piFlags);

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("Privacy screen is on")
                .setContentText(withCamera
                        ? "Blacks out when tilted away or when your face isn't seen"
                        : "Screen darkens when tilted away from you")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Reset angle", recal).build())
                .addAction(new Notification.Action.Builder(null, "Turn off", stop).build())
                .build();

        if (withCamera && Build.VERSION.SDK_INT >= 30) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (Build.VERSION.SDK_INT >= 34) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            try {
                startForeground(NOTIF_ID, n, type);
                return true;
            } catch (RuntimeException e) {
                // e.g. started from the Quick Settings tile while the app is in the background:
                // Android won't allow the camera then, so fall back to tilt only.
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        return withCamera && Build.VERSION.SDK_INT < 30;
    }

    private void refreshTile() {
        TileService.requestListeningState(this,
                new ComponentName(this, PrivacyTileService.class));
    }
}
