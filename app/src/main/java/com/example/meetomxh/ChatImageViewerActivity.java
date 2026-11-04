package com.example.meetomxh;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.example.meetomxh.databinding.ActivityChatImageViewerBinding;
import com.example.meetomxh.storage.SupabaseStorage;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Subscription;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ChatImageViewerActivity extends AppCompatActivity {
    public static final String EXTRA_IMAGE_URL = "image_url";
    public static final String EXTRA_IMAGE_URLS = "image_urls";
    public static final String EXTRA_START_INDEX = "image_start_index";

    private ActivityChatImageViewerBinding binding;
    private final List<String> references = new ArrayList<>();
    private int index;
    private Subscription request;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding = ActivityChatImageViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        String[] list = getIntent().getStringArrayExtra(EXTRA_IMAGE_URLS);
        if (list != null) references.addAll(Arrays.asList(list));
        if (references.isEmpty()) {
            String single = getIntent().getStringExtra(EXTRA_IMAGE_URL);
            if (single != null) references.add(single);
        }
        if (references.isEmpty()) { finish(); return; }
        index = state == null ? getIntent().getIntExtra(EXTRA_START_INDEX, 0)
                : state.getInt("index", 0);
        index = Math.max(0, Math.min(index, references.size() - 1));
        binding.btnImageViewerBack.setOnClickListener(v -> finish());
        binding.imageRetry.setOnClickListener(v -> loadCurrent());
        binding.ivFullImage.setPageListener(new ZoomableImageView.PageListener() {
            @Override public void onNext() { showIndex(index + 1); }
            @Override public void onPrevious() { showIndex(index - 1); }
        });
        updateIndicator();
        loadCurrent();
    }

    private void showIndex(int requested) {
        if (requested < 0 || requested >= references.size()) return;
        index = requested;
        updateIndicator();
        loadCurrent();
    }

    private void updateIndicator() {
        if (references.size() > 1) {
            binding.imagePageIndicator.setText((index + 1) + " / " + references.size());
            binding.imagePageIndicator.setVisibility(View.VISIBLE);
        } else binding.imagePageIndicator.setVisibility(View.GONE);
    }

    private void loadCurrent() {
        if (request != null) request.remove();
        String reference = references.get(index);
        binding.imageRetry.setVisibility(View.GONE);
        binding.imageLoading.setVisibility(View.VISIBLE);
        binding.ivFullImage.setVisibility(View.INVISIBLE);
        request = Api.run(() -> SupabaseStorage.info(reference), new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject asset) {
                if (!alive() || !reference.equals(references.get(index))) return;
                if (asset.optString("mime_type").startsWith("video/")) {
                    binding.imageLoading.setVisibility(View.GONE);
                    binding.ivFullImage.setVisibility(View.INVISIBLE);
                    startActivity(new android.content.Intent(ChatImageViewerActivity.this, VideoPlayerActivity.class)
                            .putExtra(VideoPlayerActivity.EXTRA_ASSET, reference));
                    // This activity is only a media-type routing step for videos.
                    // Remove it so closing playback returns to the original screen.
                    finish();
                    return;
                }
                Glide.with(ChatImageViewerActivity.this)
                        .load(reference)
                        .apply(new RequestOptions().diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true))
                        .placeholder(R.drawable.avatar_neutral)
                        .listener(new RequestListener<android.graphics.drawable.Drawable>() {
                            @Override public boolean onLoadFailed(@Nullable com.bumptech.glide.load.engine.GlideException e,
                                    Object model, Target<android.graphics.drawable.Drawable> target, boolean first) {
                                if (alive()) {
                                    binding.imageLoading.setVisibility(View.GONE);
                                    binding.ivFullImage.setVisibility(View.INVISIBLE);
                                    binding.imageRetry.setVisibility(View.VISIBLE);
                                }
                                return false;
                            }
                            @Override public boolean onResourceReady(android.graphics.drawable.Drawable resource,
                                    Object model, Target<android.graphics.drawable.Drawable> target,
                                    com.bumptech.glide.load.DataSource source, boolean first) {
                                if (alive()) {
                                    binding.imageLoading.setVisibility(View.GONE);
                                    binding.imageRetry.setVisibility(View.GONE);
                                    binding.ivFullImage.setVisibility(View.VISIBLE);
                                }
                                return false;
                            }
                        })
                        .into(binding.ivFullImage);
            }
            @Override public void error(String error) {
                if (!alive()) return;
                binding.imageLoading.setVisibility(View.GONE);
                binding.imageRetry.setVisibility(View.VISIBLE);
            }
        });
    }

    private boolean alive() { return binding != null && !isFinishing() && !isDestroyed(); }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("index", index);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        if (request != null) request.remove();
        if (binding != null) Glide.with(getApplicationContext()).clear(binding.ivFullImage);
        binding = null;
        super.onDestroy();
    }
}
