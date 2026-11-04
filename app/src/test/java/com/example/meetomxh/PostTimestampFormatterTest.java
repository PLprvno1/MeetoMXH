package com.example.meetomxh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.meetomxh.ui.PostTimestampFormatter;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PostTimestampFormatterTest {
    private static String utc(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    @Test public void parsesUtcAndOffsetTimestampsAndAppliesRelativeRanges() {
        long now = parseUtc("2026-11-04T12:00:00Z");
        assertEquals("Vừa xong", PostTimestampFormatter.format(utc(now - 30_000), now));
        assertEquals("20 phút trước", PostTimestampFormatter.format(utc(now - 20 * 60_000L), now));
        assertEquals("3 giờ trước", PostTimestampFormatter.format(utc(now - 3 * 3_600_000L), now));
        assertEquals("2 ngày trước", PostTimestampFormatter.format(utc(now - 2 * 86_400_000L), now));
        assertEquals("Vừa xong", PostTimestampFormatter.format("2026-11-04T07:00:00-05:00",
                parseUtc("2026-11-04T12:00:00Z")));
    }

    @Test public void olderThanSevenDaysUsesLocalCalendarDate() {
        long now = parseUtc("2026-11-04T12:00:00Z");
        long created = parseUtc("2026-10-27T11:59:00Z");
        String actual = PostTimestampFormatter.format(utc(created), now);
        String expected = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(new Date(created));
        assertEquals(expected, actual);
    }

    @Test public void futureAndInvalidValuesNeverPretendToBeRecent() {
        long now = parseUtc("2026-11-04T12:00:00Z");
        String future = PostTimestampFormatter.format(utc(now + 60_000), now);
        assertFalse(future.equals("Vừa xong"));
        assertTrue(future.matches("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}"));
        assertEquals("Không rõ thời gian", PostTimestampFormatter.format(null, now));
        assertEquals("Không rõ thời gian", PostTimestampFormatter.format("not-a-timestamp", now));
    }

    private static long parseUtc(String value) {
        return com.example.meetomxh.supabase.ServerTime.parse(value).toDate().getTime();
    }
}
