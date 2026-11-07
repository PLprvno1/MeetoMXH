package com.example.meetomxh.music;

import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.ErrorMapper;
import com.example.meetomxh.supabase.RpcClient;
import org.json.JSONObject;

/** Fail-closed contract probe; music is never published through legacy RPCs. */
public final class MusicPublishingClient {
    private MusicPublishingClient(){}
    public static void requireEnabled()throws Exception{
        try{
            JSONObject result=RpcClient.object("get_music_publish_capabilities",Api.obj());
            if(!result.optBoolean("enabled")||result.optInt("contract_version")!=1)
                throw new IllegalStateException("Máy chủ chưa bật lưu metadata nhạc. Có thể bỏ nhạc để đăng theo luồng hiện tại.");
        }catch(ErrorMapper.HttpError error){
            if("PGRST202".equals(error.code)||"42883".equals(error.code))
                throw new IllegalStateException("Máy chủ chưa có hỗ trợ xuất bản nhạc. Chủ dự án cần triển khai migration Task 52; có thể bỏ nhạc để đăng theo luồng hiện tại.");
            throw error;
        }
    }
}
