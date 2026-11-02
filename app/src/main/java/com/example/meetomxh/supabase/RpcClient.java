package com.example.meetomxh.supabase;
import org.json.JSONObject;
public final class RpcClient {
 public static Object call(String name,JSONObject payload) throws Exception{return SupabaseHttpClient.authorized("POST","/rest/v1/rpc/"+name,payload);}
 public static JSONObject object(String name,JSONObject payload) throws Exception{return (JSONObject)call(name,payload);}
}
