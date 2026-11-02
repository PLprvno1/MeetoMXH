package com.example.meetomxh.supabase;
import org.json.JSONObject;
import com.example.meetomxh.models.User;
public final class ProfileClient {
 public static JSONObject self() throws Exception {return RpcClient.object("get_my_profile",Api.obj());}
 public static void get(String id,Api.Callback<User> cb){Api.run(()->Dto.user(RpcClient.object(id.equals(SessionManager.getInstance().uid())?"get_my_profile":"get_profile",id.equals(SessionManager.getInstance().uid())?Api.obj():Api.obj("p_target",id))),cb);}
}
