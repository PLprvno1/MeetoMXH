package com.example.meetomxh;
import android.app.Activity;
import android.content.*;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.adapters.ChatMessageAdapter;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class ChatHistoryTest {
 private JSONObject send(JSONObject peer,String cid,String body)throws Exception{return (JSONObject)SupabaseHttpClient.raw("POST","/rest/v1/rpc/send_message",Api.obj("p_conversation_id",cid,"p_client_request_id",UUID.randomUUID(),"p_kind","text","p_body",body,"p_assets",new JSONArray(),"p_reply_to",null),peer.getString("access_token"));}
 private int count(Activity a){final int[] n={0};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->n[0]=((RecyclerView)a.findViewById(R.id.rvMessages)).getAdapter().getItemCount());return n[0];}
 @Test public void sequenceCatchupAndOlderPageReconcile()throws Exception{
  SupabaseCutoverTest helper=new SupabaseCutoverTest();Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();JSONObject peer=helper.newPeer("Android08 History Peer");String peerId=peer.getJSONObject("user").getString("id"),cid=RpcClient.object("get_or_create_private",Api.obj("p_peer",peerId)).getString("id");
  for(int i=0;i<60;i++)send(peer,cid,"History original "+i);
  c.startActivity(new Intent(c,ChatActivity.class).putExtra("chat_id",cid).putExtra("other_uid",peerId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));helper.screen(ChatActivity.class);Activity a=helper.activity();helper.waitFor("initial history window has 50 unique messages",()->count(a)==50);
  RealtimeClient.get().stop();for(int i=0;i<55;i++)send(peer,cid,"History disconnected "+i);RealtimeClient.get().connect();helper.waitFor("sequence catchup preserves original window plus 55 newer rows",()->count(a)==105);
  onView(withText("Tải tin nhắn cũ hơn")).perform(click());helper.waitFor("older page retrieves all 115 rows",()->count(a)==115);
  final int[] found={0};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{ChatMessageAdapter adapter=(ChatMessageAdapter)((RecyclerView)a.findViewById(R.id.rvMessages)).getAdapter();found[0]=adapter.searchLoadedTextMessages("History").size();});assertEquals("deduplicated window",115,found[0]);
  assertEquals(100,RpcClient.object("search_messages",Api.obj("p_conversation_id",cid,"p_query","History","p_limit",100)).getJSONArray("items").length());
  try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"history-results.json"))){out.write(Api.obj("initial50",true,"disconnected55Catchup",true,"olderPage115Unique",true,"serverSearch100Limit",true).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 }
}
