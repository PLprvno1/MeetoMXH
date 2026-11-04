package com.example.meetomxh.supabase;

import com.example.meetomxh.models.Story;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Authenticated RPC access for the Cloud Stories contract. */
public final class StoryClient {
    private StoryClient() { }
    public static List<Story> list(String authorId) throws Exception {
        JSONArray rows=(JSONArray)RpcClient.call("get_stories", Api.obj("p_author",authorId,"p_limit",100));
        List<Story> result=new ArrayList<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject o=rows.getJSONObject(i); Story s=new Story();
            s.setId(o.optString("id")); s.setAuthorId(o.optString("author_id"));
            s.setUserName(o.optString("author_name","Meeto")); s.setAvatarUrl(Dto.asset(Dto.nullable(o,"avatar_asset_id")));
            s.setAvatarPreset(o.optString("avatar_preset","neutral")); s.setImageUrl(Dto.asset(Dto.nullable(o,"media_asset_id")));
            s.setMediaType(o.optString("mime_type","image/*")); s.setCaption(o.optString("caption",""));
            s.setAudience(o.optString("audience","friends")); s.setCreatedAt(o.optString("created_at"));
            s.setExpiresAt(o.optString("expires_at")); s.setViewed(o.optBoolean("viewed")); s.setOwn(o.optBoolean("own"));
            result.add(s);
        }
        return result;
    }
    public static JSONObject create(String asset,String caption,String audience,String requestId)throws Exception {
        return RpcClient.object("create_story",Api.obj("p_media_asset_id",Dto.assetId(asset),"p_caption",caption,
                "p_audience",audience,"p_client_request_id",requestId));
    }
    public static void viewed(String id)throws Exception { RpcClient.call("mark_story_viewed",Api.obj("p_story_id",id)); }
    public static void delete(String id)throws Exception { RpcClient.call("delete_story",Api.obj("p_story_id",id)); }
    public static JSONObject reply(String storyId,String body,String requestId)throws Exception {
        return RpcClient.object("reply_to_story",Api.obj("p_story_id",storyId,"p_body",body,"p_client_request_id",requestId));
    }
    public static List<JSONObject> viewers(String id)throws Exception {
        JSONArray rows=(JSONArray)RpcClient.call("get_story_viewers",Api.obj("p_story_id",id,"p_after_time",null,"p_after_id",null,"p_limit",100));
        List<JSONObject> out=new ArrayList<>(); for(int i=0;i<rows.length();i++)out.add(rows.getJSONObject(i)); return out;
    }
}
