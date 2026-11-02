package com.example.meetomxh.supabase;
import okhttp3.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
public final class RealtimeClient extends WebSocketListener {
 private static final RealtimeClient INSTANCE=new RealtimeClient();public static RealtimeClient get(){return INSTANCE;}
 private final CopyOnWriteArrayList<Runnable> subscribers=new CopyOnWriteArrayList<>();
 private final EventDeduplicator seen=new EventDeduplicator(512);private WebSocket socket;private long epoch;private boolean joined,connecting;private int ref=1,failures;
 public volatile long receivedEvents,reconnections;
 private RealtimeClient(){SessionManager.getInstance().listeners.add(()->{stop();if(!subscribers.isEmpty())connect();});}
 public Subscription watch(Runnable action){subscribers.add(action);connect();return ()->{subscribers.remove(action);if(subscribers.isEmpty())stop();};}
 public synchronized void connect(){if(connecting||socket!=null||subscribers.isEmpty()||SessionManager.getInstance().getCurrentUser()==null)return;connecting=true;epoch=SessionManager.getInstance().generation();long expected=epoch;
  Api.IO.execute(()->{try{String token=SessionManager.getInstance().access(false);synchronized(this){if(expected!=SessionManager.getInstance().generation()){connecting=false;return;}String ws=SupabaseConfig.URL.replaceFirst("^http","ws")+"/realtime/v1/websocket?apikey="+SupabaseConfig.KEY+"&vsn=1.0.0";socket=SupabaseHttpClient.CLIENT.newWebSocket(new Request.Builder().url(ws).build(),this);this.token=token;}}catch(Exception e){synchronized(this){connecting=false;}retry();}});
 }
 private String token;
 @Override public synchronized void onOpen(WebSocket ws,Response response){if(ws!=socket)return;connecting=false;String topic="realtime:user:"+SessionManager.getInstance().uid();send(ws,topic,"phx_join",Api.obj("config",Api.obj("private",true,"broadcast",Api.obj("ack",true,"self",false),"presence",Api.obj("enabled",false),"postgres_changes",new JSONArray()),"access_token",token));Api.MAIN.postDelayed(heartbeat,20000);}
 private void send(WebSocket ws,String topic,String event,JSONObject payload){ws.send(Api.obj("topic",topic,"event",event,"payload",payload,"ref",String.valueOf(ref++),"join_ref","1").toString());}
 private final Runnable heartbeat=new Runnable(){public void run(){synchronized(RealtimeClient.this){if(socket==null)return;send(socket,"phoenix","heartbeat",Api.obj());}Api.IO.execute(()->{try{SessionManager.getInstance().access(false);}catch(Exception ignored){}});Api.MAIN.postDelayed(this,20000);}};
 @Override public void onMessage(WebSocket ws,String text){if(ws!=socket||epoch!=SessionManager.getInstance().generation())return;try{JSONObject frame=new JSONObject(text);String event=frame.optString("event");if(event.equals("phx_reply")&&!joined&&frame.getJSONObject("payload").optString("status").equals("ok")){joined=true;failures=0;reconnections++;invalidate();}
  if(event.equals("phx_close")||event.equals("phx_error")){stop();retry();}
  if(event.equals("broadcast")){JSONObject payload=frame.getJSONObject("payload").getJSONObject("payload");String id=payload.optString("event_id");if(!payload.optString("scope").equals("messenger")||!id.matches("[0-9a-fA-F-]{36}")||!seen.accept(id))return;receivedEvents++;invalidate();}
 }catch(JSONException ignored){}}
 private void invalidate(){for(Runnable r:subscribers)Api.MAIN.post(r);}
 private void retry(){Api.MAIN.postDelayed(this::connect,Math.min(30000,1000L<<Math.min(failures++,5)));}
 @Override public void onFailure(WebSocket ws,Throwable t,Response response){if(ws==socket){stop();retry();}}
 @Override public void onClosed(WebSocket ws,int code,String reason){if(ws==socket){stop();retry();}}
 public synchronized void stop(){WebSocket old=socket;socket=null;connecting=false;joined=false;ref=1;Api.MAIN.removeCallbacks(heartbeat);if(old!=null)old.cancel();if(SessionManager.getInstance().getCurrentUser()==null)seen.clear();}
}
