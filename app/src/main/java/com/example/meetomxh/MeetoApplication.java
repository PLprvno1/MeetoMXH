package com.example.meetomxh;
import android.app.Application;
import com.example.meetomxh.supabase.SessionManager;
public final class MeetoApplication extends Application {
 private static MeetoApplication instance;
 public static MeetoApplication get(){return instance;}
 private java.lang.ref.WeakReference<android.app.Activity> visible=new java.lang.ref.WeakReference<>(null);
 private void checkSession(){android.app.Activity a=visible.get();if(a!=null&&!(a instanceof LoginActivity)&&!(a instanceof RegisterActivity)&&!(a instanceof ForgotPasswordActivity)&&!(a instanceof AuthCallbackActivity)&&!(a instanceof EmailConfirmationActivity)&&SessionManager.getInstance().getCurrentUser()==null){a.startActivity(new android.content.Intent(a,LoginActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));a.finish();}}
 @Override public void onCreate(){super.onCreate();instance=this;androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(com.example.meetomxh.supabase.MeetoPrefs.theme(this));SessionManager.init(this);com.bumptech.glide.Glide.get(this).getRegistry().prepend(String.class,java.io.InputStream.class,new com.example.meetomxh.storage.PrivateAssetLoader.Factory());SessionManager.getInstance().listeners.add(this::checkSession);registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){public void onActivityResumed(android.app.Activity a){visible=new java.lang.ref.WeakReference<>(a);checkSession();}public void onActivityCreated(android.app.Activity a,android.os.Bundle b){}public void onActivityStarted(android.app.Activity a){}public void onActivityPaused(android.app.Activity a){if(visible.get()==a)visible.clear();}public void onActivityStopped(android.app.Activity a){}public void onActivitySaveInstanceState(android.app.Activity a,android.os.Bundle b){}public void onActivityDestroyed(android.app.Activity a){}});}
}
