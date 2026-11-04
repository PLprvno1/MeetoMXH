package com.example.meetomxh;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

public final class ZoomableImageView extends AppCompatImageView {
    public interface PageListener { void onNext(); void onPrevious(); }
    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float scale = 1f, lastX, lastY, downX, downY;
    private PageListener pageListener;

    public ZoomableImageView(Context context, @Nullable android.util.AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float next = Math.max(1f, Math.min(4f, scale * detector.getScaleFactor()));
                float applied = next / scale;
                matrix.postScale(applied, applied, detector.getFocusX(), detector.getFocusY());
                scale = next;
                setImageMatrix(matrix);
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (scale > 1f) resetMatrix();
                else {
                    scale = 2f;
                    matrix.postScale(2f, 2f, event.getX(), event.getY());
                    setImageMatrix(matrix);
                }
                return true;
            }
        });
    }

    public void setPageListener(PageListener listener) { pageListener = listener; }

    @Override public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::resetMatrix);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetMatrix();
    }

    private void resetMatrix() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() <= 0 || getHeight() <= 0) return;
        float sx = getWidth() / (float) drawable.getIntrinsicWidth();
        float sy = getHeight() / (float) drawable.getIntrinsicHeight();
        float base = Math.min(sx, sy);
        float left = (getWidth() - drawable.getIntrinsicWidth() * base) / 2f;
        float top = (getHeight() - drawable.getIntrinsicHeight() * base) / 2f;
        matrix.reset();
        matrix.setScale(base, base);
        matrix.postTranslate(left, top);
        scale = 1f;
        setImageMatrix(matrix);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && scale > 1f && event.getPointerCount() == 1) {
                    matrix.postTranslate(event.getX() - lastX, event.getY() - lastY);
                    setImageMatrix(matrix);
                }
                lastX = event.getX(); lastY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (scale == 1f && Math.abs(dx) > 100 && Math.abs(dx) > Math.abs(dy) * 1.3f && pageListener != null) {
                    if (dx < 0) pageListener.onNext(); else pageListener.onPrevious();
                }
                performClick();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
