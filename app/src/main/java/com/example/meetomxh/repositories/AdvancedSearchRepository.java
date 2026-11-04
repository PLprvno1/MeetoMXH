package com.example.meetomxh.repositories;

import com.example.meetomxh.models.Post;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.storage.SupabaseStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Map;

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

    public static void people(String query,String afterName,String afterId,int limit,Callback<JSONArray> cb) {
        Api.run(() -> (JSONArray)RpcClient.call("search_advanced_people",Api.obj(
                "p_query",query,"p_after_name",afterName,"p_after_id",afterId,"p_limit",limit)),adapt(cb));
    }

    public static void posts(String query,boolean mediaOnly,String since,String sort,float afterRank,
                              String afterTime,String afterId,int limit,Callback<PostPage> cb) {
        Api.run(() -> {
            JSONArray rows=(JSONArray)RpcClient.call("search_advanced_posts",Api.obj(
                    "p_query",query,"p_media_only",mediaOnly,"p_since",since,"p_until",null,
                    "p_sort",sort,"p_after_rank",afterRank<0?null:afterRank,
                    "p_after_time",afterTime,"p_after_id",afterId,"p_limit",limit));
            List<Post> result=new ArrayList<>(); List<String> refs=new ArrayList<>();
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i); Post post=Dto.post(row);
                post.setTimeAgo(com.example.meetomxh.ui.PostTimestampFormatter.format(post.serverCreatedAt));
                result.add(post); refs.addAll(post.getImageUrls());
            }
            Map<String,String> types=SupabaseStorage.mimeTypes(refs);
            for(Post post:result)for(int i=0;i<post.getImageUrls().size();i++){
                String mime=types.get(post.getImageUrls().get(i)); if(mime!=null)post.setMediaTypeAt(i,mime);
            }
            JSONObject last=rows.length()==0?null:rows.optJSONObject(rows.length()-1);
            return new PostPage(result,last==null?null:last.optString("created_at"),
                    last==null?null:last.optString("id"),last==null?-1f:(float)last.optDouble("search_rank",0));
        },adapt(cb));
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
