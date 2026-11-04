package com.example.meetomxh.supabase;
import okhttp3.*;
import org.json.*;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
public final class SupabaseHttpClient {
 public static final OkHttpClient CLIENT=new OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).writeTimeout(60,TimeUnit.SECONDS).build();
 public static Object raw(String method,String path,JSONObject data,String token) throws Exception {
  SupabaseConfig.check();Request.Builder b=new Request.Builder().url(SupabaseConfig.URL+path).header("apikey",SupabaseConfig.KEY);
  if(token!=null)b.header("Authorization","Bearer "+token);
  b.method(method,(method.equals("GET")||method.equals("HEAD"))?null:RequestBody.create(data==null?"{}":data.toString(),MediaType.get("application/json")));
  Call call=CLIENT.newCall(b.build());Api.attach(call);try(Response r=call.execute()){
   String text=r.body()==null?"":r.body().string();if(!r.isSuccessful()){String code="",serverMessage="";try{JSONObject error=new JSONObject(text);code=error.optString("error_code",error.optString("code"));serverMessage=error.optString("message","");if("PT409".equals(code)&&"verification_pending".equals(serverMessage))code="verification_pending";}catch(Exception ignored){}throw new ErrorMapper.HttpError(r.code(),code,serverMessage);}
   Api.checkCancelled();return text.isEmpty()?new JSONObject():new JSONTokener(text).nextValue();
  }
 }
 public static Object authorized(String method,String path,JSONObject data) throws Exception {
  SessionManager s=SessionManager.getInstance();long epoch=s.generation();String token=s.access(false);
  try{Object result=raw(method,path,data,token);if(epoch!=s.generation())throw new IOException("Session changed");return result;}
  catch(ErrorMapper.HttpError e){if(e.status!=401)throw e;if(epoch!=s.generation())throw e;Object result=raw(method,path,data,s.afterRejected(token));if(epoch!=s.generation())throw new IOException("Session changed");return result;}
 }
}
