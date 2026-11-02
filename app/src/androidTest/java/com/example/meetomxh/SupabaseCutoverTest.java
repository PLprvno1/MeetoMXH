package com.example.meetomxh;

import android.app.Activity;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.storage.SupabaseStorage;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import okhttp3.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

/** Real APK -> local GoTrue, RPC, Storage and private Broadcast. No operator key. */
@RunWith(AndroidJUnit4.class)
public class SupabaseCutoverTest {
 private final JSONArray checks=new JSONArray();
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private final String suffix=UUID.randomUUID().toString().replace("-","");
 private final String email="android08-"+suffix+"@example.test",password="A!08-"+UUID.randomUUID();
 interface Condition {boolean ok()throws Exception;}
 private void check(String label,boolean value){checks.put(Api.obj("check",label,"pass",value));assertTrue(label,value);}
 void waitFor(String label,Condition c)throws Exception {long end=System.currentTimeMillis()+60000;while(System.currentTimeMillis()<end){if(c.ok()){check(label,true);return;}Thread.sleep(250);}check(label,false);}
 Activity activity(){final Activity[] result={null};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))result[0]=a;});return result[0];}
 void screen(Class<?> type)throws Exception{waitFor("screen "+type.getSimpleName(),()->activity()!=null&&type.isInstance(activity()));}
 void launch(Class<?> type)throws Exception{context.startActivity(new Intent(context,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));screen(type);}
 private JSONObject rpc(String name,JSONObject args)throws Exception{return RpcClient.object(name,args);}
 private JSONObject peerRpc(JSONObject peer,String name,JSONObject args)throws Exception{return (JSONObject)SupabaseHttpClient.raw("POST","/rest/v1/rpc/"+name,args,peer.getString("access_token"));}
 String mailLink(String recipient,String type)throws Exception {
  OkHttpClient client=new OkHttpClient();
  for(int attempt=0;attempt<100;attempt++){
   try(Response response=client.newCall(new Request.Builder().url("http://127.0.0.1:54324/api/v1/messages").build()).execute()){
    JSONArray messages=new JSONObject(response.body().string()).getJSONArray("messages");
    for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);if(!m.getJSONArray("To").toString().contains(recipient))continue;
     try(Response detail=client.newCall(new Request.Builder().url("http://127.0.0.1:54324/api/v1/message/"+m.getString("ID")).build()).execute()){
      JSONObject body=new JSONObject(detail.body().string());String text=body.optString("Text")+" "+body.optString("HTML");
      java.util.regex.Matcher links=java.util.regex.Pattern.compile("https?://[^\\s<>\"']+/auth/v1/verify\\?[^\\s<>\"']+").matcher(text.replace("&amp;","&"));
      while(links.find()){Uri uri=Uri.parse(links.group());if(type.equals(uri.getQueryParameter("type"))){check("mail verification stays loopback",(uri.getHost().equals("127.0.0.1")||uri.getHost().equals("localhost"))&&uri.getPort()==54321);return links.group().replace("127.0.0.1","127.0.0.1").replace("localhost","127.0.0.1");}}
     }
    }
   }Thread.sleep(300);
  }throw new AssertionError("Local verification mail unavailable");
 }
 JSONObject newPeer(String name)throws Exception {
  String e="android08-peer-"+UUID.randomUUID()+"@example.test",p="A!08-"+UUID.randomUUID();
  SupabaseHttpClient.raw("POST","/auth/v1/signup",Api.obj("email",e,"password",p),null);
  Uri link=Uri.parse(mailLink(e,"signup"));
  SupabaseHttpClient.raw("POST","/auth/v1/verify",Api.obj("token_hash",link.getQueryParameter("token"),"type","signup"),null);
  JSONObject session=(JSONObject)SupabaseHttpClient.raw("POST","/auth/v1/token?grant_type=password",Api.obj("email",e,"password",p),null);
  peerRpc(session,"bootstrap_profile",Api.obj());
  peerRpc(session,"complete_onboarding",Api.obj("p_display_name",name,"p_birth_date","2000-01-01","p_gender",null,"p_expected_version",1));session.put("_test_password",p);return session;
 }
 private void denied(String label,Condition action)throws Exception{try{action.ok();check(label,false);}catch(ErrorMapper.HttpError e){check(label,e.status==400||e.status==401||e.status==403||e.status==404||e.status==409||e.status==422);}}
 void callback(String link)throws Exception{
  OkHttpClient noRedirect=new OkHttpClient.Builder().followRedirects(false).build();String location;
  try(Response response=noRedirect.newCall(new Request.Builder().url(link).build()).execute()){location=response.header("Location");check("GoTrue PKCE redirect",response.isRedirect()&&location!=null&&location.startsWith("meeto://auth/callback?code="));}
  context.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(location)).setClass(context,AuthCallbackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
 }
 @Test public void cutoverOnRealEmulator()throws Exception {
  try{
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->SessionManager.getInstance().clear());
   launch(LoginActivity.class);onView(withId(R.id.btnRegister)).perform(click());screen(RegisterActivity.class);
   onView(withHint("Họ và tên")).perform(replaceText("Android08 Owner"),closeSoftKeyboard());
   onView(withHint("Ngày sinh YYYY-MM-DD")).perform(replaceText("2000-01-01"),closeSoftKeyboard());
   onView(withHint("Email")).perform(replaceText(email),closeSoftKeyboard());
   onView(withHint("Mật khẩu (ít nhất 8 ký tự)")).perform(replaceText(password),closeSoftKeyboard());
   onView(withText("Đăng ký bằng email")).perform(scrollTo(),click());
   String verification=mailLink(email,"signup");check("signup requires email confirmation",SessionManager.getInstance().getCurrentUser()==null);
   denied("unverified password login denied",()->{SupabaseHttpClient.raw("POST","/auth/v1/token?grant_type=password",Api.obj("email",email,"password",password),null);return true;});
   callback(verification);screen(RegisterActivity.class);
   waitFor("PKCE created actual session",()->SessionManager.getInstance().getCurrentUser()!=null);
   onView(org.hamcrest.Matchers.allOf(withText("Hoàn tất hồ sơ"),isAssignableFrom(android.widget.Button.class))).perform(scrollTo(),click());screen(MainActivity.class);
   String owner=SessionManager.getInstance().uid();check("onboarding persisted",ProfileClient.self().getBoolean("onboarding_completed"));
   JSONObject peer=newPeer("Android08 Peer");String peerId=peer.getJSONObject("user").getString("id");
   JSONObject outsider=newPeer("Android08 Outsider");
   launch(ProfileEditActivity.class);
   waitFor("profile editor populated",()->ProfileClient.self().getLong("version")>=2);
   onView(withHint("Tiểu sử")).perform(replaceText("Android emulator profile"),closeSoftKeyboard());
   onView(withText("Lưu hồ sơ")).perform(scrollTo(),click());
   waitFor("profile edited through UI",()->ProfileClient.self().optString("bio").equals("Android emulator profile"));
   launch(CreatePostActivity.class);onView(withId(R.id.etPostContent)).perform(replaceText("Android08 UI post "+suffix),closeSoftKeyboard());onView(withId(R.id.btnPostSubmit)).perform(click());
   final JSONObject[] posted={null};waitFor("post UI committed",()->{JSONArray rows=(JSONArray)RpcClient.call("get_user_posts",Api.obj("p_author",owner,"p_limit",50));for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).optString("body").contains(suffix)){posted[0]=rows.getJSONObject(i);return true;}return false;});
   String postId=posted[0].getString("id");check("post DTO",Dto.post(rpc("get_post",Api.obj("p_post_id",postId))).getPostId().equals(postId));
   rpc("set_post_reaction",Api.obj("p_post_id",postId,"p_reaction","love"));check("reaction current state",rpc("get_post",Api.obj("p_post_id",postId)).getString("my_reaction").equals("love"));
   rpc("set_post_reaction",Api.obj("p_post_id",postId,"p_reaction",null));
   JSONObject comment=rpc("create_comment",Api.obj("p_post_id",postId,"p_parent_id",null,"p_body","Android root","p_client_request_id",UUID.randomUUID()));
   JSONObject reply=rpc("create_comment",Api.obj("p_post_id",postId,"p_parent_id",comment.getString("id"),"p_body","Android reply","p_client_request_id",UUID.randomUUID()));
   check("thread parent",reply.getString("parent_id").equals(comment.getString("id")));
   rpc("edit_comment",Api.obj("p_comment_id",comment.getString("id"),"p_body","Android edited","p_expected_version",comment.getLong("version")));
   rpc("delete_comment",Api.obj("p_comment_id",reply.getString("id"),"p_expected_version",reply.getLong("version")));
   check("share points to source",rpc("share_post",Api.obj("p_original",postId,"p_body","Android share","p_audience","public","p_client_request_id",UUID.randomUUID())).getString("original_post_id").equals(postId));
   JSONObject friend=rpc("send_friend_request",Api.obj("p_recipient",peerId,"p_client_request_id",UUID.randomUUID()));peerRpc(peer,"accept_friend_request",Api.obj("p_request_id",friend.getString("id")));
   check("friendship actual server",((JSONArray)RpcClient.call("get_friends",Api.obj())).toString().contains(peerId));
   Uri image=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"meeto08-"+suffix+".png");values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");Uri fixture=context.getContentResolver().insert(image,values);
   try(OutputStream out=context.getContentResolver().openOutputStream(fixture)){Bitmap b=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888);b.eraseColor(0xff3366cc);b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle();}
   CountDownLatch uploaded=new CountDownLatch(1);final String[] asset={null},uploadError={null};
   SupabaseStorage.upload(context.getContentResolver(),fixture,"posts",owner,new SupabaseStorage.UploadListener(){public void onSuccess(String ref){asset[0]=ref;uploaded.countDown();}public void onFailure(String e){uploadError[0]=e;uploaded.countDown();}});
   check("actual upload verified by local worker",uploaded.await(150,TimeUnit.SECONDS)&&asset[0]!=null&&uploadError[0]==null);
   JSONObject mediaPost=rpc("create_post",Api.obj("p_body","Private media","p_audience","private","p_assets",Api.array(Dto.assetId(asset[0])),"p_client_request_id",UUID.randomUUID()));JSONObject info=SupabaseStorage.info(asset[0]);
   try(Response read=SupabaseHttpClient.CLIENT.newCall(new Request.Builder().url(SupabaseStorage.url(info)).header("apikey",SupabaseConfig.KEY).header("Authorization","Bearer "+SessionManager.getInstance().access(false)).header("Range","bytes=0-15").build()).execute()){check("authenticated media Range",read.code()==206&&read.body().bytes().length==16);}
   denied("private post guessed UUID denied",()->{peerRpc(outsider,"get_post",Api.obj("p_post_id",mediaPost.getString("id")));return true;});

   context.startActivity(new Intent(context,ChatImageViewerActivity.class).putExtra("image_url",asset[0]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));screen(ChatImageViewerActivity.class);
   JSONObject conversation=rpc("get_or_create_private",Api.obj("p_peer",peerId));String cid=conversation.getString("id");
   context.startActivity(new Intent(context,ChatActivity.class).putExtra("chat_id",cid).putExtra("other_uid",peerId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));screen(ChatActivity.class);
   waitFor("private WS joined on emulator",()->RealtimeClient.get().reconnections>0);long events=RealtimeClient.get().receivedEvents;
   JSONObject incoming=peerRpc(peer,"send_message",Api.obj("p_conversation_id",cid,"p_client_request_id",UUID.randomUUID(),"p_kind","text","p_body","Android peer realtime","p_assets",new JSONArray(),"p_reply_to",null));
   waitFor("actual Broadcast received by Android",()->RealtimeClient.get().receivedEvents>events);
   waitFor("visible device read ACK",()->{JSONArray receipts=(JSONArray)SupabaseHttpClient.raw("POST","/rest/v1/rpc/get_read_receipts",Api.obj("p_conversation_id",cid,"p_message_id",incoming.getString("id")),peer.getString("access_token"));return receipts.length()==1&&receipts.getJSONObject(0).getBoolean("read");});
   onView(withId(R.id.etMessage)).perform(replaceText("Android UI send"),closeSoftKeyboard());onView(withId(R.id.btnSendMessage)).perform(click());
   waitFor("chat UI send committed",()->rpc("get_messages",Api.obj("p_conversation_id",cid)).getJSONArray("items").toString().contains("Android UI send"));
   check("search server full ACL",rpc("search_messages",Api.obj("p_conversation_id",cid,"p_query","Android UI")).getJSONArray("items").length()==1);
   String request=UUID.randomUUID().toString();JSONObject payload=Api.obj("p_conversation_id",cid,"p_client_request_id",request,"p_kind","text","p_body","Retry from Android","p_assets",new JSONArray(),"p_reply_to",incoming.getString("id"));JSONObject sent=rpc("send_message",payload);check("lost ACK idempotency",rpc("send_message",payload).getLong("seq")==sent.getLong("seq"));
   rpc("recall_message",Api.obj("p_message_id",sent.getString("id")));check("recall tombstone",!rpc("get_messages",Api.obj("p_conversation_id",cid)).getJSONArray("items").toString().contains("Retry from Android"));
   rpc("hide_message_for_me",Api.obj("p_message_id",incoming.getString("id")));check("hide affects current reader",rpc("search_messages",Api.obj("p_conversation_id",cid,"p_query","Android peer")).getJSONArray("items").length()==0);
   JSONObject group=rpc("create_group_conversation",Api.obj("p_title","Android08 group","p_members",Api.array(peerId),"p_client_request_id",UUID.randomUUID()));String gid=group.getString("id");
   check("group DTO",Dto.chat(group).getType().equals("group"));
   rpc("rename_conversation",Api.obj("p_conversation_id",gid,"p_title","Renamed Android group","p_expected_version",group.getLong("version")));
   JSONObject current=rpc("get_conversation",Api.obj("p_conversation_id",gid));rpc("set_conversation_state",Api.obj("p_conversation_id",gid,"p_state","archived","p_expected_version",current.getLong("version")));
   denied("archive rejects send",()->{rpc("send_message",Api.obj("p_conversation_id",gid,"p_client_request_id",UUID.randomUUID(),"p_kind","text","p_body","blocked","p_assets",new JSONArray()));return true;});
   current=rpc("get_conversation",Api.obj("p_conversation_id",gid));rpc("set_conversation_state",Api.obj("p_conversation_id",gid,"p_state","active","p_expected_version",current.getLong("version")));
   peerRpc(peer,"leave_conversation",Api.obj("p_conversation_id",gid));denied("old JWT after leave denied",()->{peerRpc(peer,"get_conversation",Api.obj("p_conversation_id",gid));return true;});
   long connections=RealtimeClient.get().reconnections;InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{RealtimeClient.get().stop();RealtimeClient.get().connect();});waitFor("socket reconnect reauthorizes",()->RealtimeClient.get().reconnections>connections);
   SessionManager.getInstance().access(true);check("real GoTrue refresh keeps actor",SessionManager.getInstance().uid().equals(owner));
   rpc("set_block",Api.obj("p_target",peerId,"p_blocked",true));denied("block revokes current peer JWT",()->{peerRpc(peer,"get_conversation",Api.obj("p_conversation_id",cid));return true;});rpc("set_block",Api.obj("p_target",peerId,"p_blocked",false));
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->SessionManager.getInstance().signOut());screen(LoginActivity.class);check("logout clears session",SessionManager.getInstance().getCurrentUser()==null);
   // Account switch uses an actual peer password-login session, never a fabricated JWT.
   launch(LoginActivity.class);onView(withId(R.id.etEmail)).perform(replaceText(peer.getJSONObject("user").getString("email")),closeSoftKeyboard());onView(withId(R.id.etPassword)).perform(replaceText(peer.getString("_test_password")),closeSoftKeyboard());onView(withId(R.id.btnLogin)).perform(click());screen(MainActivity.class);check("account switch actor",SessionManager.getInstance().uid().equals(peerId));
   denied("old private media absent after switch",()->{SupabaseStorage.info(asset[0]);return true;});
   check("new account profile isolated",ProfileClient.self().getString("profile_id").equals(peerId));
   // Leave a real encrypted session for separate process-death restore test.
  }finally{try(FileOutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),"cutover-results.json"))){out.write(Api.obj("environment","Android emulator -> Supabase local","checks",checks).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
 }
}
