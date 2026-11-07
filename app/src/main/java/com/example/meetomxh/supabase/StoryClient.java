package com.example.meetomxh.supabase;

import com.example.meetomxh.models.Story;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Authenticated RPC access for the Cloud Stories contract. */
public final class StoryClient {
    private StoryClient() { }
    public static boolean validId(String value){return value!=null&&value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    private static String requireId(String value,String label){if(!validId(value))throw new IllegalStateException(label+" không hợp lệ. Hãy đóng và mở lại Story.");return value;}
    private static Object rpc(String name,JSONObject input)throws Exception{
        try{return RpcClient.call(name,input);}catch(ErrorMapper.HttpError error){
            ErrorMapper.diagnostic("story."+name,error);
            if("25006".equals(error.code)){
                android.util.Log.w("StoryViewer","stage=rpc."+name+" reason=readonly_actor_lock");
                throw new IllegalStateException("Máy chủ chưa cập nhật luồng đọc Story. Cần triển khai bản sửa transaction Story rồi thử lại.");
            }
            if("PGRST202".equals(error.code)||"42883".equals(error.code))throw new IllegalStateException("Máy chủ chưa hỗ trợ xem Story. Cần cập nhật máy chủ trước khi xem.");
            if("22P02".equals(error.code))throw new IllegalStateException("Máy chủ từ chối mã Story/người đăng không hợp lệ. Hãy đóng và mở lại Story.");
            if(error.status==404)throw new IllegalStateException("Tin đã hết hạn, bị xóa hoặc bạn không còn quyền xem.");
            throw error;
        }
    }
    public static List<Story> list(String authorId) throws Exception {
        Object data=authorId==null?rpc("get_stories", Api.obj("p_author",null,"p_limit",100)):
                rpc("get_story_author_stories",Api.obj("p_author",requireId(authorId,"Người đăng")));
        if(!(data instanceof JSONArray))throw new IllegalStateException("Máy chủ trả danh sách Story không đúng định dạng.");
        JSONArray rows=(JSONArray)data;
        List<Story> result=new ArrayList<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject o=rows.optJSONObject(i);if(o==null)continue;Story s=parse(o);
            if(s.isActive())result.add(s);
        }
        return result;
    }
    public static JSONObject create(String asset,String caption,String audience,String requestId)throws Exception {
        return RpcClient.object("create_story",Api.obj("p_media_asset_id",Dto.assetId(asset),"p_caption",caption,
                "p_audience",audience,"p_client_request_id",requestId));
    }
    public static void viewed(String id)throws Exception { RpcClient.call("mark_story_viewed",Api.obj("p_story_id",id)); }
    public static void delete(String id)throws Exception { RpcClient.call("delete_story",Api.obj("p_story_id",id)); }
    public static JSONObject privacy(String id)throws Exception{
        try{
            Object result=RpcClient.call("get_story_privacy",Api.obj("p_story_id",requireId(id,"Mã Story")));
            if(!(result instanceof JSONObject))throw new IllegalStateException("Máy chủ chưa trả cấu hình quyền Story hợp lệ.");
            JSONObject data=(JSONObject)result;
            if(!id.equalsIgnoreCase(data.optString("story_id"))||data.optJSONArray("allowed_audiences")==null)
                throw new IllegalStateException("Cấu hình quyền không khớp Story. Hãy mở lại bảng chọn.");
            return data;
        }catch(ErrorMapper.HttpError error){throw privacyError(error);}
    }
    public static Story setAudience(String id,String audience,String expected)throws Exception{
        if(!"public".equals(audience)&&!"followers".equals(audience)&&!"private".equals(audience))
            throw new IllegalStateException("Quyền Story không được hỗ trợ.");
        try{
            Object result=RpcClient.call("set_story_audience",Api.obj("p_story_id",requireId(id,"Mã Story"),
                    "p_audience",audience,"p_expected_audience",expected));
            if(!(result instanceof JSONObject))throw new IllegalStateException("Chưa nhận được xác nhận đổi quyền Story. Hãy tải lại quyền hiện tại.");
            Story story=parse((JSONObject)result);
            if(!id.equalsIgnoreCase(story.getId())||!story.isOwn()||!audience.equals(story.getAudience()))
                throw new IllegalStateException("Máy chủ chưa xác nhận quyền mới của đúng Story. Hãy tải lại quyền hiện tại.");
            return story;
        }catch(ErrorMapper.HttpError error){throw privacyError(error);}
    }
    private static Exception privacyError(ErrorMapper.HttpError error){
        ErrorMapper.diagnostic("story.privacy",error);
        if("PGRST202".equals(error.code)||"42883".equals(error.code))
            return new IllegalStateException("Máy chủ chưa hỗ trợ chỉnh sửa quyền Story. Chủ dự án cần triển khai bản cập nhật 20261107000600.");
        if(error.status==409)return new IllegalStateException("Quyền Story đã thay đổi ở nơi khác. Bấm Tải lại quyền hiện tại rồi chọn lại.");
        if(error.status==404)return new IllegalStateException("Tin đã hết hạn, bị xóa hoặc bạn không phải chủ Story.");
        if(error.serverMessage.contains("private_account_public_audience"))
            return new IllegalStateException("Tài khoản riêng tư không thể chuyển Story sang Công khai. Hãy tải lại quyền hiện tại.");
        return error;
    }
    public static JSONObject reply(String storyId,String body,String requestId)throws Exception {
        return RpcClient.object("reply_to_story",Api.obj("p_story_id",storyId,"p_body",body,"p_client_request_id",requestId));
    }
    public static Story get(String id)throws Exception{
        Object result=rpc("get_story",Api.obj("p_story_id",requireId(id,"Mã Story")));
        if(!(result instanceof JSONObject))throw new IllegalStateException("Máy chủ chưa trả dữ liệu Story hợp lệ.");
        Story story=parse((JSONObject)result);
        if(!id.equalsIgnoreCase(story.getId()))throw new IllegalStateException("Máy chủ trả nhầm mã Story. Hãy mở lại tin.");
        return story;
    }
    private static Story parse(JSONObject o){
        Story s=new Story();s.setId(requireId(Dto.nullable(o,"id"),"Mã Story"));s.setAuthorId(requireId(Dto.nullable(o,"author_id"),"Người đăng"));s.setUserName(o.optString("author_name","Meeto"));
        s.setAvatarUrl(Dto.asset(Dto.nullable(o,"avatar_asset_id")));s.setAvatarPreset(o.optString("avatar_preset","neutral"));
        s.setImageUrl(Dto.asset(requireId(Dto.nullable(o,"media_asset_id"),"Media Story")));s.setMediaType(o.optString("mime_type"));s.setCaption(o.optString("caption",""));
        s.setAudience(o.optString("audience","private"));s.setViewed(o.optBoolean("viewed"));
        s.setCreatedAt(o.optString("created_at"));s.setExpiresAt(o.optString("expires_at"));s.setOwn(o.optBoolean("own"));s.setLiked(o.optBoolean("liked"));s.setViewsCount(o.optInt("views_count"));return s;
    }
    public static JSONObject like(String id,boolean liked)throws Exception{return (JSONObject)rpc("set_story_like",Api.obj("p_story_id",requireId(id,"Mã Story"),"p_liked",liked));}
    public static JSONObject viewersPage(String id,String time,String cursor)throws Exception{
        if(cursor!=null)requireId(cursor,"Mốc người xem");
        return (JSONObject)rpc("get_story_viewers_page",Api.obj("p_story_id",requireId(id,"Mã Story"),"p_after_time",time,"p_after_id",cursor,"p_limit",30));
    }
    public static final class Media{
        public final String url,mime;public final java.util.Map<String,String> headers;
        Media(String url,String mime,java.util.Map<String,String> headers){this.url=url;this.mime=mime;this.headers=headers;}
    }
    public static Media media(Story story)throws Exception{
        requireId(Dto.assetId(story.getImageUrl()),"Media Story");SocialContract.require();
        try{
            JSONObject info=com.example.meetomxh.storage.SupabaseStorage.info(story.getImageUrl());
            String mime=info.optString("mime_type");
            if(!(mime.startsWith("image/")||"video/mp4".equals(mime)))throw new IllegalStateException("Định dạng media Story không được hỗ trợ.");
            if(mime.startsWith("video/")!=story.getMediaType().startsWith("video/"))throw new IllegalStateException("Định dạng media không khớp dữ liệu Story. Hãy tải lại tin.");
            java.util.Map<String,String> headers=new java.util.HashMap<>();headers.put("apikey",SupabaseConfig.KEY);headers.put("Authorization","Bearer "+SessionManager.getInstance().access(false));
            return new Media(com.example.meetomxh.storage.SupabaseStorage.url(info),mime,headers);
        }catch(ErrorMapper.HttpError error){ErrorMapper.diagnostic("story.media",error);throw new IllegalStateException(ErrorMapper.message(error));}
    }
    public static List<JSONObject> viewers(String id)throws Exception {
        JSONArray rows=(JSONArray)RpcClient.call("get_story_viewers",Api.obj("p_story_id",id,"p_after_time",null,"p_after_id",null,"p_limit",100));
        List<JSONObject> out=new ArrayList<>(); for(int i=0;i<rows.length();i++)out.add(rows.getJSONObject(i)); return out;
    }
}
