package com.example.meetomxh.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Draws only the connector segments belonging to this comment's visible branch. */
public final class CommentTreeGuideView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Boolean> ancestorContinues = new ArrayList<>();
    private int visualDepth;
    private boolean hasPrevious;
    private boolean hasNext;
    private float density;

    public CommentTreeGuideView(Context context) { this(context, null); }
    public CommentTreeGuideView(Context context, @Nullable AttributeSet attrs) { this(context, attrs, 0); }
    public CommentTreeGuideView(Context context, @Nullable AttributeSet attrs, int style) {
        super(context, attrs, style);
        density = getResources().getDisplayMetrics().density;
        paint.setColor(0x55818790);
        paint.setStrokeWidth(dp(1.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setBranch(int depth, List<Boolean> ancestors, boolean previous, boolean next) {
        visualDepth = Math.max(0, depth);
        ancestorContinues = ancestors == null ? new ArrayList<>() : new ArrayList<>(ancestors);
        hasPrevious = previous;
        hasNext = next;
        requestLayout();
        invalidate();
    }

    public int desiredWidth() { return visualDepth == 0 ? 0 : Math.round((visualDepth * 22f + 14f) * density); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (visualDepth <= 0) return;
        float centerY = getHeight() / 2f;
        float step = dp(22f);
        float firstX = dp(8f);
        int ancestorCount = Math.min(visualDepth - 1, ancestorContinues.size());
        for (int i = 0; i < ancestorCount; i++) {
            if (ancestorContinues.get(i)) {
                float x = firstX + i * step;
                canvas.drawLine(x, 0, x, getHeight(), paint);
            }
        }
        float branchX = firstX + (visualDepth - 1) * step;
        if (hasPrevious) canvas.drawLine(branchX, 0, branchX, centerY, paint);
        if (hasNext) canvas.drawLine(branchX, centerY, branchX, getHeight(), paint);
        canvas.drawLine(branchX, centerY, getWidth(), centerY, paint);
    }

    private float dp(float value) { return value * density; }
}
