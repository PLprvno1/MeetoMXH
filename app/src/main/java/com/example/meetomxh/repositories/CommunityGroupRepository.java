package com.example.meetomxh.repositories;

import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.ErrorMapper;
import org.json.JSONArray;
import org.json.JSONObject;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.storage.SupabaseStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CommunityGroupRepository {
    private CommunityGroupRepository() {}
    public interface Callback<T> { void success(T value); void error(String message); }
    public static void search(String query, boolean mine, String afterTime, String afterId, Callback<JSONArray> cb) {
        Api.run(() -> (JSONArray) RpcClient.call("search_community_groups", Api.obj("p_query", query == null ? "" : query,
                "p_mine", mine, "p_after_time", afterTime, "p_after_id", afterId, "p_limit", 20)), adapt(cb));
    }
    public static void searchAdvanced(String query,String afterTime,String afterId,int limit,Callback<JSONArray> cb) {
        Api.run(() -> (JSONArray)RpcClient.call("search_advanced_groups",Api.obj("p_query",query,
                "p_after_time",afterTime,"p_after_id",afterId,"p_limit",limit)),adapt(cb));
    }
    public static void create(String name, String description, String privacy, boolean listed, String rules,
                              String requestId, Callback<JSONObject> cb) {
        Api.run(() -> RpcClient.object("create_community_group", Api.obj("p_name", name, "p_description", description,
                "p_privacy", privacy, "p_listed", listed, "p_rules", rules, "p_client_request_id", requestId)), adapt(cb));
    }
    public static void get(String id, Callback<JSONObject> cb) {
        Api.run(() -> RpcClient.object("get_community_group", Api.obj("p_group_id", id)), adapt(cb));
    }
    public static void join(String id, Callback<JSONObject> cb) {
        Api.run(() -> RpcClient.object("join_community_group", Api.obj("p_group_id", id)), adapt(cb));
    }
    public static void leave(String id, Callback<JSONObject> cb) {
        Api.run(() -> RpcClient.object("leave_community_group", Api.obj("p_group_id", id)), adapt(cb));
    }
    public static void posts(String id, boolean pending, String afterTime, String afterId, Callback<List<Post>> cb) {
        Api.run(() -> {
            JSONArray rows=(JSONArray)RpcClient.call("get_community_posts",Api.obj("p_group_id",id,"p_pending",pending,"p_after_time",afterTime,"p_after_id",afterId,"p_limit",20));
            List<Post> result=new ArrayList<>(); List<String> refs=new ArrayList<>();
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);Post p=Dto.post(row);p.setGroupId(id);p.setPostState(row.optString("state"));result.add(p);refs.addAll(p.getImageUrls());}
            Map<String,String> types=SupabaseStorage.mimeTypes(refs);
            for(Post p:result)for(int i=0;i<p.getImageUrls().size();i++){String mime=types.get(p.getImageUrls().get(i));if(mime!=null)p.setMediaTypeAt(i,mime);}
            return result;
        },adapt(cb));
    }
    public static void members(String group, String afterTime, String afterId, Callback<JSONArray> cb) {
        Api.run(() -> (JSONArray)RpcClient.call("list_community_members",Api.obj("p_group_id",group,"p_after_time",afterTime,"p_after_id",afterId,"p_limit",20)),adapt(cb));
    }
    public static void requests(String group,String afterTime,String afterId,Callback<JSONArray> cb){Api.run(()->(JSONArray)RpcClient.call("list_group_requests",Api.obj("p_group_id",group,"p_after_time",afterTime,"p_after_id",afterId,"p_limit",20)),adapt(cb));}
    public static void invites(String group,boolean inbox,String afterTime,String afterId,Callback<JSONArray> cb){Api.run(()->(JSONArray)RpcClient.call("list_group_invites",Api.obj("p_group_id",group,"p_inbox",inbox,"p_after_time",afterTime,"p_after_id",afterId,"p_limit",20)),adapt(cb));}
    public static void action(String rpc,JSONObject args,Callback<JSONObject> cb){Api.run(()->{
        try { return RpcClient.object(rpc,args); }
        catch(ErrorMapper.HttpError error){
            if("delete_community_post".equals(rpc)){
                String code=error.code!=null&&error.code.matches("[A-Za-z0-9_.-]{1,80}")?error.code:"unknown";
                android.util.Log.w("SupabaseGroupPost","Delete failed: HTTP "+error.status+" code="+code);
            }
            throw error;
        }
    },adapt(cb));}
    private static <T> Api.Callback<T> adapt(Callback<T> cb) {
        return new Api.Callback<T>() { public void success(T value) { cb.success(value); } public void error(String message) { cb.error(message); } };
    }
}
