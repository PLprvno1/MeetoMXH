package com.example.meetomxh.ui;

import com.example.meetomxh.models.Chat;
import com.example.meetomxh.supabase.ServerTime;
import java.util.Comparator;

public final class InboxOrder {
    private InboxOrder(){}
    public static ServerTime activityTime(Chat chat){
        ServerTime message=chat.getLastMessageAt(),created=chat.getCreatedAt();
        if(message==null)return created!=null?created:chat.getUpdatedAt();
        return created!=null&&created.compareTo(message)>0?created:message;
    }
    public static final Comparator<Chat> NEWEST_FIRST=(a,b)->{
        ServerTime first=activityTime(a),second=activityTime(b);
        int order=first==null?(second==null?0:1):second==null?-1:second.compareTo(first);
        if(order!=0)return order;
        String firstId=a.getChatId(),secondId=b.getChatId();
        return firstId==null?(secondId==null?0:1):secondId==null?-1:secondId.compareTo(firstId);
    };
}
