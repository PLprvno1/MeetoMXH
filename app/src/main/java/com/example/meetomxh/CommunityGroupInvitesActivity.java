package com.example.meetomxh;

import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.models.FriendItem;
import com.example.meetomxh.repositories.CommunityGroupRepository;
import com.example.meetomxh.repositories.FriendRepository;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Forms;
import com.example.meetomxh.supabase.RpcClient;
import org.json.JSONArray;
import org.json.JSONObject;

public class CommunityGroupInvitesActivity extends AppCompatActivity {
    private String groupId; private android.widget.LinearLayout form;
    @Override protected void onCreate(Bundle b){super.onCreate(b);groupId=getIntent().getStringExtra("group_id");home();}
    private void home(){form=Forms.start(this,"Lời mời nhóm");if(groupId!=null)Forms.button(form,"Mời bạn bè vào nhóm",this::chooseFriend);Forms.button(form,"Làm mới lời mời đã nhận",this::inbox);inbox();if(groupId!=null)outbox();}
    private void inbox(){Api.run(()->(JSONArray)RpcClient.call("list_group_invites",Api.obj("p_group_id",null,"p_inbox",true,"p_after_time",null,"p_after_id",null,"p_limit",30)),new Api.Callback<JSONArray>(){public void success(JSONArray rows){if(rows.length()==0)Forms.status(CommunityGroupInvitesActivity.this,"Không có lời mời đang chờ.");for(int i=0;i<rows.length();i++){JSONObject x=rows.optJSONObject(i);String id=x.optString("id");Forms.button(form,x.optString("group_name","Nhóm")+" · Chấp nhận",()->resolve(id,"accept"));Forms.button(form,"Từ chối lời mời",()->resolve(id,"decline"));}}public void error(String e){Forms.status(CommunityGroupInvitesActivity.this,e);}});}
    private void outbox(){CommunityGroupRepository.invites(groupId,false,null,null,new CommunityGroupRepository.Callback<JSONArray>(){public void success(JSONArray rows){for(int i=0;i<rows.length();i++){JSONObject x=rows.optJSONObject(i);Forms.button(form,"Đã mời · "+x.optString("invitee_id")+" · Hủy",()->CommunityGroupRepository.action("cancel_group_invite",Api.obj("p_invite_id",x.optString("id")),notice("Đã hủy lời mời")));}}public void error(String e){}});}
    private void chooseFriend(){FriendRepository.getInstance().getFriendsList(com.example.meetomxh.supabase.SessionManager.getInstance().uid(),new FriendRepository.OnFriendItemsListener(){public void onItems(java.util.List<FriendItem> friends){if(friends.isEmpty()){Toast.makeText(CommunityGroupInvitesActivity.this,"Bạn chưa có bạn bè để mời.",Toast.LENGTH_LONG).show();return;}String[] names=new String[friends.size()];for(int i=0;i<friends.size();i++)names[i]=friends.get(i).getUser().getFullName();new AlertDialog.Builder(CommunityGroupInvitesActivity.this).setTitle("Chọn bạn bè").setItems(names,(d,n)->{FriendItem friend=friends.get(n);CommunityGroupRepository.action("invite_to_community_group",Api.obj("p_group_id",groupId,"p_invitee",friend.getUser().getUid()),notice("Đã gửi lời mời"));}).show();}public void onError(String e){Toast.makeText(CommunityGroupInvitesActivity.this,e,Toast.LENGTH_LONG).show();}});}
    private void resolve(String id,String action){CommunityGroupRepository.action("resolve_group_invite",Api.obj("p_invite_id",id,"p_action",action),notice("Đã cập nhật lời mời"));}
    private CommunityGroupRepository.Callback<JSONObject> notice(String text){return new CommunityGroupRepository.Callback<JSONObject>(){public void success(JSONObject o){Toast.makeText(CommunityGroupInvitesActivity.this,text,Toast.LENGTH_SHORT).show();recreate();}public void error(String e){Toast.makeText(CommunityGroupInvitesActivity.this,e,Toast.LENGTH_LONG).show();}};}
}
