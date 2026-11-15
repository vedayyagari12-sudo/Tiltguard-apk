package com.vaapps.tiltguard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/**
 * Software model of a micro-louver privacy filter.
 *
 * A real filter is a sheet of microscopic vertical blinds. Light that leaves the screen
 * at angle A gets through in proportion to  T = 1 - tan(A) / tan(cutoff)  and is fully
 * blocked past the cutoff angle (about 30 degrees on most phone protectors).
 *
 * Because your eye is close to the phone, each column of the screen is seen at a slightly
 * different angle, so the brightness across the screen is a "tent": brightest right under
 * your eye and darker toward the edges. Move off-centre and the tent slides; the far side
 * goes black first, then everything.
 *
 * Here, for a point u across the screen (-1 = left edge, +1 = right edge):
 *     darkness(u) = clamp(|u * s - k|, 0, 1)
 *   k = tan(viewing angle) / tan(cutoff)   (0 = straight on, 1 = at the cutoff)
 *   s = how much darker the edges are than the middle when viewed straight on
 *
 * On top of that it draws the louvers themselves: 1-pixel black lines every 3 pixels,
 * which costs roughly the same ~30% of brightness a real filter does.
 */
public class ShadeView extends View {

    private static final int N = 33;

    private final Paint gradPaint = new Paint();
    private final Paint linePaint = new Paint();
    private final int[] colors = new int[N];

    private float kx, sx, ky, sy, full;
    private int gridCell = 0;        // 0 = grid off, else mesh pitch in pixels
    private float gridAlpha = 0f;    // 0..1 strength of the black/white mesh
    private float decoyAlpha = 0f;   // 0..1 strength of the big soft decoy blobs
    private boolean moving = true;

    private final Paint decoyPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap decoy;
    private BitmapShader meshShader;
    private final android.graphics.Matrix matrix = new android.graphics.Matrix();
    private final long startTime = android.os.SystemClock.uptimeMillis();
    private float gain = 1f;

    public ShadeView(Context c) {
        super(c);
        buildDecoy();
    }

    private void buildDecoy() {
        // Random light/dark field, stretched over the screen with smoothing:
        // large soft blobs that dominate what a distant eye sees.
        java.util.Random r = new java.util.Random();
        decoy = Bitmap.createBitmap(10, 18, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 10; x++) {
                int v = r.nextBoolean() ? 20 + r.nextInt(50) : 185 + r.nextInt(60);
                decoy.setPixel(x, y, Color.rgb(v, v, v));
            }
        }
        decoyPaint.setShader(new BitmapShader(decoy, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR));
    }

    /**
     * Black-and-white mesh. Lines alternate black and white every `cell` pixels, so from a
     * distance they average to grey: dark text gets lighter, white background gets darker,
     * and the contrast someone else needs to read it collapses. Up close you see between them.
     */
    private void buildMesh() {
        if (gridCell <= 0) {
            meshShader = null;
            return;
        }
        int c = gridCell;
        int t = c * 2;
        Bitmap tile = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888);
        for (int i = 0; i < t; i++) {
            tile.setPixel(i, 0, Color.BLACK);
            tile.setPixel(0, i, Color.BLACK);
            tile.setPixel(i, c, Color.WHITE);
            tile.setPixel(c, i, Color.WHITE);
        }
        tile.setPixel(c, c, Color.GRAY);
        tile.setPixel(0, 0, Color.GRAY);
        meshShader = new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        linePaint.setShader(meshShader);

    }
    }

    /**
     * kx/sx: across the view, ky/sy: down the view. full: black over everything (0..1).
     * gain: multiplies drawn darkness to make up for a see-through window (1 / window alpha).
     * gridCell/gridAlpha: optional fine mesh over everything (0 cell = off).
     */
    public void set(float kx, float sx, float ky, float sy, float full,
                    int gridCell, float gridAlpha, float decoyAlpha, boolean moving, float gain) {
        final float e = 0.004f;
        if (Math.abs(kx - this.kx) < e && Math.abs(ky - this.ky) < e
                && Math.abs(sx - this.sx) < e && Math.abs(sy - this.sy) < e
                && Math.abs(full - this.full) < e && (full >= 1f) == (this.full >= 1f)
                && (full <= 0f) == (this.full <= 0f)
                && gridCell == this.gridCell
                && Math.abs(gridAlpha - this.gridAlpha) < e
                && Math.abs(decoyAlpha - this.decoyAlpha) < e && moving == this.moving
                && gain == this.gain) {
            return;
        }
        boolean rebuild = gridCell != this.gridCell;
        this.kx = kx;
        this.sx = sx;
        this.ky = ky;
        this.sy = sy;
        this.full = full;
        this.gridCell = gridCell;
        this.gridAlpha = gridAlpha;
        this.decoyAlpha = decoyAlpha;
        this.moving = moving;
        this.gain = gain;
        if (rebuild) buildMesh();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        if (full >= 0.999f) {
            canvas.drawColor(Color.BLACK);
            return;
        }

        if (fill(kx, sx)) {
            gradPaint.setShader(new LinearGradient(0, 0, w, 0, colors, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, gradPaint);
        }
        if (fill(ky, sy)) {
            gradPaint.setShader(new LinearGradient(0, 0, 0, h, colors, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, gradPaint);
        }
        long t = android.os.SystemClock.uptimeMillis() - startTime;
        boolean animate = false;

        // Decoy blobs fade in as the screen tilts away and dims, and are gone when it's clear.
        float tilt = Math.min(1f, Math.max(Math.max(Math.abs(kx), Math.abs(ky)), full));
        float blobs = decoyAlpha * tilt;
        if (blobs > 0.01f && decoy != null) {
            float sxs = w / 10f * 1.4f;
            float sys = h / 18f * 1.4f;
            float drift = moving ? t / 1000f : 0f;
            matrix.setScale(sxs, sys);
            matrix.postTranslate((float) Math.sin(drift * 0.35) * w * 0.25f,
                    (float) Math.cos(drift * 0.27) * h * 0.2f - drift * 18f);
            decoyPaint.getShader().setLocalMatrix(matrix);
            decoyPaint.setAlpha((int) (Math.min(1f, blobs * gain) * 255));
            canvas.drawRect(0, 0, w, h, decoyPaint);
            animate = moving;
        }

        if (gridCell > 0 && meshShader != null) {
            // Mesh creeps one pixel at a time so the eye can't lock onto it.
            int step = moving ? (int) ((t / 90) % (gridCell * 2)) : 0;
            matrix.setTranslate(step, step / 2f);
            meshShader.setLocalMatrix(matrix);
            linePaint.setAlpha((int) (Math.min(1f, gridAlpha * gain) * 255));
            canvas.drawRect(0, 0, w, h, linePaint);
            animate |= moving;
        }
        if (animate) postInvalidateDelayed(45);
        if (full > 0.001f) {
            canvas.drawColor(Color.argb((int) (Math.min(1f, full * gain) * 255), 0, 0, 0));
        }
    }

    /** Fills the gradient stops for one axis. Returns false if that axis is fully clear. */
    private boolean fill(float k, float s) {
        boolean any = false;
        for (int i = 0; i < N; i++) {
            float u = -1f + 2f * i / (N - 1);
            float a = Math.min(1f, Math.abs(u * s - k)) * gain;
            int alpha = (int) (Math.min(1f, a) * 255);
            if (alpha > 0) any = true;
            colors[i] = alpha << 24;
        }
        return any;
    }
}
