package com.example.meetomxh.supabase;
import com.example.meetomxh.BuildConfig;
public final class SupabaseConfig {
 public static final String URL=BuildConfig.SUPABASE_URL.replaceAll("/+$","");
 public static final String KEY=BuildConfig.SUPABASE_ANON_KEY;
 private SupabaseConfig(){}
 public static void check(){
  if(URL.isEmpty()||KEY.isEmpty())throw new IllegalStateException("Thiếu cấu hình Supabase Cloud. Hãy điền url và anonKey trong supabase.debug.properties rồi build lại.");
  if(!URL.startsWith("https://"))throw new IllegalStateException("Supabase Cloud phải dùng HTTPS.");
 }
}
