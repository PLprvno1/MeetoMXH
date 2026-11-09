package com.example.meetomxh.ui;

import com.example.meetomxh.supabase.ServerTime;
import org.junit.Test;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class ChatDaySeparatorTest {
    private String label(String before,String after,String zone){return ChatDaySeparator.label(ServerTime.parse(before),ServerTime.parse(after),TimeZone.getTimeZone(zone));}

    @Test public void sameLocalDayDoesNotRepeatLabel(){
        assertEquals("",label("2026-11-08T01:00:00Z","2026-11-08T16:59:59Z","Asia/Bangkok"));
    }
    @Test public void localMidnightAddsNewDayLabel(){
        assertEquals("00:00 9 Tháng 11, 2026",label("2026-11-08T16:59:59Z","2026-11-08T17:00:00Z","Asia/Bangkok"));
    }
    @Test public void utcMidnightWithinSameLocalDayDoesNotSplit(){
        assertEquals("",label("2026-11-08T23:59:59Z","2026-11-09T00:00:00Z","Asia/Bangkok"));
    }
    @Test public void skippedDaysAndYearChangesShowFullDate(){
        assertEquals("16:19 2 Tháng 11, 2026",label("2026-10-30T10:00:00Z","2026-11-02T09:19:00Z","Asia/Bangkok"));
        assertEquals("00:00 1 Tháng 1, 2027",label("2026-12-31T16:59:59Z","2026-12-31T17:00:00Z","Asia/Bangkok"));
    }
    @Test public void firstMessageAndMissingDatesDoNotInventADay(){
        assertEquals("",ChatDaySeparator.label(null,ServerTime.now(),TimeZone.getDefault()));
        assertEquals("",ChatDaySeparator.label(ServerTime.now(),null,TimeZone.getDefault()));
    }
    @Test public void daylightSavingChangeWithinDayDoesNotSplit(){
        assertEquals("",label("2026-11-01T05:30:00Z","2026-11-01T06:30:00Z","America/New_York"));
    }
}
