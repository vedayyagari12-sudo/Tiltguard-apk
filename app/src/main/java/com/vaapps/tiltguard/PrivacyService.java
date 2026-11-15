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
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
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

    private static final String CHANNEL_ID = "privacy";
    private static final int NOTIF_ID = 1;
    private static final float MAX_DARKNESS = 0.97f;
    private static final float FADE_RANGE_DEG = 12f; // degrees from "clear" to "fully dark"

    static volatile boolean running = false;
    static volatile int sensitivityDeg = DEFAULT_SENSITIVITY;

    private WindowManager windowManager;
    private View shade;
    private WindowManager.LayoutParams shadeParams;
    private SensorManager sensorManager;
    private boolean usingRotationVector;

    private final float[] rotation = new float[9];
    private final float[] gravity = new float[3];
    private float[] baseline;           // "looking straight at it" direction
    private float darkness = 0f;
    private float appliedDarkness = -1f;

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

    public static void recalibrate(Context c) {
        if (running) c.startService(new Intent(c, PrivacyService.class).setAction(ACTION_RECALIBRATE));
    }

    static void loadSettings(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        sensitivityDeg = p.getInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        loadSettings(this);
        startAsForeground();

        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        addShade();
        startSensor();
        running = true;
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
        shade = new View(this);
        shade.setBackgroundColor(Color.BLACK);

        shadeParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        shadeParams.alpha = 0f;
        if (Build.VERSION.SDK_INT >= 28) {
            shadeParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        windowManager.addView(shade, shadeParams);
    }

    private void applyDarkness(float d) {
        if (shade == null || Math.abs(d - appliedDarkness) < 0.01f) return;
        appliedDarkness = d;
        // Window alpha (not view alpha) so touches still pass through while clear.
        // Above 0.8 Android blocks touches, which only happens while tilted away.
        shadeParams.alpha = d;
        windowManager.updateViewLayout(shade, shadeParams);
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

        float target;
        if (tiltDeg <= sensitivityDeg) target = 0f;
        else target = Math.min(MAX_DARKNESS, (tiltDeg - sensitivityDeg) / FADE_RANGE_DEG * MAX_DARKNESS);

        // Darken fast, brighten a touch slower, so it feels smooth.
        float speed = target > darkness ? 0.35f : 0.2f;
        darkness += (target - darkness) * speed;
        if (darkness < 0.005f) darkness = 0f;
        applyDarkness(darkness);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    // ----------------------------------------------------------- notification

    private void startAsForeground() {
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
                .setContentText("Screen darkens when tilted away from you")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Reset angle", recal).build())
                .addAction(new Notification.Action.Builder(null, "Turn off", stop).build())
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void refreshTile() {
        TileService.requestListeningState(this,
                new ComponentName(this, PrivacyTileService.class));
    }
}
