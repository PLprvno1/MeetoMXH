package com.example.meetomxh;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.*;
import android.widget.VideoView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.storage.SupabaseStorage;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class MediaAndMessengerTest {
 private final Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private final JSONArray checks=new JSONArray();
 private void check(String label,boolean pass){checks.put(Api.obj("check",label,"pass",pass));assertTrue(label,pass);}
 private JSONObject rpc(String name,JSONObject input)throws Exception{return RpcClient.object(name,input);}
 private JSONObject other(JSONObject peer,String name,JSONObject input)throws Exception{return (JSONObject)SupabaseHttpClient.raw("POST","/rest/v1/rpc/"+name,input,peer.getString("access_token"));}
 private void denied(String label,java.util.concurrent.Callable<Object> action)throws Exception{try{action.call();check(label,false);}catch(ErrorMapper.HttpError e){check(label,e.status==400||e.status==401||e.status==403||e.status==404||e.status==409||e.status==422);}}
 private Uri fixture(String mime,Bitmap.CompressFormat format)throws Exception{
  ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,"meeto08-"+UUID.randomUUID()+(mime.equals("video/mp4")?".mp4":mime.equals("image/png")?".png":mime.equals("image/jpeg")?".jpg":".webp"));values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
  Uri uri=c.getContentResolver().insert(mime.equals("video/mp4")?MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY):MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values);
  try(OutputStream out=c.getContentResolver().openOutputStream(uri)){if(format!=null){Bitmap image=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888);image.eraseColor(0xffaa5522);image.compress(format,90,out);image.recycle();}else try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("verified-small.mp4")){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}}
  return uri;
 }
 private String upload(Uri uri,String purpose)throws Exception{CountDownLatch latch=new CountDownLatch(1);String[] ref={null};SupabaseStorage.upload(c.getContentResolver(),uri,purpose,SessionManager.getInstance().uid(),new SupabaseStorage.UploadListener(){public void onSuccess(String asset){ref[0]=asset;latch.countDown();}public void onFailure(String ignored){latch.countDown();}});check("verified Android upload "+purpose,latch.await(150,TimeUnit.SECONDS)&&ref[0]!=null);return ref[0];}
 private VideoView findVideo(View view){if(view instanceof VideoView)return (VideoView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){VideoView found=findVideo(group.getChildAt(i));if(found!=null)return found;}}return null;}
 @Test public void mediaAndMessengerContractsOnAndroid()throws Exception{
  try{
   SupabaseCutoverTest helper=new SupabaseCutoverTest();String owner=SessionManager.getInstance().uid();check("real restored user",!owner.isEmpty());
   JSONObject peer=helper.newPeer("Android08 Additional Peer");String peerId=peer.getJSONObject("user").getString("id");
   JSONObject cancelled=rpc("send_friend_request",Api.obj("p_recipient",peerId,"p_client_request_id",UUID.randomUUID()));rpc("cancel_friend_request",Api.obj("p_request_id",cancelled.getString("id")));
   check("cancel request removes pending",!((JSONArray)RpcClient.call("get_sent_requests",Api.obj())).toString().contains(peerId));
   JSONObject declined=rpc("send_friend_request",Api.obj("p_recipient",peerId,"p_client_request_id",UUID.randomUUID()));other(peer,"decline_friend_request",Api.obj("p_request_id",declined.getString("id")));check("decline request removes pending",!((JSONArray)RpcClient.call("get_sent_requests",Api.obj())).toString().contains(peerId));
   JSONObject request=rpc("send_friend_request",Api.obj("p_recipient",peerId,"p_client_request_id",UUID.randomUUID()));other(peer,"accept_friend_request",Api.obj("p_request_id",request.getString("id")));
   String avatar=upload(fixture("image/jpeg",Bitmap.CompressFormat.JPEG),"avatar"),cover=upload(fixture("image/webp",Bitmap.CompressFormat.WEBP),"cover");JSONObject profile=ProfileClient.self();
   JSONObject updated=rpc("update_profile",Api.obj("p_display_name",profile.getString("display_name"),"p_bio",profile.optString("bio"),"p_avatar_asset_id",Dto.assetId(avatar),"p_cover_asset_id",Dto.assetId(cover),"p_expected_version",profile.getLong("version")));
   check("profile attachments authoritative",updated.getString("avatar_asset_id").equals(Dto.assetId(avatar))&&updated.getString("cover_asset_id").equals(Dto.assetId(cover)));
   denied("stale profile version conflicts",()->rpc("update_profile",Api.obj("p_display_name","stale","p_bio","","p_avatar_asset_id",null,"p_cover_asset_id",null,"p_expected_version",profile.getLong("version"))));
   JSONObject privateProfile=rpc("update_profile_private",Api.obj("p_birth_date","2001-02-03","p_gender",null,"p_expected_version",updated.getLong("version")));check("private profile current version",privateProfile.getString("birth_date").equals("2001-02-03"));
   JSONObject publicProfile=other(peer,"get_profile",Api.obj("p_target",owner));check("peer cannot read private birth date",!publicProfile.has("birth_date")||publicProfile.isNull("birth_date"));
   String video=upload(fixture("video/mp4",null),"posts");JSONObject videoPost=rpc("create_post",Api.obj("p_body","Android actual video","p_audience","friends","p_assets",Api.array(Dto.assetId(video)),"p_client_request_id",UUID.randomUUID()));
   c.startActivity(new Intent(c,ChatImageViewerActivity.class).putExtra("image_url",video).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));helper.screen(ChatImageViewerActivity.class);
   helper.waitFor("native video decoded over authenticated network",()->{final android.app.Activity currentActivity=helper.activity();final boolean[] playing={false};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{VideoView view=findVideo(currentActivity.getWindow().getDecorView());playing[0]=view!=null&&view.getDuration()>0;});return playing[0];});check("video view network decode",true);
   JSONObject videoInfo=SupabaseStorage.info(video);try(Response response=SupabaseHttpClient.CLIENT.newCall(new Request.Builder().url(SupabaseStorage.url(videoInfo)).header("apikey",SupabaseConfig.KEY).header("Authorization","Bearer "+SessionManager.getInstance().access(false)).header("Range","bytes=0-31").build()).execute()){check("MP4 authenticated Range",response.code()==206&&response.body().bytes().length==32);}
   JSONObject convo=rpc("get_or_create_private",Api.obj("p_peer",peerId));String cid=convo.getString("id");String chatAsset=upload(fixture("image/png",Bitmap.CompressFormat.PNG),"chats/"+cid);
   denied("ready unattached chat asset deny",()->SupabaseStorage.info(chatAsset));
   JSONObject message=rpc("send_message",Api.obj("p_conversation_id",cid,"p_client_request_id",UUID.randomUUID(),"p_kind","media","p_body","","p_assets",Api.array(Dto.assetId(chatAsset)),"p_reply_to",null));check("chat media attached",SupabaseStorage.info(chatAsset).has("object_path"));
   rpc("recall_message",Api.obj("p_message_id",message.getString("id")));denied("recall revokes asset",()->SupabaseStorage.info(chatAsset));
   JSONObject group=rpc("create_group_conversation",Api.obj("p_title","Android admin group","p_members",Api.array(peerId),"p_client_request_id",UUID.randomUUID()));String gid=group.getString("id");
   rpc("manage_conversation_member",Api.obj("p_conversation_id",gid,"p_target",peerId,"p_action","promote"));check("promoted member role",other(peer,"get_conversation",Api.obj("p_conversation_id",gid)).getString("role").equals("admin"));
   rpc("manage_conversation_member",Api.obj("p_conversation_id",gid,"p_target",peerId,"p_action","demote"));
   JSONObject old=rpc("send_message",Api.obj("p_conversation_id",gid,"p_client_request_id",UUID.randomUUID(),"p_kind","text","p_body","Before kick","p_assets",new JSONArray()));
   rpc("manage_conversation_member",Api.obj("p_conversation_id",gid,"p_target",peerId,"p_action","remove"));denied("kicked old JWT denies",()->other(peer,"get_conversation",Api.obj("p_conversation_id",gid)));
   rpc("manage_conversation_member",Api.obj("p_conversation_id",gid,"p_target",peerId,"p_action","add"));check("rejoin does not restore history",other(peer,"get_messages",Api.obj("p_conversation_id",gid)).getJSONArray("items").length()==0);
   JSONObject pending=Api.obj("p_conversation_id",cid,"p_client_request_id",UUID.randomUUID(),"p_kind","text","p_body","Encrypted durable retry","p_assets",new JSONArray(),"p_reply_to",null);MessageOutbox.put(pending);check("pending outbox persisted",MessageOutbox.forConversation(cid).length()==1);check("retry dispatches committed RPC",MessageOutbox.retry(cid)==1&&MessageOutbox.forConversation(cid).length()==0);
   check("retry not duplicated",rpc("search_messages",Api.obj("p_conversation_id",cid,"p_query","Encrypted durable retry")).getJSONArray("items").length()==1);
   rpc("unfriend",Api.obj("p_other",peerId));check("unfriend removes canonical relation",!((JSONArray)RpcClient.call("get_friends",Api.obj())).toString().contains(peerId));
   denied("friends audience revoked after unfriend",()->other(peer,"get_post",Api.obj("p_post_id",videoPost.getString("id"))));
  }finally{try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"media-messenger-results.json"))){out.write(Api.obj("checks",checks).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
 }
}
