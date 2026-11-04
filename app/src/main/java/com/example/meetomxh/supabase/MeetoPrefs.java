package com.example.meetomxh.supabase;

import android.content.Context;
import androidx.appcompat.app.AppCompatDelegate;

public final class MeetoPrefs {
    private static final String FILE = "meeto_preferences";
    private static final String THEME = "theme_mode";
    private MeetoPrefs() { }
    public static int theme(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getInt(THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
    public static void setTheme(Context context, int mode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(THEME, mode).apply();
    }
}
