package com.example.meetomxh.repositories;

import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.RpcClient;
import org.json.JSONArray;
import org.json.JSONObject;

public final class NotificationRepository {
    public static final int MAX_PAGE_SIZE=50;
    private NotificationRepository() {}
    public static JSONArray page(boolean unreadOnly,String afterTime,String afterId,int limit) throws Exception {
        JSONArray data=(JSONArray) RpcClient.call("get_notifications",Api.obj("p_unread_only",unreadOnly,
                "p_after_time",afterTime,"p_after_id",afterId,"p_limit",limit));
        JSONArray visible=new JSONArray();
        for(int i=0;i<data.length();i++){JSONObject row=data.optJSONObject(i);if(row!=null && !com.example.meetomxh.models.NotificationItem.isRetired(row.optString("kind")))visible.put(row);}
        return visible;
    }
    public static long unreadCount() throws Exception { return ((Number)RpcClient.call("get_notification_unread_count",Api.obj())).longValue(); }
    public static void markRead(String id) throws Exception { RpcClient.call("mark_notification_read",Api.obj("p_notification_id",id)); }
    public static void markAllRead() throws Exception { RpcClient.call("mark_all_notifications_read",Api.obj()); }
    public static JSONObject settings() throws Exception { return RpcClient.object("get_notification_settings",Api.obj()); }
    public static JSONObject updateSettings(boolean enabled,JSONObject preferences) throws Exception {
        return RpcClient.object("update_notification_settings",Api.obj("p_enabled",enabled,"p_preferences",preferences));
    }
}
