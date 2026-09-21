package com.kirakuapp.chordstem.v5;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.List;

/** 可滚动和弦轨道；播放时当前播放位置保持在中间，点击区块可修正和弦。 */
public final class ChordTimelineView extends View {
    public interface Listener { void onChordClick(int index); }
    private final Paint block = new Paint(1), text = new Paint(1), beat = new Paint(1), cursor = new Paint(1);
    private List<ChordAnalyzerEngine.ChordSegment> chords;
    private List<Long> beats;
    private int duration, position;
    private float density, pixelsPerSecond, scrollPx;
    private float downX, downScrollPx;
    private boolean dragging;
    private Listener listener;

    public ChordTimelineView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        pixelsPerSecond = 86 * density;
        setMinimumHeight((int) (66 * density));
        block.setColor(Color.rgb(247, 222, 234)); text.setColor(Color.rgb(72, 28, 56));
        text.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity);
        beat.setColor(Color.argb(100, 110, 90, 105)); cursor.setColor(Color.rgb(210, 44, 125));
        setContentDescription("可滚动和弦时间线");
        setClickable(true);
    }
    public void setListener(Listener l) { listener = l; }
    public void setData(List<ChordAnalyzerEngine.ChordSegment> c, List<Long> b, int d, int p) {
        chords=c; beats=b; duration=Math.max(1,d); position=Math.max(0, Math.min(p, duration));
        scrollToPosition(); invalidate();
    }
    public void setPosition(int p) {
        position=Math.max(0, Math.min(p, duration));
        if (!dragging) scrollToPosition();
        invalidate();
    }

    private float scale() { return pixelsPerSecond / 1000f; }
    private float contentWidth() { return duration * scale(); }
    private void scrollToPosition() {
        float target = position * scale() - getWidth() / 2f;
        scrollPx = Math.max(0, Math.min(target, Math.max(0, contentWidth() - getWidth())));
    }
    private float xForTime(long timeMs) { return timeMs * scale() - scrollPx; }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); float w=getWidth(), h=getHeight();
        c.drawColor(Color.rgb(255,250,252));
        if (beats != null) for (Long t : beats) {
            float x=xForTime(t);
            if (x >= -1 && x <= w + 1) c.drawRect(x,0,x+Math.max(1,density),h,beat);
        }
        if (chords != null) for (int i=0;i<chords.size();i++) {
            long a=chords.get(i).timeMs, b=i+1<chords.size()?chords.get(i+1).timeMs:duration;
            float x1=xForTime(a), x2=Math.max(x1+4*density,xForTime(b));
            if (x2 < 0 || x1 > w) continue;
            block.setColor(chords.get(i).chordName.equals("—") ? Color.rgb(238,238,238) : Color.rgb(247,222,234));
            c.drawRoundRect(new RectF(x1+density,7*density,x2-density,h-7*density),8*density,8*density,block);
                if (x2-x1>34*density) c.drawText(displayName(chords.get(i).chordName),Math.max(x1+7*density,4*density),h/2+5*density,text);
        }
        float x=w/2f; c.drawRect(x-density,0,x+2*density,h,cursor);
    }

    private String displayName(String name) {
        if (name == null) return "—";
        if (name.startsWith("D#")) return "Eb" + name.substring(2);
        if (name.startsWith("A#")) return "Bb" + name.substring(2);
        return name;
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: downX=e.getX(); downScrollPx=scrollPx; dragging=false; return true;
            case MotionEvent.ACTION_MOVE:
                float delta=e.getX()-downX;
                if (Math.abs(delta)>4*density) dragging=true;
                if (dragging) { scrollPx=Math.max(0,Math.min(downScrollPx-delta,Math.max(0,contentWidth()-getWidth()))); invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging && chords!=null && listener!=null) {
                    long t=(long)((e.getX()+scrollPx)/Math.max(0.0001f,scale()));
                    for (int i=0;i<chords.size();i++) { long a=chords.get(i).timeMs,b=i+1<chords.size()?chords.get(i+1).timeMs:duration; if(t>=a&&t<b){listener.onChordClick(i);break;} }
                }
                dragging=false; return true;
            case MotionEvent.ACTION_CANCEL: dragging=false; return true;
            default: return true;
        }
    }
}
