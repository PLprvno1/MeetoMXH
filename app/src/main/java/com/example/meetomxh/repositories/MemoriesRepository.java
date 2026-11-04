package com.example.meetomxh.repositories;

import com.example.meetomxh.models.Post;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.RpcClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class MemoriesRepository {
    public static final class Page {
        public final List<Post> posts;
        public final String afterTime, afterId;
        public final boolean hasMore;
        public Page(List<Post> posts, String afterTime, String afterId, boolean hasMore) {
            this.posts=posts; this.afterTime=afterTime; this.afterId=afterId; this.hasMore=hasMore;
        }
    }

    private MemoriesRepository() { }

    public static void settings(Api.Callback<JSONObject> callback) {
        Api.run(() -> RpcClient.object("get_memories_settings", Api.obj()), callback);
    }

    public static void updateSettings(boolean enabled, boolean reminders, String timezone, Api.Callback<JSONObject> callback) {
        Api.run(() -> RpcClient.object("update_memories_settings", Api.obj(
                "p_enabled",enabled,"p_reminders_enabled",reminders,"p_timezone",timezone)), callback);
    }

    public static void today(String time, String id, Api.Callback<Page> callback) {
        load("get_today_memories", Api.obj("p_after_time",time,"p_after_id",id,"p_limit",20), callback);
    }

    public static void byYear(int yearsAgo, String time, String id, Api.Callback<Page> callback) {
        load("get_memories_by_year", Api.obj("p_years_ago",yearsAgo,"p_after_time",time,"p_after_id",id,"p_limit",20), callback);
    }

    public static void hidden(String time, String id, Api.Callback<Page> callback) {
        load("get_hidden_memories", Api.obj("p_after_time",time,"p_after_id",id,"p_limit",20), callback);
    }

    private static void load(String rpc, JSONObject args, Api.Callback<Page> callback) {
        Api.run(() -> {
            JSONArray rows=(JSONArray)RpcClient.call(rpc,args);
            List<Post> posts=new ArrayList<>();
            String nextTime=null,nextId=null;
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);
                Post post=Dto.post(row);
                post.canShareMemory=row.optBoolean("can_share",false);
                int years=row.optInt("years_ago",0);
                String localDate=row.optString("local_date","");
                if(years>0) post.setTimeAgo(years+" năm trước · "+localDate);
                posts.add(post);
                if(i==rows.length()-1){nextTime=row.optString("created_at",null);nextId=row.optString("id",null);}
            }
            return new Page(posts,nextTime,nextId,rows.length()==20);
        },callback);
    }
}
