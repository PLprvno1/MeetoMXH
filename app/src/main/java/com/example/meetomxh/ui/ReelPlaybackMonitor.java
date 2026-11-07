package com.example.meetomxh.ui;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import com.example.meetomxh.BuildConfig;

/** One surface owner per player. READY is not proof that a video frame has rendered. */
public final class ReelPlaybackMonitor implements Player.Listener {
    public interface Callback {
        void waiting();
        void firstFrame();
        void failed(String message);
    }
    private final ExoPlayer player;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PlayerView target;
    private String mediaId = "none";
    private boolean rendered, failed, released;
    private int recoveries;
    private long lastPosition, progressingWithoutFrame;
    private final Runnable watchdog = this::checkFrame;

    public ReelPlaybackMonitor(ExoPlayer player, Callback callback) {
        this.player = player;
        this.callback = callback;
        player.addListener(this);
    }

    public void begin(String id) {
        if(released)return;
        mediaId = id == null ? "unknown" : id;
        recoveries = 0;
        failed = false;
        waitForFrame();
    }

    public void attach(PlayerView next) {
        if(released)return;
        if (target == next && next != null && next.getPlayer() == player) return;
        PlayerView previous = target;
        target = next;
        // Attach the new surface before clearing the old surface, as required by Media3.
        PlayerView.switchTargetView(player, previous, next);
        diagnostic(next == null ? "detach" : "attach");
        if (next == null) {
            player.pause();
            main.removeCallbacks(watchdog);
        } else {
            if (!failed) waitForFrame();
        }
    }

    public boolean owns(PlayerView view) { return target == view; }
    public boolean hasFrame() { return rendered; }
    public boolean hasFailed() { return failed; }

    private void waitForFrame() {
        if(released)return;
        rendered = false;
        progressingWithoutFrame = 0;
        lastPosition = player.getCurrentPosition();
        callback.waiting();
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, 500);
    }

    private void checkFrame() {
        if (released || target == null || failed || rendered) return;
        if (!target.isAttachedToWindow() || !target.isShown()) {
            diagnostic("surface_not_visible");
            player.pause();
            return;
        }
        long position = player.getCurrentPosition();
        if (player.getPlaybackState() == Player.STATE_READY && player.getPlayWhenReady()
                && position > lastPosition) progressingWithoutFrame += 500;
        lastPosition = position;
        if (progressingWithoutFrame >= 5000) {
            diagnostic("ready_without_first_frame");
            if (recoveries++ == 0) {
                // One bounded surface recovery; no URL reload, decoder loop or global TextureView switch.
                PlayerView view = target;
                PlayerView.switchTargetView(player, view, null);
                PlayerView.switchTargetView(player, null, view);
                player.seekTo(position);
                waitForFrame();
            } else fail("Không thể hiển thị video này. Nhấn Thử lại hoặc chuyển video khác.");
            return;
        }
        main.postDelayed(watchdog, 500);
    }

    @Override public void onRenderedFirstFrame() {
        if(released)return;
        if (target == null || !target.isAttachedToWindow() || failed) return;
        if (player.getCurrentMediaItem() == null || !mediaId.equals(player.getCurrentMediaItem().mediaId)) return;
        rendered = true;
        main.removeCallbacks(watchdog);
        diagnostic("first_frame");
        callback.firstFrame();
    }

    @Override public void onPlaybackStateChanged(int state) {
        if(released)return;
        diagnostic("state_" + state);
        if (state == Player.STATE_READY && !rendered && !failed) {
            main.removeCallbacks(watchdog);
            main.postDelayed(watchdog, 500);
        }
        if (state == Player.STATE_ENDED && !rendered && !failed) fail("Video kết thúc nhưng chưa hiển thị được hình ảnh.");
    }

    @Override public void onTracksChanged(Tracks tracks) {
        if(released)return;
        if (tracks.isEmpty()) return;
        diagnostic("video_track_" + tracks.containsType(C.TRACK_TYPE_VIDEO)
                + "_selected_" + tracks.isTypeSelected(C.TRACK_TYPE_VIDEO));
        if (!tracks.containsType(C.TRACK_TYPE_VIDEO)) fail("Tệp này không có hình ảnh video.");
        else if (!tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)) fail("Thiết bị không hỗ trợ giải mã video này.");
    }

    @Override public void onVideoSizeChanged(VideoSize size) {
        if(released)return;
        diagnostic("size_" + size.width + "x" + size.height);
    }

    @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
        if(released)return;
        // A sheet dismissal, scroll callback or native controls must not restart audio after a render failure.
        if (playWhenReady && (failed || target == null)) player.pause();
    }

    @Override public void onPlayerError(PlaybackException error) {
        if(released)return;
        diagnostic("error_" + error.getErrorCodeName());
        fail(error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                ? "Không thể giải mã video này trên thiết bị. Hãy thử video khác."
                : "Không thể phát video này. Nhấn Thử lại.");
    }

    private void fail(String message) {
        if (released || failed) return;
        failed = true;
        player.pause();
        main.removeCallbacks(watchdog);
        callback.failed(message);
    }

    private void diagnostic(String event) {
        // Only an internal media ID, states and dimensions. Never log URLs, tokens or local paths.
        if (BuildConfig.DEBUG) Log.d("ReelPlayback", "id=" + mediaId + " event=" + event
                + " attached=" + (target != null && target.isAttachedToWindow()));
    }

    public void release() {
        if(released)return;
        released=true;rendered=false;
        main.removeCallbacks(watchdog);
        player.removeListener(this);
        PlayerView previous=target;target=null;
        PlayerView.switchTargetView(player,previous,null);
        player.pause();
    }
}
