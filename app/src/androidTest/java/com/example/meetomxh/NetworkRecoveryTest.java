package com.example.meetomxh;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.meetomxh.supabase.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.UUID;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class NetworkRecoveryTest {
 private final Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private final File dir=c.getExternalFilesDir(null);
 private void phase(String value)throws Exception{try(FileOutputStream out=new FileOutputStream(new File(dir,"network-phase.txt"))){out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
 private boolean control(String wanted)throws Exception{File f=new File(dir,"network-control.txt");if(!f.exists())return false;try(FileInputStream in=new FileInputStream(f)){byte[] b=new byte[64];int n=in.read(b);return n>0&&new String(b,0,n,java.nio.charset.StandardCharsets.UTF_8).trim().equals(wanted);}}
 @Test public void realNetworkLossRetainsOutboxThenReconnects()throws Exception{
  SupabaseCutoverTest helper=new SupabaseCutoverTest();JSONObject peer=helper.newPeer("Android08 Offline Peer");String cid=RpcClient.object("get_or_create_private",Api.obj("p_peer",peer.getJSONObject("user").getString("id"))).getString("id");
  Subscription watcher=RealtimeClient.get().watch(()->{});
  try{
   helper.waitFor("network test initial private join",()->RealtimeClient.get().reconnections>0);long joined=RealtimeClient.get().reconnections;phase("ready");helper.waitFor("host removed only ADB local bridge",()->control("offline"));
   RealtimeClient.get().stop();SupabaseHttpClient.CLIENT.connectionPool().evictAll();RealtimeClient.get().connect();
   boolean failed=false;try{ProfileClient.self();}catch(java.io.IOException expected){failed=true;}assertTrue("actual RPC fails without bridge",failed);
   JSONObject pending=Api.obj("p_conversation_id",cid,"p_client_request_id",UUID.randomUUID(),"p_kind","text","p_body","Android08 real network retry","p_assets",new JSONArray(),"p_reply_to",null);MessageOutbox.put(pending);
   failed=false;try{MessageOutbox.retry(cid);}catch(java.io.IOException expected){failed=true;}assertTrue("offline send fails",failed);assertEquals(1,MessageOutbox.forConversation(cid).length());
   helper.launch(MainActivity.class);helper.waitFor("feed shows actual network error",()->{final android.app.Activity current=helper.activity();final boolean[] shown={false};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{android.widget.TextView banner=current.findViewById(R.id.feedStatus);shown[0]=banner!=null&&banner.getText().toString().contains("Không kết nối");});return shown[0];});
   phase("offline-verified");helper.waitFor("host restored ADB bridge",()->control("online"));
   helper.waitFor("real RPC recovers",()->{try{return ProfileClient.self().getBoolean("onboarding_completed");}catch(java.io.IOException e){return false;}});helper.waitFor("private socket reconnects with current JWT",()->RealtimeClient.get().reconnections>joined);
   androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.feedStatus)).perform(androidx.test.espresso.action.ViewActions.click());helper.waitFor("feed retry button clears actual network error",()->com.example.meetomxh.repositories.FeedRepository.getInstance().error.getValue()==null);
   long events=RealtimeClient.get().receivedEvents;assertEquals(1,MessageOutbox.retry(cid));helper.waitFor("resumed worker Broadcast reaches Android",()->RealtimeClient.get().receivedEvents>events);assertEquals(0,MessageOutbox.forConversation(cid).length());assertEquals(1,RpcClient.object("search_messages",Api.obj("p_conversation_id",cid,"p_query","Android08 real network retry")).getJSONArray("items").length());
   try(FileOutputStream out=new FileOutputStream(new File(dir,"network-recovery-results.json"))){out.write(Api.obj("actualBridgeRemoved",true,"rpcFailureObserved",true,"outboxRetained",true,"rpcAndPrivateWsReconnect",true,"oneCommittedRetry",true,"realBroadcastAfterRecovery",true).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}phase("done");
  }finally{watcher.remove();}
 }
}
