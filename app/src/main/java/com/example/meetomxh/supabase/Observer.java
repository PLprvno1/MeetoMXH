package com.example.meetomxh.supabase;
import java.util.concurrent.Callable;
public final class Observer<T> implements Subscription {
 private volatile boolean stopped,busy,dirty;private final long epoch=SessionManager.getInstance().generation();
 private final Callable<T> fetch;private final Api.Callback<T> cb;private final Subscription realtime;private Subscription request;
 private final long pollMillis;
 private final Runnable timer=new Runnable(){public void run(){refresh();if(!stopped)Api.MAIN.postDelayed(this,pollMillis);}};
 public Observer(Callable<T> fetch,Api.Callback<T> cb){this(fetch,cb,30000);}
 public Observer(Callable<T> fetch,Api.Callback<T> cb,long pollMillis){if(pollMillis<1000)throw new IllegalArgumentException("Polling interval too short");this.fetch=fetch;this.cb=cb;this.pollMillis=pollMillis;realtime=RealtimeClient.get().watch(this::refresh);refresh();Api.MAIN.postDelayed(timer,pollMillis);}
 public synchronized void refresh(){if(stopped||epoch!=SessionManager.getInstance().generation())return;if(busy){dirty=true;return;}busy=true;dirty=false;request=Api.run(fetch,new Api.Callback<T>(){public void success(T value){busy=false;if(!stopped)cb.success(value);again();}public void error(String error){busy=false;if(!stopped)cb.error(error);again();}});}
 private void again(){if(dirty&&!stopped)refresh();}
 public void remove(){stopped=true;if(request!=null)request.remove();realtime.remove();Api.MAIN.removeCallbacks(timer);}
}
