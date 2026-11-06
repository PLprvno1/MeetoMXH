package com.example.meetomxh.ui;

import android.widget.ImageView;
import android.graphics.drawable.ColorDrawable;

import com.bumptech.glide.Glide;
import com.example.meetomxh.R;

public final class AvatarViews {
    private AvatarViews() { }

    public static int placeholder(String preset) {
        if ("male".equals(preset)) return R.drawable.avatar_male;
        if ("female".equals(preset)) return R.drawable.avatar_female;
        return R.drawable.ic_person;
    }

    public static void load(ImageView view, String privateAssetReference, String publicPreset) {
        load(view, privateAssetReference, publicPreset, true);
    }

    /** Loads an uploaded avatar while leaving a missing-avatar slot empty instead of showing a person glyph. */
    public static void loadWithoutPersonFallback(ImageView view, String privateAssetReference) {
        load(view, privateAssetReference, "neutral", false);
    }

    private static void load(ImageView view, String privateAssetReference, String publicPreset, boolean showFallback) {
        int fallback = showFallback ? placeholder(publicPreset) : 0;
        if (privateAssetReference == null || privateAssetReference.trim().isEmpty()) {
            Glide.with(view).clear(view);
            if (showFallback) view.setImageResource(fallback);
            else view.setImageDrawable(null);
            return;
        }
        if (showFallback) {
            Glide.with(view).load(privateAssetReference).placeholder(fallback).error(fallback).centerCrop().into(view);
        } else {
            ColorDrawable empty = new ColorDrawable(android.graphics.Color.TRANSPARENT);
            Glide.with(view).load(privateAssetReference).placeholder(empty).error(empty).centerCrop().into(view);
        }
    }
}
