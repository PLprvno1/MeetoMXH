package com.example.meetomxh.ui;
public final class StoryTime {
    private StoryTime(){}
    public static long millis(String value){com.example.meetomxh.supabase.ServerTime time=com.example.meetomxh.supabase.ServerTime.parse(value);return time==null?0:time.toDate().getTime();}
    public static String relative(String value){long at=millis(value);return at==0?"":android.text.format.DateUtils.getRelativeTimeSpanString(at,System.currentTimeMillis(),60000).toString();}
}
