package com.example.meetomxh.supabase;

import android.content.Context;
import android.content.SharedPreferences;
import com.example.meetomxh.MeetoApplication;
import com.example.meetomxh.models.Chat;

/** Device-local, account-scoped hide-for-self. Never modifies server membership/history. */
public final class ConversationVisibility {
    private ConversationVisibility() {}
    private static SharedPreferences preferences(String uid) {
        return MeetoApplication.get().getSharedPreferences("conversation_hidden_v1_" + uid, Context.MODE_PRIVATE);
    }
    public static boolean isHidden(String uid, Chat chat) {
        if (uid == null || uid.isEmpty() || chat == null) return false;
        SharedPreferences prefs = preferences(uid);
        return prefs.contains(chat.getChatId()) && chat.highWater <= prefs.getLong(chat.getChatId(), -1);
    }
    public static void hide(String uid, Chat chat) {
        if (uid == null || uid.isEmpty() || chat == null) return;
        SharedPreferences prefs = preferences(uid);
        long cutoff = Math.max(chat.highWater, prefs.getLong(chat.getChatId(), -1));
        prefs.edit().putLong(chat.getChatId(), cutoff).apply();
    }
}
