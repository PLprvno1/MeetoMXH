package com.example.meetomxh;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.meetomxh.supabase.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import okhttp3.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class ClientSecurityTest {
 private final JSONArray checks=new JSONArray();
 private void denied(String name,Callable<Object> operation)throws Exception{boolean pass=false;try{operation.call();}catch(ErrorMapper.HttpError e){pass=e.status==401||e.status==403||e.status==404;}checks.put(Api.obj("check",name,"pass",pass));assertTrue(name,pass);}
 private boolean channel(String topic,boolean privateChannel,boolean publish)throws Exception{
  String token=SessionManager.getInstance().access(false);CountDownLatch done=new CountDownLatch(1);boolean[] allowed={false},transportFailure={false};
  WebSocket socket=SupabaseHttpClient.CLIENT.newWebSocket(new Request.Builder().url(SupabaseConfig.URL.replaceFirst("^http","ws")+"/realtime/v1/websocket?apikey="+SupabaseConfig.KEY+"&vsn=1.0.0").build(),new WebSocketListener(){
   public void onOpen(WebSocket ws,Response r){ws.send(Api.obj("topic","realtime:"+topic,"event","phx_join","ref","1","join_ref","1","payload",Api.obj("access_token",token,"config",Api.obj("private",privateChannel,"broadcast",Api.obj("ack",true,"self",false),"presence",Api.obj("enabled",false),"postgres_changes",new JSONArray()))).toString());}
   public void onMessage(WebSocket ws,String text){try{JSONObject frame=new JSONObject(text);if("phx_reply".equals(frame.optString("event"))){boolean ok="ok".equals(frame.getJSONObject("payload").optString("status"));if("1".equals(frame.optString("ref"))){if(!ok||!publish){allowed[0]=ok;done.countDown();}else ws.send(Api.obj("topic","realtime:"+topic,"event","broadcast","ref","2","join_ref","1","payload",Api.obj("type","broadcast","event","invalidate","payload",Api.obj("scope","messenger","event_id",UUID.randomUUID()))).toString());}else if("2".equals(frame.optString("ref"))){allowed[0]=ok;done.countDown();}}else if("phx_error".equals(frame.optString("event"))||"phx_close".equals(frame.optString("event"))){done.countDown();}}catch(JSONException ignored){}}
   public void onFailure(WebSocket ws,Throwable t,Response r){transportFailure[0]=true;done.countDown();}
  });
  try{assertTrue("actual channel response",done.await(20,TimeUnit.SECONDS));assertFalse("transport failure cannot prove authorization denial",transportFailure[0]);return allowed[0];}finally{socket.cancel();}
 }
 @Test public void rejectsSpoofedActorAndUnauthorizedRealtime()throws Exception{
  try{
   String token=SessionManager.getInstance().access(false);String[] parts=token.split("\\.");parts[2]=(parts[2].charAt(0)=='A'?"B":"A")+parts[2].substring(1);String forged=String.join(".",parts);
   denied("missing user JWT",()->SupabaseHttpClient.raw("POST","/rest/v1/rpc/get_my_profile",Api.obj(),null));
   denied("tampered signature JWT",()->SupabaseHttpClient.raw("POST","/rest/v1/rpc/get_my_profile",Api.obj(),forged));
   denied("no actor argument contract",()->RpcClient.call("send_message",Api.obj("p_actor",UUID.randomUUID(),"p_sender",UUID.randomUUID())));
   denied("direct post owner/counter DML",()->SupabaseHttpClient.authorized("POST","/rest/v1/posts",Api.obj("author_id",SessionManager.getInstance().uid(),"body","Unauthorized direct write","audience","public","reactions_count",999)));
   denied("guessed conversation UUID",()->RpcClient.call("get_conversation",Api.obj("p_conversation_id",UUID.randomUUID())));
   boolean own=channel("user:"+SessionManager.getInstance().uid(),true,false);checks.put(Api.obj("check","own private join","pass",own));assertTrue(own);
   boolean otherDenied=!channel("user:"+UUID.randomUUID(),true,false);checks.put(Api.obj("check","wrong user topic denied","pass",otherDenied));assertTrue(otherDenied);
   boolean publicDenied=!channel("public-probe",false,false);checks.put(Api.obj("check","public channel denied","pass",publicDenied));assertTrue(publicDenied);
   boolean publishDenied=!channel("user:"+SessionManager.getInstance().uid(),true,true);checks.put(Api.obj("check","client broadcast publish denied","pass",publishDenied));assertTrue(publishDenied);
  }finally{android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"client-security-results.json"))){out.write(Api.obj("checks",checks).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
 }
}
