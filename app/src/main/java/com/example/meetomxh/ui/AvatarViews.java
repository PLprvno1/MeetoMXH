package com.example.meetomxh.ui;

import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.example.meetomxh.R;

public final class AvatarViews {
    private AvatarViews() { }

    public static int placeholder(String preset) {
        if ("male".equals(preset)) return R.drawable.avatar_male;
        if ("female".equals(preset)) return R.drawable.avatar_female;
        return R.drawable.avatar_neutral;
    }

    public static void load(ImageView view, String privateAssetReference, String publicPreset) {
        int fallback = placeholder(publicPreset);
        if (privateAssetReference == null || privateAssetReference.trim().isEmpty()) {
            Glide.with(view).clear(view);
            view.setImageResource(fallback);
            return;
        }
        Glide.with(view).load(privateAssetReference)
                .placeholder(fallback).error(fallback).centerCrop().into(view);
    }
}
