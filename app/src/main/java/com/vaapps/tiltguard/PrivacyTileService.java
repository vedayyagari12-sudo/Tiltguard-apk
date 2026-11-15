package com.vaapps.tiltguard;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings toggle: swipe down, tap "Privacy Screen". */
public class PrivacyTileService extends TileService {

    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        if (PrivacyService.isRunning()) {
            PrivacyService.stop(this);
        } else if (Settings.canDrawOverlays(this)) {
            PrivacyService.start(this);
        } else {
            openApp();
            return;
        }
        // Give the service a moment to flip its state, then redraw the tile.
        new Handler(Looper.getMainLooper()).postDelayed(this::update, 300);
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(i);
        }
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = PrivacyService.isRunning();
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(on ? "On" : "Off");
        t.updateTile();
    }
}
