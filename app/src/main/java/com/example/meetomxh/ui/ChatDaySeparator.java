package com.example.meetomxh.ui;

import com.example.meetomxh.supabase.ServerTime;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Labels a change of local calendar day between adjacent visible messages. */
public final class ChatDaySeparator {
    private ChatDaySeparator() {}

    public static String label(ServerTime previous,ServerTime current,TimeZone zone){
        if(previous==null||current==null)return "";
        Calendar before=Calendar.getInstance(zone),after=Calendar.getInstance(zone);
        before.setTime(previous.toDate());after.setTime(current.toDate());
        if(before.get(Calendar.ERA)==after.get(Calendar.ERA)
                &&before.get(Calendar.YEAR)==after.get(Calendar.YEAR)
                &&before.get(Calendar.DAY_OF_YEAR)==after.get(Calendar.DAY_OF_YEAR))return "";
        SimpleDateFormat format=new SimpleDateFormat("HH:mm d 'Tháng' M, yyyy",Locale.forLanguageTag("vi-VN"));
        format.setTimeZone(zone);return format.format(current.toDate());
    }
}
