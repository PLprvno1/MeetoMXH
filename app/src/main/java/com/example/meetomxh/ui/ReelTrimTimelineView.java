package com.example.meetomxh.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Millisecond range model with independently draggable playhead and a bounded filmstrip cache. */
public final class ReelTrimTimelineView extends View {
    public interface Listener {
        void rangeChanged(long startMs, long endMs, long seekMs);
        void scrub(long timeMs);
        void interactionFinished();
    }
    private static final long MAX_CLIP = 60_000;
    private static final int FRAME_COUNT = 12;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final LruCache<Integer, Bitmap> frames = new LruCache<Integer, Bitmap>(2 * 1024 * 1024) {
        @Override protected int sizeOf(Integer key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Listener listener;
    private long duration, start, end, playhead, downStart, downEnd;
    private float downX;
    private int drag, generation;
    private volatile boolean closed;

    public ReelTrimTimelineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setClickable(true);
        setContentDescription("Dòng thời gian: kéo tay nắm để cắt, kéo vạch trắng để xem, kéo thước thời gian phía dưới để di chuyển đoạn");
    }
    public void setListener(Listener value) { listener = value; }
    public void setSource(File file, long durationMs, long startMs, long endMs) {
        duration = durationMs;
        start = Math.max(0, Math.min(startMs, duration - minimum()));
        end = Math.min(duration, Math.max(start + minimum(), Math.min(endMs, start + MAX_CLIP)));
        playhead = start;
        frames.evictAll();
        final int token = ++generation;
        invalidate();
        worker.execute(() -> {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getAbsolutePath());
                for (int index = 0; index < FRAME_COUNT && !closed; index++) {
                    if (token != generation || Thread.currentThread().isInterrupted()) break;
                    long atUs = Math.max(0, Math.min(durationMs - 1, durationMs * index / FRAME_COUNT)) * 1000;
                    Bitmap frame = null;
                    try {
                        if (Build.VERSION.SDK_INT >= 27) frame = retriever.getScaledFrameAtTime(atUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 96);
                        else {
                            Bitmap original = retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                            if (original != null) {
                                frame = Bitmap.createScaledBitmap(original, 160, 96, true);
                                if (frame != original) original.recycle();
                            }
                        }
                    } catch (RuntimeException ignored) { /* Keep the range usable without thumbnails. */ }
                    Bitmap ready = frame;
                    int key = index;
                    if (ready != null) post(() -> {
                        if (closed || token != generation) { ready.recycle(); return; }
                        frames.put(key, ready);
                        invalidate();
                    });
                }
            } catch (Exception ignored) { /* Unsupported thumbnail extraction is not a playback error. */ }
            finally { try { retriever.release(); } catch (Exception ignored) { } }
        });
    }
    public void setPlayhead(long timeMs) {
        if (drag != 0) return;
        playhead = Math.max(0, Math.min(duration, timeMs));
        invalidate();
    }
    public boolean isInteracting() { return drag != 0; }
    private long minimum() { return Math.min(1000, duration); }
    private float dp(float n) { return n * getResources().getDisplayMetrics().density; }
    private float left() { return dp(22); }
    private float width() { return Math.max(1, getWidth() - 2 * left()); }
    private float x(long time) { return left() + width() * time / Math.max(1, duration); }
    private long time(float x) { return Math.max(0, Math.min(duration, Math.round((double) (x - left()) / width() * duration))); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float top = dp(30), bottom = getHeight() - dp(32);
        paint.setColor(0xFF303034);
        rect.set(left(), top, left() + width(), bottom);
        canvas.drawRoundRect(rect, dp(6), dp(6), paint);
        for (int index = 0; index < FRAME_COUNT; index++) {
            Bitmap frame = frames.get(index);
            float a = left() + width() * index / FRAME_COUNT;
            float b = left() + width() * (index + 1) / FRAME_COUNT;
            if (frame != null && !frame.isRecycled()) {
                rect.set(a, top, b, bottom);
                // Crop the small decoded thumbnail instead of stretching it to the strip cell.
                float ratio = (b - a) / Math.max(1, bottom - top);
                int cropWidth = frame.getWidth(), cropHeight = frame.getHeight();
                if (cropWidth / (float) cropHeight > ratio) cropWidth = Math.max(1, Math.round(cropHeight * ratio));
                else cropHeight = Math.max(1, Math.round(cropWidth / ratio));
                int dx = (frame.getWidth() - cropWidth) / 2, dy = (frame.getHeight() - cropHeight) / 2;
                canvas.drawBitmap(frame, new Rect(dx, dy, dx + cropWidth, dy + cropHeight), rect, paint);
            } else {
                paint.setColor(index % 2 == 0 ? 0xFF36363B : 0xFF414147);
                canvas.drawRect(a, top, b, bottom, paint);
            }
        }
        paint.setColor(0xAA000000);
        canvas.drawRect(left(), top, x(start), bottom, paint);
        canvas.drawRect(x(end), top, left() + width(), bottom, paint);
        paint.setColor(0xFFFF4275);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(3));
        rect.set(x(start), top, x(end), bottom);
        canvas.drawRect(rect, paint);
        paint.setStyle(Paint.Style.FILL);
        for (float handle : new float[]{x(start), x(end)}) {
            rect.set(handle - dp(8), top - dp(2), handle + dp(8), bottom + dp(2));
            canvas.drawRoundRect(rect, dp(4), dp(4), paint);
        }
        paint.setColor(Color.WHITE);paint.setStrokeWidth(dp(2));
        for (float handle : new float[]{x(start), x(end)}) canvas.drawLine(handle, top + dp(12), handle, bottom - dp(12), paint);
        canvas.drawLine(x(playhead), dp(10), x(playhead), bottom + dp(8), paint);
        canvas.drawCircle(x(playhead), dp(13), dp(5), paint);
        paint.setTextSize(dp(10));
        for (int tick = 0; tick <= 4; tick++) {
            long at = duration * tick / 4;
            paint.setTextAlign(tick == 0 ? Paint.Align.LEFT : tick == 4 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            canvas.drawText(format(at), x(at), getHeight() - dp(8), paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || duration <= 0) return false;
        float touch = event.getX();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = touch;downStart = start;downEnd = end;
                if (event.getY() >= getHeight() - dp(28)) drag = 4;
                else if (event.getY() < dp(29)) drag = 3;
                else if (Math.min(Math.abs(touch - x(start)), Math.abs(touch - x(end))) <= dp(22))
                    drag = Math.abs(touch - x(start)) <= Math.abs(touch - x(end)) ? 1 : 2;
                else if (touch > x(start) && touch < x(end)) drag = 4;
                else drag = 3;
                getParent().requestDisallowInterceptTouchEvent(true);
                updateTouch(touch);return true;
            case MotionEvent.ACTION_MOVE:
                updateTouch(touch);return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drag = 0;getParent().requestDisallowInterceptTouchEvent(false);
                if (listener != null) listener.interactionFinished();
                if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            default:return true;
        }
    }
    private void updateTouch(float touch) {
        long at = time(touch);
        if (drag == 1) {
            start = Math.max(Math.max(0, end - MAX_CLIP), Math.min(at, end - minimum()));
            playhead = start;
        } else if (drag == 2) {
            end = Math.max(start + minimum(), Math.min(at, Math.min(duration, start + MAX_CLIP)));
            playhead = end;
        } else if (drag == 4) {
            long length = downEnd - downStart;
            long delta = Math.round((double) (touch - downX) / width() * duration);
            start = Math.max(0, Math.min(duration - length, downStart + delta));
            end = start + length;playhead = start;
        } else playhead = at;
        if (listener != null) {
            if (drag == 3) listener.scrub(playhead);
            else listener.rangeChanged(start, end, playhead);
        }
        invalidate();
    }
    @Override public boolean performClick() { super.performClick();return true; }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            playhead = Math.max(start, Math.min(end, playhead + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -500 : 500)));
            if (listener != null) listener.scrub(playhead);
            invalidate();return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    public static String format(long ms) {
        return String.format(Locale.ROOT, "%02d:%02d.%01d", ms / 60000, ms / 1000 % 60, ms / 100 % 10);
    }
    public void release() { closed = true;generation++;worker.shutdownNow();frames.evictAll(); }
}
