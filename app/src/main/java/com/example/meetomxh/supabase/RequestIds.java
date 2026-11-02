package com.example.meetomxh.supabase;
import com.example.meetomxh.MeetoApplication;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
public final class RequestIds {
 private static SharedPreferences prefs(){return MeetoApplication.get().getSharedPreferences("request_ids",0);}
 private static String key(String op,String payload){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest((SessionManager.getInstance().uid()+op+payload).getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format("%02x",b));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
 public static synchronized String get(String op,String payload){String k=key(op,payload);String id=prefs().getString(k,null);if(id==null){id=UUID.randomUUID().toString();prefs().edit().putString(k,id).apply();}return id;}
 public static synchronized void complete(String op,String payload){prefs().edit().remove(key(op,payload)).apply();}
}
