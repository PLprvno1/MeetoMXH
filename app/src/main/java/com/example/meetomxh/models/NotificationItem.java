package com.example.meetomxh.models;

import org.json.JSONObject;

public final class NotificationItem {
    public final String id, kind, targetType, targetId, targetContextId, createdAt;
    public final String actorId, actorName, actorAvatarId, actorAvatarPreset;
    public final String conversationKind, conversationTitle, messagePreview;
    public final boolean unread, targetAvailable;

    public NotificationItem(JSONObject row) {
        id=row.optString("id"); kind=row.optString("kind"); targetType=row.optString("target_type");
        targetId=row.isNull("target_id")?null:row.optString("target_id");
        targetContextId=row.isNull("target_context_id")?null:row.optString("target_context_id");
        createdAt=row.optString("created_at"); actorId=row.isNull("actor_id")?null:row.optString("actor_id");
        actorName=row.optString("actor_name","Người dùng Meeto");
        actorAvatarId=row.isNull("actor_avatar_asset_id")?null:row.optString("actor_avatar_asset_id");
        actorAvatarPreset=row.optString("actor_avatar_preset","neutral");
        unread=row.isNull("read_at"); targetAvailable=row.optBoolean("target_available",false);
        conversationKind=row.optString("conversation_kind","");
        conversationTitle=row.isNull("conversation_title")?"":row.optString("conversation_title","").trim();
        messagePreview=row.isNull("message_preview")?"":row.optString("message_preview","").trim();
    }
    public static boolean isRetired(String kind) {
        return "friend_request".equals(kind)||"friend_accepted".equals(kind)||"group".equals(kind)||"memories".equals(kind)||"marketplace".equals(kind);
    }
    public String message() {
        if(isGroupMessage())return groupTitle()+"\n"+actorName+": "+(messagePreview.isEmpty()?"Tin nhắn không còn khả dụng.":messagePreview);
        if("story".equals(kind)&&"story".equals(targetType))return actorName+" đã thích tin của bạn.";
        return messageFor(kind, actorName);
    }
    public boolean isGroupMessage(){return "message".equals(kind)&&"conversation".equals(targetType)&&"group".equals(conversationKind);}
    public String groupTitle(){return conversationTitle.isEmpty()?"Nhóm chat":conversationTitle;}
    static String messageFor(String kind, String actorName) {
        switch(kind == null ? "" : kind) {
            case "reaction": return actorName+" đã bày tỏ cảm xúc về bài viết của bạn.";
            case "comment": return actorName+" đã bình luận về bài viết của bạn.";
            case "reply": return actorName+" đã trả lời bình luận của bạn.";
            case "follow_request": return actorName+" muốn theo dõi bạn.";
            case "follow_accepted": return actorName+" đã chấp nhận yêu cầu theo dõi.";
            case "message_request": return actorName+" đã gửi tin nhắn chờ.";
            case "group_invite": return actorName+" đã mời bạn vào nhóm chat.";
            case "follow": return actorName+" đã bắt đầu theo dõi bạn.";
            case "message": return actorName+" đã gửi tin nhắn cho bạn.";
            case "share": return actorName+" đã chia sẻ bài viết của bạn.";
            case "moderation": return "Bạn có cập nhật kiểm duyệt mới.";
            case "story": return actorName+" đã trả lời tin của bạn.";
            case "mention": return actorName+" đã nhắc đến bạn.";
            case "tag": return actorName+" đã gắn thẻ bạn trong bài viết.";
            default: return "Bạn có một thông báo mới.";
        }
    }
}
