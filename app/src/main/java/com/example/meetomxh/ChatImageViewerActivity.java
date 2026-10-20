package com.example.meetomxh;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.example.meetomxh.databinding.ActivityChatImageViewerBinding;

public class ChatImageViewerActivity extends AppCompatActivity {
    public static final String EXTRA_IMAGE_URL = "image_url";
    private ActivityChatImageViewerBinding binding;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatImageViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.btnImageViewerBack.setOnClickListener(v -> finish());
        String url = getIntent().getStringExtra(EXTRA_IMAGE_URL);
        if (url == null || !url.startsWith("https://")) {
            finish();
            return;
        }
        Glide.with(this).load(url).fitCenter().into(binding.ivFullImage);
    }

    @Override protected void onDestroy() {
        if (binding != null) Glide.with(this).clear(binding.ivFullImage);
        binding = null;
        super.onDestroy();
    }
}
