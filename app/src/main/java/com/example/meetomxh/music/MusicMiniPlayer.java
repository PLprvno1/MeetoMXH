package com.example.meetomxh.music;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.media3.common.Player;
import com.example.meetomxh.R;
import java.util.Locale;

/** Owns one local preview player. Both modes use a zero-based preview timeline. */
public final class MusicMiniPlayer extends FrameLayout {
    public enum Mode { TRACK, SEGMENT }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView title, time;
    private final SeekBar seek;
    private final Button play, action;
    private Player player;
    private long durationMs;
    private boolean dragging;
    private Runnable closed = () -> {};
    private java.util.function.Consumer<String> error = ignored -> {};
    private Player.Listener listener;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            update(); main.postDelayed(this, 250);
        }
    };

    public MusicMiniPlayer(Context context, AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(context).inflate(R.layout.view_music_mini_player, this, true);
        title = findViewById(R.id.mini_music_title); time = findViewById(R.id.mini_music_time);
        seek = findViewById(R.id.mini_music_seek); play = findViewById(R.id.mini_music_play);
        action = findViewById(R.id.mini_music_action);
        findViewById(R.id.mini_music_close).setOnClickListener(v -> close());
        play.setOnClickListener(v -> toggle());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            public void onProgressChanged(SeekBar bar, int position, boolean user) {
                if (user) time.setText(format(position) + " / " + format(durationMs));
            }
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (player != null) player.seekTo(Math.min(bar.getProgress(), Math.max(0, durationMs - 1)));
                update();
            }
        });
        setVisibility(GONE);
    }

    public void setOnClosed(Runnable callback) { closed = callback; }
    public void setOnError(java.util.function.Consumer<String> callback) { error = callback; }
    public boolean isOpen() { return player != null; }
    public long positionMs() { return player == null ? 0 : Math.max(0, player.getCurrentPosition()); }
    public void pause() { if (player != null) player.pause(); update(); }
    public void toggle() {
        if(player==null)return;
        // Buffering and audio-focus suppression can make isPlaying() false even
        // though playback is requested. A second tap must cancel that request.
        if(player.getPlayWhenReady()&&player.getPlaybackState()!=Player.STATE_ENDED)player.pause();
        else{if(player.getPlaybackState()==Player.STATE_ENDED)player.seekTo(0);player.play();}
        update();
    }
    public void show(OpenverseTrack track, Mode mode, Player prepared, long lengthMs,
                     long positionMs, boolean autoplay, Runnable confirm) {
        close(); player = prepared; durationMs = Math.max(1, lengthMs);
        // Ownership transfers here, including failures while attaching/seeking.
        try {
            listener=new Player.Listener(){
                @Override public void onEvents(Player observed,Player.Events events){if(player==prepared)update();}
                @Override public void onPlaybackStateChanged(int state){
                    if(player==prepared)android.util.Log.i("MeetoMusic","stage=mini_player_state state="+state+" playWhenReady="+prepared.getPlayWhenReady());
                }
                @Override public void onPlaybackSuppressionReasonChanged(int reason){
                    if(player==prepared)android.util.Log.i("MeetoMusic","stage=mini_player_suppression reason="+reason);
                }
                @Override public void onPlayerError(androidx.media3.common.PlaybackException failure){
                    if(player!=prepared)return;
                    MusicDiagnostics.failure("mini_player_async",failure);
                    int code=failure.errorCode;
                    close();error.accept("Không nghe thử được nhạc (mã Media3 "+code+"). Hãy thử lại; bản chỉnh sửa vẫn được giữ.");
                }
            };
            title.setText(track.title + (mode == Mode.TRACK && !track.creator.isEmpty() ? " · " + track.creator : ""));
            action.setText(mode == Mode.TRACK ? "Chọn nhạc" : "Xác nhận");
            action.setOnClickListener(v -> confirm.run());
            seek.setMax((int)Math.min(Integer.MAX_VALUE, durationMs));
            player.addListener(listener);
            // A supplied player may already have failed before its listener was attached.
            if(prepared.getPlayerError()!=null){listener.onPlayerError(prepared.getPlayerError());return;}
            player.seekTo(Math.min(Math.max(0, positionMs), durationMs - 1));
            setVisibility(VISIBLE); main.post(tick);
            post(() -> {if(player==prepared&&isAttachedToWindow())requestRectangleOnScreen(new android.graphics.Rect(0, 0, getWidth(), getHeight()), false);});
            if (autoplay) player.play();
            update();
            } catch(RuntimeException failure) { close(); throw failure; }
    }

    public void close() {
        main.removeCallbacks(tick); dragging = false;
        Player previous = player; player = null;
        Player.Listener previousListener=listener;listener=null;
        if (previous != null) {
            try{if(previousListener!=null)previous.removeListener(previousListener);}
            catch(RuntimeException listenerError){MusicDiagnostics.failure("mini_player_remove_listener",listenerError);}
            finally{try{previous.release();}catch(RuntimeException releaseError){MusicDiagnostics.failure("mini_player_release",releaseError);}}
        }
        setVisibility(GONE);
        if (previous != null) closed.run();
    }

    private void update() {
        if (player == null) return;
        long actual = player.getDuration();
        if (actual > 0) durationMs = Math.min(durationMs, actual);
        seek.setMax((int)Math.min(Integer.MAX_VALUE, durationMs));
        if (!dragging) {
            long position = Math.min(durationMs, positionMs()); seek.setProgress((int)position);
            time.setText(format(position) + " / " + format(durationMs));
        }
        play.setText(player.getPlayWhenReady()&&player.getPlaybackState()!=Player.STATE_ENDED ? "Tạm dừng" : "Phát");
    }
    private static String format(long ms) { return String.format(Locale.ROOT, "%d:%02d", ms / 60_000, ms / 1000 % 60); }
    @Override protected void onDetachedFromWindow() { close(); super.onDetachedFromWindow(); }
}
