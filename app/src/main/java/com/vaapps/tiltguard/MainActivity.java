package com.vaapps.tiltguard;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** One screen: a big ON/OFF button, a sensitivity slider and a "reset angle" button. */
public class MainActivity extends Activity {

    private static final int MIN_DEG = 5;
    private static final int MAX_DEG = 40;
    private static final int BRAND = 0xFF1E3A5F;
    private static final int GREEN = 0xFF2E9E5B;

    private Button bigButton;
    private TextView status;
    private TextView sensitivityLabel;
    private Button resetButton;
    private Switch faceSwitch;
    private static final int REQ_CAMERA = 2;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PrivacyService.loadSettings(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0F1A2A);
        scroll.addView(buildUi());
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(0xFF0F1A2A);
        int pad = (int) (24 * dp);
        root.setPadding(pad, (int) (40 * dp), pad, pad);

        TextView title = new TextView(this);
        title.setText("TiltGuard");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Works like a privacy screen protector: the edges stay black, the screen "
                + "only shows while your face is in front of it, and it blacks out if you tilt it away.");
        sub.setTextColor(0xFFB8C4D6);
        sub.setTextSize(15);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, (int) (12 * dp), 0, (int) (36 * dp));
        root.addView(sub);

        bigButton = new Button(this);
        bigButton.setTextColor(Color.WHITE);
        bigButton.setTextSize(26);
        bigButton.setTypeface(Typeface.DEFAULT_BOLD);
        bigButton.setAllCaps(false);
        int size = (int) (170 * dp);
        root.addView(bigButton, new LinearLayout.LayoutParams(size, size));
        bigButton.setOnClickListener(v -> toggle());

        status = new TextView(this);
        status.setTextColor(0xFFB8C4D6);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, (int) (16 * dp), 0, (int) (32 * dp));
        root.addView(status);

        sensitivityLabel = new TextView(this);
        sensitivityLabel.setTextColor(Color.WHITE);
        sensitivityLabel.setTextSize(16);
        root.addView(sensitivityLabel, matchWidth());

        SeekBar slider = new SeekBar(this);
        slider.setMax(MAX_DEG - MIN_DEG);
        slider.setProgress(PrivacyService.sensitivityDeg - MIN_DEG);
        slider.setPadding((int) (8 * dp), (int) (12 * dp), (int) (8 * dp), (int) (12 * dp));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                PrivacyService.sensitivityDeg = p + MIN_DEG;
                updateSensitivityLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) { }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                getSharedPreferences(PrivacyService.PREFS, MODE_PRIVATE).edit()
                        .putInt(PrivacyService.KEY_SENSITIVITY, PrivacyService.sensitivityDeg)
                        .apply();
            }
        });
        root.addView(slider, matchWidth());

        TextView hint = new TextView(this);
        hint.setText("Left = darkens with a small tilt (more private)\nRight = allows more tilt");
        hint.setTextColor(0xFF7F8EA3);
        hint.setTextSize(13);
        root.addView(hint, matchWidth());

        // ---- side bands
        TextView sidesLabel = new TextView(this);
        sidesLabel.setTextColor(Color.WHITE);
        sidesLabel.setTextSize(16);
        LinearLayout.LayoutParams slp = matchWidth();
        slp.topMargin = (int) (20 * dp);
        root.addView(sidesLabel, slp);
        sidesLabel.setText("Black edges: " + PrivacyService.sidePercent + "% each side");

        SeekBar sides = new SeekBar(this);
        sides.setMax(30);
        sides.setProgress(PrivacyService.sidePercent);
        sides.setPadding((int) (8 * dp), (int) (12 * dp), (int) (8 * dp), (int) (12 * dp));
        sides.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                PrivacyService.sidePercent = p;
                sidesLabel.setText("Black edges: " + p + "% each side");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) { }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                getSharedPreferences(PrivacyService.PREFS, MODE_PRIVATE).edit()
                        .putInt(PrivacyService.KEY_SIDES, PrivacyService.sidePercent).apply();
            }
        });
        root.addView(sides, matchWidth());

        // ---- face check
        faceSwitch = makeSwitch("Only show the screen when my face is there", dp);
        faceSwitch.setChecked(PrivacyService.faceCheck && PrivacyService.hasCamera(this));
        faceSwitch.setOnCheckedChangeListener((b, on) -> {
            if (on && !PrivacyService.hasCamera(this)) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                return; // finished in onRequestPermissionsResult
            }
            setFaceCheck(on);
        });
        root.addView(faceSwitch, matchWidth());

        Switch multi = makeSwitch("Black out if someone else is looking too", dp);
        multi.setChecked(PrivacyService.multiFace);
        multi.setOnCheckedChangeListener((b, on) -> {
            PrivacyService.multiFace = on;
            getSharedPreferences(PrivacyService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(PrivacyService.KEY_MULTI, on).apply();
        });
        root.addView(multi, matchWidth());

        resetButton = new Button(this);
        resetButton.setText("Reset viewing angle");
        resetButton.setAllCaps(false);
        resetButton.setTextColor(Color.WHITE);
        resetButton.setBackground(rounded(0xFF2A3A52, 14 * dp));
        LinearLayout.LayoutParams rp = matchWidth();
        rp.topMargin = (int) (20 * dp);
        root.addView(resetButton, rp);
        resetButton.setOnClickListener(v -> {
            PrivacyService.recalibrate(this);
            Toast.makeText(this, "Got it — this is your new viewing angle", Toast.LENGTH_SHORT).show();
        });

        updateSensitivityLabel();
        return root;
    }

    private Switch makeSwitch(String text, float dp) {
        Switch sw = new Switch(this);
        sw.setText(text);
        sw.setTextColor(Color.WHITE);
        sw.setTextSize(15);
        sw.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));
        return sw;
    }

    private void setFaceCheck(boolean on) {
        PrivacyService.faceCheck = on;
        getSharedPreferences(PrivacyService.PREFS, MODE_PRIVATE).edit()
                .putBoolean(PrivacyService.KEY_FACE, on).apply();
        PrivacyService.restart(this);
        handler.postDelayed(this::refresh, 800);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_CAMERA) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Toast.makeText(this, "Camera is needed to check for your face", Toast.LENGTH_LONG).show();
            faceSwitch.setChecked(false);
        }
        setFaceCheck(granted);
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private void updateSensitivityLabel() {
        sensitivityLabel.setText("Allowed tilt: " + PrivacyService.sensitivityDeg + "°");
    }

    private void refresh() {
        boolean on = PrivacyService.isRunning();
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(on ? GREEN : BRAND);
        circle.setStroke((int) (4 * getResources().getDisplayMetrics().density), 0x55FFFFFF);
        bigButton.setBackground(circle);
        bigButton.setText(on ? "ON" : "OFF");
        String faceNote = "";
        if (on && PrivacyService.faceCheck && PrivacyService.hasCamera(this)) {
            faceNote = PrivacyService.faceActive
                    ? "\nFace check is on (front camera in use)."
                    : "\nFace check is waiting for the camera.";
        }
        status.setText(on
                ? "Privacy screen is active." + faceNote
                : "Tap to turn on");
        resetButton.setEnabled(on);
        resetButton.setAlpha(on ? 1f : 0.4f);
    }

    // -------------------------------------------------------------- actions

    private void toggle() {
        if (PrivacyService.isRunning()) {
            PrivacyService.stop(this);
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this,
                        "Allow TiltGuard to \"display over other apps\", then come back",
                        Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            if (PrivacyService.faceCheck && !PrivacyService.hasCamera(this)) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            }
            PrivacyService.start(this);
        }
        handler.postDelayed(this::refresh, 600);
    }
}
