package com.example.meetomxh.supabase;
import com.example.meetomxh.BuildConfig;
public final class SupabaseConfig {
 public static final String URL=BuildConfig.SUPABASE_URL.replaceAll("/+$","");
 public static final String KEY=BuildConfig.SUPABASE_ANON_KEY;
 private SupabaseConfig(){}
 public static void check(){if(URL.isEmpty()||KEY.isEmpty()||(!BuildConfig.DEBUG&&!URL.startsWith("https://")))throw new IllegalStateException("Chưa cấu hình kết nối an toàn");}
}
