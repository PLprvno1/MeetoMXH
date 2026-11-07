package com.example.meetomxh.supabase;

import org.json.JSONArray;
import org.json.JSONObject;

/** Task 44 is additive and still requires the existing Task 36 privacy capability guard. */
public final class ChatCustomizationClient {
    public static void require() throws Exception {
        SocialContract.require();
        try{
            JSONObject c=RpcClient.object("task44_chat_capabilities",Api.obj());
            if(c.optInt("version")<44||!c.optBoolean("chat_customization")||!c.optBoolean("follow_picker"))
                throw new IllegalStateException("Tùy chỉnh trò chuyện cần bản cập nhật Task 44. Vui lòng thử lại sau khi máy chủ được cập nhật.");
        }catch(ErrorMapper.HttpError e){
            if(e.status==404||"PGRST202".equals(e.code))throw new IllegalStateException("Máy chủ chưa được cập nhật Task 44. Chủ dự án cần triển khai bản cập nhật trước khi dùng tùy chỉnh trò chuyện.");
            throw e;
        }
    }
    public static JSONObject get(String id)throws Exception{require();return RpcClient.object("get_chat_customization",Api.obj("p_conversation_id",id));}
    public static JSONArray people(String query,String name,String id,String conversation,String target)throws Exception{
        require();return (JSONArray)RpcClient.call("chat_picker_people",Api.obj("p_query",query,"p_after_name",name,"p_after_id",id,
                "p_limit",30,"p_conversation_id",conversation,"p_target",target));
    }
    public static String name(JSONObject member){
        String name=Dto.nullable(member,"conversation_name");if(name==null)name=Dto.nullable(member,"display_name");
        return name==null||name.trim().isEmpty()?"Người dùng không khả dụng":name;
    }
    private ChatCustomizationClient(){}
}
