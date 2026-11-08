package com.example.meetomxh.notifications;

import com.example.meetomxh.models.NotificationItem;

public final class NotificationPresentation {
    public final String title,body;
    private NotificationPresentation(String title,String body){this.title=title;this.body=body;}
    public static NotificationPresentation of(NotificationItem item){
        if(item.isGroupMessage())return new NotificationPresentation(item.groupTitle(),item.actorName+": "+
            (item.messagePreview.isEmpty()?"Đã gửi tin nhắn.":item.messagePreview));
        return new NotificationPresentation("message".equals(item.kind)?item.actorName:"Meeto",item.message());
    }
}
