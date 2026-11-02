package com.example.meetomxh.supabase;
import android.os.Handler;
import android.os.Looper;
import org.json.*;
import java.util.concurrent.*;
public final class Api {
 public interface Callback<T>{void success(T value);void error(String message);}
 public static final ExecutorService IO=Executors.newCachedThreadPool();
 public static final Handler MAIN=new Handler(Looper.getMainLooper());
 private static final ThreadLocal<Job> CURRENT=new ThreadLocal<>();
 public static final class Job implements Subscription {private final long epoch=SessionManager.getInstance().generation();private volatile boolean cancelled;private java.util.concurrent.Future<?> future;private final java.util.concurrent.CopyOnWriteArrayList<okhttp3.Call> calls=new java.util.concurrent.CopyOnWriteArrayList<>();public void remove(){cancelled=true;for(okhttp3.Call c:calls)c.cancel();if(future!=null)future.cancel(true);}}
 public static void attach(okhttp3.Call call)throws java.io.IOException{Job job=CURRENT.get();if(job!=null){job.calls.add(call);if(job.cancelled){call.cancel();throw new java.io.IOException("Cancelled");}}}
 public static void checkCancelled()throws java.io.IOException{Job job=CURRENT.get();if(Thread.currentThread().isInterrupted()||(job!=null&&(job.cancelled||job.epoch!=SessionManager.getInstance().generation())))throw new java.io.IOException("Cancelled");}
 public static <T> Subscription run(Callable<T> task,Callback<T> cb){long generation=SessionManager.getInstance().generation();Job job=new Job();job.future=IO.submit(()->{CURRENT.set(job);try{checkCancelled();T result=task.call();MAIN.post(()->{if(!job.cancelled&&generation==SessionManager.getInstance().generation())cb.success(result);});}catch(Exception e){String message=ErrorMapper.message(e);MAIN.post(()->{if(!job.cancelled&&generation==SessionManager.getInstance().generation())cb.error(message);});}finally{CURRENT.remove();}});return job;}
 public static JSONObject obj(Object...pairs){JSONObject o=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)o.put((String)pairs[i],pairs[i+1]==null?JSONObject.NULL:pairs[i+1]);return o;}catch(JSONException e){throw new IllegalArgumentException(e);}}
 public static JSONArray array(String...strings){JSONArray a=new JSONArray();for(String s:strings)a.put(s);return a;}
}
