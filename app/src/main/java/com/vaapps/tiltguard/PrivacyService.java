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
 * Software privacy filter. One overlay window (ShadeView) models a micro-louver film:
 * brightness falls off with viewing angle and is black past the cutoff angle.
 *
 * The viewing angle comes from the front camera when face check is on (where your face
 * actually is relative to the screen), otherwise from how far the phone has been tilted
 * away from the position you were holding it in.
 */
public class PrivacyService extends Service implements SensorEventListener {

    public static final String ACTION_STOP = "com.vaapps.tiltguard.STOP";
    public static final String ACTION_RECALIBRATE = "com.vaapps.tiltguard.RECALIBRATE";

    static final String PREFS = "tiltguard";
    static final String KEY_SENSITIVITY = "cutoff_angle"; // degrees off-centre where it goes black
    static final int DEFAULT_SENSITIVITY = 30;
    static final String KEY_GRID = "grid_mode";
    static final String KEY_GRID_STR = "grid_strength";   // percent
    static final String KEY_GRID_CELL = "grid_cell";      // pixels between mesh lines
    static final String KEY_DECOY = "grid_decoy";         // percent
    static final String KEY_MOVING = "grid_moving";
    static final String KEY_FOURWAY = "four_way";
    static final String KEY_FACE = "face_tracking"; // optional, off unless switched on
    static final String KEY_MULTI = "multi_face";
    static final String KEY_SIDES = "edge_shade"; // how dark the edges are, percent
    static final int DEFAULT_SIDES = 40;

    private static final String CHANNEL_ID = "privacy";
    private static final int NOTIF_ID = 1;
    private static final float TOUCH_ALPHA = 0.8f;   // Android passes touches through up to this
    private static final long FACE_POS_FRESH_MS = 800;
    private static final float CLEAR_ZONE = 0.5f;
    private static final long NO_FACE_GRACE_MS = 1500; // black out after this long without a face
    private static final long MULTI_FACE_HOLD_MS = 1200;
    private static final long FACE_RETRY_MS = 5000;

    static volatile boolean running = false;
    static volatile int sensitivityDeg = DEFAULT_SENSITIVITY;
    static volatile boolean faceCheck = false;
    static volatile boolean multiFace = true;
    static volatile boolean faceActive = false; // camera actually running
    static volatile int sidePercent = DEFAULT_SIDES;
    static volatile boolean gridMode = false;
    static volatile int gridStrength = 45;
    static volatile int gridCell = 3;
    static volatile int decoyStrength = 35;
    static volatile boolean gridMoving = true;
    static volatile boolean fourWay = false;

    private WindowManager windowManager;
    private ShadeView shade;
    private WindowManager.LayoutParams shadeParams;
    private boolean opaque = false;           // window at full alpha (touches blocked)
    private SensorManager sensorManager;
    private boolean usingRotationVector;

    private final float[] rotation = new float[9];
    private final float[] gravity = new float[3];
    private float[] baseline;           // "looking straight at it" direction
    private float kx = 0f, ky = 0f;     // eased viewing angle / cutoff, phone axes
    private float full = 0f;
    private volatile float faceTx, faceTy;
    private volatile long lastFacePosTime;
    private double lastRoll = 0;
    private float[] faceBase;           // where your face sits when looking straight on

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
        faceCheck = p.getBoolean(KEY_FACE, false);
        multiFace = p.getBoolean(KEY_MULTI, true);
        sidePercent = 0;   // no edge shading: the front view stays completely clear
        gridMode = p.getBoolean(KEY_GRID, false);   // experimental, off unless switched on
        gridStrength = p.getInt(KEY_GRID_STR, 45);
        gridCell = p.getInt(KEY_GRID_CELL, 3);
        decoyStrength = p.getInt(KEY_DECOY, 35);
        gridMoving = p.getBoolean(KEY_MOVING, true);
        fourWay = p.getBoolean(KEY_FOURWAY, false);
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
            lastRoll = 0;
            faceBase = null;
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
        if (shade != null && shade.isAttachedToWindow()) windowManager.removeView(shade);
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
        shade = new ShadeView(this);
        shadeParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        shadeParams.alpha = TOUCH_ALPHA;
        if (Build.VERSION.SDK_INT >= 28) {
            shadeParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        windowManager.addView(shade, shadeParams);
    }

    /**
     * kxDev / kyDev: viewing angle as a fraction of the cutoff, along the phone's own
     * x (short) and y (long) axes. fullV: black over everything.
     */
    private void applyShade(float kxDev, float kyDev, float fullV) {
        if (shade == null) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float edge = sidePercent / 100f;
        float aspect = (float) Math.max(dm.widthPixels, dm.heightPixels)
                / Math.max(1, Math.min(dm.widthPixels, dm.heightPixels));
        float sxDev = edge;                         // louvers run along the phone's long axis
        float syDev = fourWay ? edge * aspect : 0f;
        if (!fourWay) kyDev = 0f;

        // Phone axes -> what is currently "across" and "down" on the display.
        float a, b, sa, sb;
        int rot = windowManager.getDefaultDisplay().getRotation();
        switch (rot) {
            case android.view.Surface.ROTATION_90:  a = kyDev;  b = kxDev;  sa = syDev; sb = sxDev; break;
            case android.view.Surface.ROTATION_180: a = -kxDev; b = kyDev;  sa = sxDev; sb = syDev; break;
            case android.view.Surface.ROTATION_270: a = -kyDev; b = -kxDev; sa = syDev; sb = sxDev; break;
            default:                                a = kxDev;  b = -kyDev; sa = sxDev; sb = syDev;
        }

        // Android only lets taps through an overlay whose window alpha is <= 0.8, so the
        // window stays at 0.8 while the middle is readable and goes to 1.0 (true black,
        // taps blocked) once the middle is nearly dark anyway.
        float worst = Math.max(Math.max(Math.abs(kxDev), Math.abs(kyDev)), fullV);
        boolean wantOpaque = opaque ? worst > 0.68f : worst > 0.78f;
        if (wantOpaque != opaque) {
            opaque = wantOpaque;
            shadeParams.alpha = opaque ? 1f : TOUCH_ALPHA;
            windowManager.updateViewLayout(shade, shadeParams);
        }
        // Decoy blobs fade in as the screen dims from tilt, and are gone when it's clear.
        float tiltAmount = Math.min(1f, Math.max(Math.abs(kxDev), Math.abs(kyDev)));
        shade.set(a, sa, b, sb, fullV, gridMode ? gridCell : 0, gridStrength / 100f,
                decoyStrength / 100f * tiltAmount, gridMoving, opaque ? 1f : 1f / TOUCH_ALPHA);
    }

    // ---------------------------------------------------------------- face

    private void startFace() {
        faceWatcher = new FaceWatcher(this, (count, hasPos, tx, ty) -> {
            long now = SystemClock.elapsedRealtime();
            if (count >= 1) lastFaceTime = now;
            if (count >= 2) lastMultiTime = now;
            if (count >= 1 && hasPos) {
                faceTx = tx;
                faceTy = ty;
                lastFacePosTime = now;
            }
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

        // Where the viewer is, relative to straight-on, as tangents along the phone's axes.
        float tx, ty;
        boolean faceLive = faceWatcher != null && faceWatcher.isWorking()
                && SystemClock.elapsedRealtime() - lastFacePosTime < FACE_POS_FRESH_MS;
        if (faceLive) {
            // Real viewing angle: where your face is in the front camera's view.
            float fx = faceTx, fy = faceTy;
            if (faceBase == null) faceBase = new float[]{fx, fy};
            tx = tanDiff(fx, faceBase[0]);
            ty = tanDiff(fy, faceBase[1]);
            if (Math.abs(tx) < 0.1f && Math.abs(ty) < 0.1f) {
                baseline[0] = gx; // keep the tilt reference in step while you're centred
                baseline[1] = gy;
                baseline[2] = gz;
            }
        } else {
            // No camera: use how far the phone has turned away from where you held it.
            // roll  = turning the phone left/right around its long axis
            // pitch = tipping the top toward/away from you
            // (Turning your whole body doesn't change either, so walking around is fine.)
            if (Math.hypot(gx, gz) > 0.12 && Math.hypot(baseline[0], baseline[2]) > 0.12) {
                double r = Math.atan2(gx, gz) - Math.atan2(baseline[0], baseline[2]);
                if (r > Math.PI) r -= 2 * Math.PI;
                if (r < -Math.PI) r += 2 * Math.PI;
                lastRoll = r;
            } // else: phone is almost exactly upright, where roll can't be measured; keep the last value
            double roll = lastRoll;
            double pitch = Math.asin(clamp1(gy)) - Math.asin(clamp1(baseline[1]));
            double lim = Math.toRadians(80);
            tx = (float) -Math.tan(Math.max(-lim, Math.min(lim, roll)));
            ty = (float) Math.tan(Math.max(-lim, Math.min(lim, pitch)));
        }

        // Louver law: light passes in proportion to 1 - tan(angle) / tan(cutoff).
        float tanCut = (float) Math.tan(Math.toRadians(sensitivityDeg));
        float targetKx = clearZone(Math.max(-2f, Math.min(2f, tx / tanCut)));
        float targetKy = clearZone(Math.max(-2f, Math.min(2f, ty / tanCut)));
        float worst = Math.max(Math.abs(targetKx), fourWay ? Math.abs(targetKy) : 0f);

        // Just past the cutoff a real filter is black edge to edge.
        float targetFull = Math.max(0f, Math.min(1f, (worst - 1f) / 0.12f));
        targetFull = Math.max(targetFull, faceBlackout());

        kx += (targetKx - kx) * 0.3f;
        ky += (targetKy - ky) * 0.3f;
        full = ease(full, targetFull);
        applyShade(kx, ky, full);
    }

    /**
     * Keeps the front view untouched: nothing is drawn until you're past half the privacy
     * angle, then it fades to black by the privacy angle.
     */
    private static float clearZone(float k) {
        float a = Math.abs(k);
        if (a <= CLEAR_ZONE) return 0f;
        return Math.signum(k) * (a - CLEAR_ZONE) / (1f - CLEAR_ZONE);
    }

    private static float clamp1(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    /** tan(atan(a) - atan(b)) */
    private static float tanDiff(float a, float b) {
        float d = 1f + a * b;
        if (d < 0.05f) return a > b ? 20f : -20f;
        return (a - b) / d;
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
                        ? "Privacy filter on: visible only from straight ahead"
                        : "Privacy filter on: darkens when tilted away")
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
