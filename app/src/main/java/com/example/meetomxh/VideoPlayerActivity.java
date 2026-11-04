package com.example.meetomxh;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;

import com.example.meetomxh.databinding.ActivityVideoPlayerBinding;
import com.example.meetomxh.storage.SupabaseStorage;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.Subscription;
import com.example.meetomxh.supabase.SupabaseConfig;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public final class VideoPlayerActivity extends AppCompatActivity {
    public static final String EXTRA_ASSET = "asset_reference";
    public static final String EXTRA_STORY_MODE = "story_mode";
    private ActivityVideoPlayerBinding binding;
    private String reference;
    private ExoPlayer player;
    private Subscription request;
    private long resumePosition;
    private boolean resumeWhenReady;
    private boolean muted;
    private boolean fullscreen;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding = ActivityVideoPlayerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        reference = getIntent().getStringExtra(EXTRA_ASSET);
        if (reference == null || !reference.startsWith("asset://")) { finish(); return; }
        binding.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        binding.playerView.setControllerShowTimeoutMs(3500);
        binding.playerClose.setOnClickListener(v -> closePlayer());
        binding.playerRetry.setOnClickListener(v -> loadVideo());
        binding.playerMute.setOnClickListener(v -> setMuted(!muted));
        binding.playerFullscreen.setOnClickListener(v -> setFullscreen(!fullscreen));
        if (state != null) {
            resumePosition = state.getLong("position", 0L);
            resumeWhenReady = state.getBoolean("playing", false);
            muted = state.getBoolean("muted", false);
        } else resumeWhenReady = true;
    }

    @Override protected void onStart() { super.onStart(); loadVideo(); }

    private void loadVideo() {
        if (request != null) request.remove();
        releasePlayer();
        binding.playerErrorPanel.setVisibility(View.GONE);
        binding.playerLoading.setVisibility(View.VISIBLE);
        request = Api.run(() -> {
            JSONObject asset = SupabaseStorage.info(reference);
            if (!asset.optString("mime_type").startsWith("video/"))
                throw new IllegalArgumentException("This media is not a video");
            return asset;
        }, new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject asset) {
                if (isFinishing() || isDestroyed() || binding == null) return;
                preparePlayer(asset);
            }
            @Override public void error(String error) { showError(error); }
        });
    }

    private void preparePlayer(JSONObject asset) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("apikey", SupabaseConfig.KEY);
            headers.put("Authorization", "Bearer " + SessionManager.getInstance().access(false));
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(headers)
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(30000)
                    .setAllowCrossProtocolRedirects(false);
            DefaultDataSource.Factory data = new DefaultDataSource.Factory(this, http);
            DefaultMediaSourceFactory media = new DefaultMediaSourceFactory(data);
            player = new ExoPlayer.Builder(this).setMediaSourceFactory(media).build();
            binding.playerView.setPlayer(player);
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (binding == null) return;
                    binding.playerLoading.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                    if (state == Player.STATE_READY) binding.playerErrorPanel.setVisibility(View.GONE);
                }
                @Override public void onPlayerError(PlaybackException error) {
                    showError("Không phát được video hoặc quyền xem đã thay đổi.");
                }
            });
            player.setMediaItem(MediaItem.fromUri(SupabaseStorage.url(asset)));
            player.prepare();
            player.seekTo(resumePosition);
            player.setPlayWhenReady(resumeWhenReady);
            setMuted(muted);
            binding.playerLoading.setVisibility(View.VISIBLE);
        } catch (Exception error) {
            showError("Không thể mở video. Hãy thử lại.");
        }
    }

    private void setMuted(boolean value) {
        muted = value;
        if (player != null) player.setVolume(muted ? 0f : 1f);
        if (binding != null) {
            binding.playerMute.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
            binding.playerMute.setContentDescription(muted ? "Bật tiếng" : "Tắt tiếng");
        }
    }

    private void setFullscreen(boolean value) {
        fullscreen = value;
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(value
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                : View.SYSTEM_UI_FLAG_VISIBLE);
        ViewGroup.LayoutParams params = binding.playerView.getLayoutParams();
        params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        binding.playerView.setLayoutParams(params);
        binding.playerFullscreen.setImageResource(value ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
        binding.playerFullscreen.setContentDescription(value ? "Thoát toàn màn hình" : "Toàn màn hình");
    }

    private void showError(String message) {
        if (binding == null || isFinishing() || isDestroyed()) return;
        binding.playerLoading.setVisibility(View.GONE);
        binding.playerErrorText.setText(message == null || message.isEmpty()
                ? "Không thể phát video hoặc quyền xem đã thay đổi." : message);
        binding.playerErrorPanel.setVisibility(View.VISIBLE);
    }

    private void closePlayer() { if (getIntent().getBooleanExtra(EXTRA_STORY_MODE,false)) setResult(RESULT_OK); finish(); }

    @Override public void onBackPressed() { closePlayer(); }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (player != null) {
            resumePosition = player.getCurrentPosition();
            resumeWhenReady = player.isPlaying();
        }
        out.putLong("position", resumePosition);
        out.putBoolean("playing", resumeWhenReady);
        out.putBoolean("muted", muted);
        super.onSaveInstanceState(out);
    }

    @Override protected void onStop() {
        if (request != null) { request.remove(); request = null; }
        if (player != null) {
            resumePosition = player.getCurrentPosition();
            resumeWhenReady = player.isPlaying();
        }
        releasePlayer();
        super.onStop();
    }

    private void releasePlayer() {
        if (player == null) return;
        binding.playerView.setPlayer(null);
        player.release();
        player = null;
    }

    @Override protected void onDestroy() {
        if (request != null) request.remove();
        releasePlayer();
        binding = null;
        super.onDestroy();
    }
}
