package com.example.meetomxh.ui;

import com.example.meetomxh.supabase.ServerTime;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Formats server UTC/offset post timestamps in the device's local timezone. */
public final class PostTimestampFormatter {
    private PostTimestampFormatter() { }

    public static String format(String serverTimestamp) {
        return format(serverTimestamp, System.currentTimeMillis());
    }

    /** Public for deterministic unit tests. */
    public static String format(String serverTimestamp, long nowMillis) {
        ServerTime parsed = ServerTime.parse(serverTimestamp);
        if (parsed == null) return "Không rõ thời gian";
        long createdMillis = parsed.toDate().getTime();
        long elapsed = nowMillis - createdMillis;
        if (elapsed < 0) return new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                .format(new Date(createdMillis));
        long minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed);
        if (minutes < 1) return "Vừa xong";
        if (minutes < 60) return minutes + " phút trước";
        long hours = TimeUnit.MILLISECONDS.toHours(elapsed);
        if (hours < 24) return hours + " giờ trước";
        long days = TimeUnit.MILLISECONDS.toDays(elapsed);
        if (days < 7) return days + " ngày trước";
        return new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(new Date(createdMillis));
    }
}
