package com.example.meetomxh.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Camera shutter with a real circular progress indicator and a clear stop state. */
public final class ReelRecordButton extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private boolean recording;
    private float progress;
    public ReelRecordButton(Context context, AttributeSet attrs) {
        super(context, attrs);setClickable(true);setFocusable(true);setContentDescription("Bắt đầu quay video");
    }
    public void setRecording(boolean value) {
        recording = value;setContentDescription(value ? "Kết thúc bản quay" : "Bắt đầu quay video");invalidate();
    }
    public void setProgress(float value) { progress = Math.max(0, Math.min(1, value));invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float cx = getWidth() / 2f, cy = getHeight() / 2f, radius = Math.min(cx, cy) - 5 * density;
        paint.setAlpha(isEnabled() ? 255 : 130);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(4 * density);
        paint.setColor(0xAAFFFFFF);canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(0xFFFF4275);bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(bounds, -90, 360 * progress, false, paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xFFFF4275);
        if (recording) {
            float half = radius * .42f;bounds.set(cx - half, cy - half, cx + half, cy + half);
            canvas.drawRoundRect(bounds, 6 * density, 6 * density, paint);
        } else canvas.drawCircle(cx, cy, radius - 8 * density, paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { animate().scaleX(.93f).scaleY(.93f).setDuration(100).start();return true; }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            animate().scaleX(1).scaleY(1).setDuration(100).start();
            if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();return true;
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick();return true; }
}
