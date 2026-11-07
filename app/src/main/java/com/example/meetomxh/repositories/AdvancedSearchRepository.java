package com.example.meetomxh.repositories;

import com.example.meetomxh.models.Post;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.RpcClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class AdvancedSearchRepository {
    private AdvancedSearchRepository() {}

    public interface Callback<T> { void success(T value); void error(String message); }
    public static final class PostPage {
        public final List<Post> posts;
        public final String afterTime;
        public final String afterId;
        public final float afterRank;
        public PostPage(List<Post> posts, String afterTime, String afterId, float afterRank) {
            this.posts=posts; this.afterTime=afterTime; this.afterId=afterId; this.afterRank=afterRank;
        }
    }

    public static com.example.meetomxh.supabase.Subscription people(String query,String afterName,String afterId,int limit,Callback<JSONArray> cb) {
        return Api.run(() -> (JSONArray)RpcClient.call("search_advanced_people",Api.obj(
                "p_query",query,"p_after_name",afterName,"p_after_id",afterId,"p_limit",limit)),adapt(cb));
    }

    public static com.example.meetomxh.supabase.Subscription posts(String query,boolean mediaOnly,String since,String sort,float afterRank,
                              String afterTime,String afterId,int limit,Callback<PostPage> cb) {
        return Api.run(() -> {
            JSONArray rows=query==null||query.trim().isEmpty()?(JSONArray)RpcClient.call("get_explore_posts",Api.obj("p_after_time",afterTime,"p_after_id",afterId,"p_limit",limit)):(JSONArray)RpcClient.call("search_advanced_posts",Api.obj(
                    "p_query",query,"p_media_only",mediaOnly,"p_since",since,"p_until",null,
                    "p_sort",sort,"p_after_rank",afterRank<0?null:afterRank,
                    "p_after_time",afterTime,"p_after_id",afterId,"p_limit",limit));
            List<Post> result=new ArrayList<>();
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i); Post post=Dto.post(row);
                post.setTimeAgo(com.example.meetomxh.ui.PostTimestampFormatter.format(post.serverCreatedAt));
                result.add(post);
            }
            JSONObject last=rows.length()==0?null:rows.optJSONObject(rows.length()-1);
            return new PostPage(result,last==null?null:last.optString("created_at"),
                    last==null?null:last.optString("id"),last==null?-1f:(float)last.optDouble("search_rank",0));
        },adapt(cb));
    }

    public static com.example.meetomxh.supabase.Subscription reels(String query,String afterTime,String afterId,int limit,Api.Callback<PostPage> callback){
        return Api.run(()->{
            JSONArray rows=(JSONArray)RpcClient.call("search_reels",Api.obj("p_query",query,"p_after_time",afterTime,"p_after_id",afterId,"p_limit",limit));
            List<Post> posts=new ArrayList<>();for(int i=0;i<rows.length();i++)posts.add(Dto.post(rows.getJSONObject(i)));
            JSONObject last=rows.length()==0?null:rows.getJSONObject(rows.length()-1);
            return new PostPage(posts,last==null?null:last.getString("created_at"),last==null?null:last.getString("id"),-1f);
        },callback);
    }
    public static void history(Callback<JSONArray> cb) {
        Api.run(() -> (JSONArray)RpcClient.call("get_search_history",Api.obj("p_limit",20)),adapt(cb));
    }
    public static void save(String query,Callback<JSONObject> cb) {
        Api.run(() -> RpcClient.object("save_search_query",Api.obj("p_query",query)),adapt(cb));
    }
    public static void deleteHistory(String id,Callback<JSONObject> cb) {
        Api.run(() -> RpcClient.object("delete_search_history_item",Api.obj("p_item_id",id)),adapt(cb));
    }
    public static void clearHistory(Callback<JSONObject> cb) {
        Api.run(() -> RpcClient.object("clear_search_history",Api.obj()),adapt(cb));
    }

    private static <T> Api.Callback<T> adapt(Callback<T> cb) {
        return new Api.Callback<T>() {
            @Override public void success(T value) { cb.success(value); }
            @Override public void error(String message) { cb.error(message); }
        };
    }

}
