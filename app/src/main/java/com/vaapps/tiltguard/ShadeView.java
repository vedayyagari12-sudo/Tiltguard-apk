package com.vaapps.tiltguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/**
 * One black edge band (left or right), like the dark edges of a privacy filter.
 * Solid black at the outer edge, softening toward the middle of the screen.
 */
public class ShadeView extends View {

    private final boolean left;
    private final Paint paint = new Paint();
    private int shaderWidth = -1;

    public ShadeView(Context c, boolean left) {
        super(c);
        this.left = left;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (w != shaderWidth) {
            shaderWidth = w;
            int[] colors = {Color.BLACK, Color.BLACK, Color.TRANSPARENT};
            float[] stops = {0f, 0.7f, 1f};
            paint.setShader(left
                    ? new LinearGradient(0, 0, w, 0, colors, stops, Shader.TileMode.CLAMP)
                    : new LinearGradient(w, 0, 0, 0, colors, stops, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, h, paint);
    }
}
