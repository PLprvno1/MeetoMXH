package com.example.meetomxh.supabase;
import org.json.JSONObject;
/** Fail closed against the old Cloud authorization model while Task 36 SQL is undeployed. */
public final class SocialContract {
 private static long epoch=-1,checkedAt;
 private SocialContract(){}
 public static synchronized void require() throws Exception {
  long now=android.os.SystemClock.elapsedRealtime(),current=SessionManager.getInstance().generation();
  if(epoch==current&&now-checkedAt<30000)return;
  try {
   JSONObject c=(JSONObject)SupabaseHttpClient.authorized("POST","/rest/v1/rpc/task36_capabilities",Api.obj());
   if(c.optInt("version")<36||!c.optBoolean("follow_only")||!c.optBoolean("consent_messaging"))throw new IllegalStateException("Máy chủ đang được nâng cấp quyền riêng tư. Vui lòng thử lại sau.");
   epoch=current;checkedAt=now;
  } catch(ErrorMapper.HttpError e) {
   if(e.status==404||"PGRST202".equals(e.code))throw new IllegalStateException("Máy chủ đang được nâng cấp quyền riêng tư. Vui lòng thử lại sau.");
   throw e;
  }
 }
 public static boolean required(String rpc) {
  // Auth/account restoration and the independent Admin surface remain available.
  // Every other RPC, including future social routes, requires the new server contract.
  if("bootstrap_profile".equals(rpc)||"get_my_profile".equals(rpc)||"complete_onboarding".equals(rpc)
      ||"complete_onboarding_with_location".equals(rpc)||"update_profile_private".equals(rpc)
      ||"set_avatar_preset".equals(rpc)||"request_account_deletion".equals(rpc))return false;
  return rpc==null||!rpc.startsWith("admin_");
 }

 public static void requireProfileFieldVisibility() throws Exception {
  require();
  JSONObject c=(JSONObject)SupabaseHttpClient.authorized("POST","/rest/v1/rpc/task36_capabilities",Api.obj());
  if(c.optInt("version")<37||!c.optBoolean("profile_field_visibility"))
   throw new IllegalStateException("Cài đặt quyền riêng tư hồ sơ cần máy chủ được cập nhật. Vui lòng thử lại sau.");
 }
}
