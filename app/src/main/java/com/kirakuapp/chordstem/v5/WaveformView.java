package com.kirakuapp.chordstem.v5;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** 波形图视图 — 品红进度条 + 暖白底，配色取自 ChordStem 图标。 */
public final class WaveformView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private float progress;
    private float loopA = -1f, loopB = -1f;

    private static final int BAR_BG = Color.rgb(239, 232, 214);
    private static final int BAR_ACTIVE = Color.rgb(233, 0, 255);
    private static final int LOOP_A_COLOR = Color.rgb(245, 107, 88);
    private static final int LOOP_B_COLOR = Color.rgb(255, 0, 114);
    private static final int CURSOR = Color.rgb(20, 16, 10);

    public WaveformView(Context ctx) { super(ctx); init(); }
    public WaveformView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }

    private void init() {
        setContentDescription("音频波形");
        setMinimumHeight(dp(112));
    }

    public void setProgress(float v) { progress = clamp(v); invalidate(); }
    public void setLoop(float a, float b) { loopA = a < 0 ? -1f : clamp(a); loopB = b < 0 ? -1f : clamp(b); invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight(), cy = h / 2f;
        int count = Math.max(36, (int) (w / dp(7)));
        float gap = dp(3);
        float iw = Math.max(dp(2), (w - gap * (count - 1)) / count);
        float ax = w * progress;

        for (int i = 0; i < count; i++) {
            float x = i * (iw + gap);
            double sig = 0.22 + Math.abs(Math.sin(i * 0.31)) * 0.38 + Math.abs(Math.sin(i * 0.093 + 1.8)) * 0.30;
            float half = (float) sig * (h * 0.42f);
            paint.setColor(x <= ax ? BAR_ACTIVE : BAR_BG);
            bar.set(x, cy - half, x + iw, cy + half);
            c.drawRoundRect(bar, iw / 2f, iw / 2f, paint);
        }

        if (loopA >= 0) drawMarker(c, w * loopA, "A", LOOP_A_COLOR);
        if (loopB >= 0) drawMarker(c, w * loopB, "B", LOOP_B_COLOR);

        paint.setColor(CURSOR);
        paint.setStrokeWidth(dp(2));
        c.drawLine(ax, dp(3), ax, h - dp(3), paint);
    }

    private void drawMarker(Canvas c, float x, String label, int color) {
        paint.setColor(color);
        paint.setStrokeWidth(dp(2));
        c.drawLine(x, 0, x, getHeight(), paint);
        paint.setTextSize(dp(12));
        paint.setFakeBoldText(true);
        c.drawText(label, Math.min(Math.max(dp(2), x + dp(4)), getWidth() - dp(16)), dp(14), paint);
        paint.setFakeBoldText(false);
    }

    private float clamp(float v) { return Math.max(0, Math.min(v, 1)); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
