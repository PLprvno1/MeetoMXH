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
   String text=r.body()==null?"":r.body().string();if(!r.isSuccessful()){
    ErrorMapper.HttpError error=ErrorMapper.httpError(r.code(),text);
    String stage=path.matches("/rest/v1/rpc/[a-z_]+")?"rpc."+path.substring(path.lastIndexOf('/')+1):"authorized_http";
    ErrorMapper.diagnostic(stage,error);throw error;
   }
   Api.checkCancelled();return text.isEmpty()?new JSONObject():new JSONTokener(text).nextValue();
  }
 }
 public static Object authorized(String method,String path,JSONObject data) throws Exception {
  SessionManager s=SessionManager.getInstance();long epoch=s.generation();String token=s.access(false);
  if(epoch!=s.generation())throw new IOException("Session changed");
  try{Object result=raw(method,path,data,token);if(epoch!=s.generation())throw new IOException("Session changed");return result;}
  catch(ErrorMapper.HttpError e){if(e.status!=401)throw e;if(epoch!=s.generation())throw e;Object result=raw(method,path,data,s.afterRejected(token));if(epoch!=s.generation())throw new IOException("Session changed");return result;}
 }
}
