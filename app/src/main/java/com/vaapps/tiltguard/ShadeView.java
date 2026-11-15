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
    private boolean louvers;
    private float gain = 1f;

    public ShadeView(Context c) {
        super(c);
        Bitmap tile = Bitmap.createBitmap(3, 1, Bitmap.Config.ARGB_8888);
        tile.setPixel(0, 0, Color.BLACK);
        linePaint.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    /**
     * kx/sx: across the view, ky/sy: down the view. full: black over everything (0..1).
     * gain: multiplies drawn darkness to make up for a see-through window (1 / window alpha).
     */
    public void set(float kx, float sx, float ky, float sy, float full, boolean louvers, float gain) {
        final float e = 0.004f;
        if (Math.abs(kx - this.kx) < e && Math.abs(ky - this.ky) < e
                && Math.abs(sx - this.sx) < e && Math.abs(sy - this.sy) < e
                && Math.abs(full - this.full) < e && (full >= 1f) == (this.full >= 1f)
                && (full <= 0f) == (this.full <= 0f)
                && louvers == this.louvers && gain == this.gain) {
            return;
        }
        this.kx = kx;
        this.sx = sx;
        this.ky = ky;
        this.sy = sy;
        this.full = full;
        this.louvers = louvers;
        this.gain = gain;
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
        if (louvers) {
            canvas.drawRect(0, 0, w, h, linePaint);
        }
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
