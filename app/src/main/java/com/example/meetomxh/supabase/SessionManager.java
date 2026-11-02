package com.example.meetomxh.supabase;
import android.content.Context;
import org.json.*;
import java.util.concurrent.CopyOnWriteArrayList;
public final class SessionManager {
 private static SessionManager instance;
 private final EncryptedVault vault;private JSONObject data;private volatile long generation;
 public final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
 private SessionManager(Context context){vault=new EncryptedVault(context);data=vault.read();}
 public static synchronized void init(Context context){if(instance==null)instance=new SessionManager(context.getApplicationContext());}
 public static SessionManager getInstance(){if(instance==null)throw new IllegalStateException("App chưa khởi tạo");return instance;}
 public long generation(){return generation;}
 public synchronized SessionUser getCurrentUser(){String id=data.optString("uid");return id.isEmpty()?null:new SessionUser(id,data.optString("email"));}
 public synchronized String uid(){SessionUser u=getCurrentUser();return u==null?"":u.getUid();}
 public synchronized void accept(JSONObject session) throws Exception {
  Api.checkCancelled();
  JSONObject u=session.getJSONObject("user");data.put("access",session.getString("access_token")).put("refresh",session.getString("refresh_token")).put("uid",u.getString("id")).put("email",u.optString("email")).put("exp",session.optLong("expires_at",System.currentTimeMillis()/1000+session.optLong("expires_in",3600)));vault.write(data);
 }
 public synchronized String access(boolean force) throws Exception {
  if(getCurrentUser()==null)throw new ErrorMapper.HttpError(401,"no_session");
  if(force||System.currentTimeMillis()/1000+45>=data.optLong("exp")){
   try{JSONObject fresh=(JSONObject)SupabaseHttpClient.raw("POST","/auth/v1/token?grant_type=refresh_token",Api.obj("refresh_token",data.getString("refresh")),null);accept(fresh);for(Runnable r:listeners)Api.MAIN.post(r);}
   catch(ErrorMapper.HttpError e){if(e.status==400||e.status==401){clear();}throw e;}
  }
  return data.getString("access");
 }
 public synchronized String afterRejected(String rejected)throws Exception {return data.optString("access").equals(rejected)?access(true):access(false);}
 public synchronized void clear(){data=new JSONObject();generation++;try{vault.write(data);}catch(Exception ignored){}SupabaseHttpClient.CLIENT.dispatcher().cancelAll();Api.MAIN.post(()->{RealtimeClient.get().stop();com.bumptech.glide.Glide.get(com.example.meetomxh.MeetoApplication.get()).clearMemory();});Api.IO.execute(()->com.bumptech.glide.Glide.get(com.example.meetomxh.MeetoApplication.get()).clearDiskCache());for(Runnable r:listeners)Api.MAIN.post(r);}
 public void signOut(){String token;synchronized(this){token=data.optString("access",null);}clear();final String old=token;if(old!=null)Api.IO.execute(()->{try{SupabaseHttpClient.raw("POST","/auth/v1/logout",Api.obj(),old);}catch(Exception ignored){}});}
 public synchronized void pending(String key,String value) throws Exception {data.put(key,value);vault.write(data);}
 public synchronized String pending(String key){return data.optString(key);}
}
