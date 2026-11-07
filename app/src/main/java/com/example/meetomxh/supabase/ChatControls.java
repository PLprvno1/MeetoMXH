package com.example.meetomxh.supabase;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.*;
import com.example.meetomxh.ChatActivity;
import com.example.meetomxh.models.Chat;
import com.example.meetomxh.repositories.ChatRepository;
import org.json.*;
import java.util.*;

public final class ChatControls {
 private static void act(Activity a,String rpc,JSONObject input){ChatRepository.getInstance().action(rpc,input,new ChatRepository.OnMessageActionListener(){public void onSuccess(){Toast.makeText(a,"Đã cập nhật",Toast.LENGTH_SHORT).show();if(rpc.equals("leave_conversation"))a.finish();else if(a instanceof ChatActivity)((ChatActivity)a).refreshConversationState();}public void onError(String e){toast(a,e);}});}
 public static void show(Activity a,Chat chat){Api.run(()->ChatRepository.getInstance().load(chat.getChatId()),new Api.Callback<Chat>(){public void success(Chat c){menu(a,c);}public void error(String e){toast(a,e);}});}
 private static void menu(Activity a,Chat c){
  List<String> labels=new ArrayList<>(Arrays.asList("Thành viên","Rời nhóm"));if("admin".equals(c.role))labels.addAll(Arrays.asList("Mời thành viên","Đổi tên nhóm"));
  new AlertDialog.Builder(a).setTitle(c.title).setItems(labels.toArray(new String[0]),(d,i)->{
   if(i==0)members(a,c);
   if(i==1)new AlertDialog.Builder(a).setTitle("Rời nhóm chat?").setMessage("Bạn sẽ không thể gửi hoặc đọc thêm tin nhắn. Quyền chủ nhóm được chuyển cho thành viên còn lại nếu cần.").setNegativeButton("Hủy",null).setPositiveButton("Rời nhóm",(x,n)->act(a,"leave_conversation",Api.obj("p_conversation_id",c.getChatId()))).show();
   if(i==2)invite(a,c);
   if(i==3){EditText name=new EditText(a);name.setText(c.title);AlertDialog edit=new AlertDialog.Builder(a).setTitle("Đổi tên nhóm").setView(name).setPositiveButton("Lưu",null).setNegativeButton("Hủy",null).create();edit.setOnShowListener(x->edit.getButton(-1).setOnClickListener(v->{String title=name.getText().toString().trim();if(title.isEmpty()||title.length()>150){name.setError("Nhập tên từ 1 đến 150 ký tự");return;}act(a,"rename_conversation",Api.obj("p_conversation_id",c.getChatId(),"p_title",title,"p_expected_version",c.version));edit.dismiss();}));edit.show();}
  }).show();
 }
 public static void members(Activity a,Chat c){Api.run(()->(JSONArray)RpcClient.call("get_conversation_members",Api.obj("p_conversation_id",c.getChatId())),new Api.Callback<JSONArray>(){public void success(JSONArray rows){
  ScrollView scroll=new ScrollView(a);LinearLayout list=new LinearLayout(a);list.setOrientation(1);scroll.addView(list);
  for(int j=0;j<rows.length();j++){JSONObject p=rows.optJSONObject(j);if(p==null)continue;Button row=new Button(a);String name=ChatCustomizationClient.name(p);row.setText((name==null?"Thành viên":name)+(p.optString("user_id").equals(c.ownerId)?" · Chủ nhóm":"")+"\n"+("admin".equals(p.optString("role"))?"Quản trị viên":"Thành viên"));row.setAllCaps(false);row.setMinHeight((int)(64*a.getResources().getDisplayMetrics().density));row.setSingleLine(false);list.addView(row,new LinearLayout.LayoutParams(-1,-2));
   row.setOnClickListener(v->{if(!"admin".equals(c.role)||SessionManager.getInstance().uid().equals(p.optString("user_id"))||p.optString("user_id").equals(c.ownerId))return;new AlertDialog.Builder(a).setItems(new String[]{"Loại thành viên","Đặt quản trị viên","Đặt thành viên"},(x,n)->new AlertDialog.Builder(a).setTitle("Xác nhận thay đổi?").setNegativeButton("Hủy",null).setPositiveButton("Xác nhận",(z,k)->act(a,"manage_conversation_member",Api.obj("p_conversation_id",c.getChatId(),"p_target",p.optString("user_id"),"p_action",new String[]{"remove","promote","demote"}[n]))).show()).show();});
  }new AlertDialog.Builder(a).setTitle("Thành viên · "+rows.length()).setView(scroll).setPositiveButton("Đóng",null).show();
 }public void error(String e){toast(a,e);}});}
 public static void invite(Activity a,Chat c){Api.run(()->(JSONArray)RpcClient.call("get_conversation_invite_candidates_excluded",Api.obj("p_conversation_id",c.getChatId())),new Api.Callback<JSONArray>(){public void success(JSONArray rows){if(a.isFinishing()||a.isDestroyed())return;Set<String> excluded=new HashSet<>();for(int i=0;i<rows.length();i++)excluded.add(rows.optString(i));PeoplePicker.showGroup(a,excluded,1,c.getChatId(),null,ids->{inviteNext(a,c,ids,0);});}public void error(String e){toast(a,e);}});}
 private static void inviteNext(Activity a,Chat c,List<String> ids,int index){if(a.isFinishing()||a.isDestroyed())return;if(index>=ids.size()){toast(a,"Đã gửi lời mời tham gia nhóm");return;}String id=ids.get(index);Api.run(()->RpcClient.call("manage_conversation_member",Api.obj("p_conversation_id",c.getChatId(),"p_target",id,"p_action","add")),new Api.Callback<Object>(){public void success(Object r){inviteNext(a,c,ids,index+1);}public void error(String e){toast(a,"Không thể gửi đủ lời mời: "+e);}});}
 public static void rename(Activity a,Chat c,Runnable refreshed){EditText name=new EditText(a);name.setText(c.title);AlertDialog edit=new AlertDialog.Builder(a).setTitle("Đổi tên nhóm").setView(name).setPositiveButton("Lưu",null).setNegativeButton("Hủy",null).create();edit.setOnShowListener(x->edit.getButton(-1).setOnClickListener(v->{String title=name.getText().toString().trim();if(title.isEmpty()||title.length()>150){name.setError("Nhập tên từ 1 đến 150 ký tự");return;}edit.getButton(-1).setEnabled(false);Api.run(()->RpcClient.call("rename_conversation",Api.obj("p_conversation_id",c.getChatId(),"p_title",title,"p_expected_version",c.version)),new Api.Callback<Object>(){public void success(Object r){edit.dismiss();if(refreshed!=null)refreshed.run();}public void error(String e){edit.getButton(-1).setEnabled(true);toast(a,e);}});}));edit.show();}
 public static void leave(Activity a,Chat c){new AlertDialog.Builder(a).setTitle("Rời nhóm chat?").setMessage("Bạn sẽ không thể gửi hoặc đọc thêm tin nhắn. Quyền chủ nhóm được chuyển theo quy tắc hiện tại của nhóm.").setNegativeButton("Hủy",null).setPositiveButton("Rời nhóm",(d,w)->Api.run(()->RpcClient.call("leave_conversation",Api.obj("p_conversation_id",c.getChatId())),new Api.Callback<Object>(){public void success(Object r){Toast.makeText(a,"Đã rời nhóm chat",Toast.LENGTH_SHORT).show();a.setResult(Activity.RESULT_OK);a.finish();}public void error(String e){toast(a,e);}})).show();}
 public static void create(Activity a){create(a,null);}
 public static void create(Activity a,String preselect){
  PeoplePicker.showGroup(a,Collections.emptySet(),2,null,preselect,ids->{
   if(a.isFinishing()||a.isDestroyed())return;
   EditText title=new EditText(a);title.setSingleLine(true);title.setHint("Tên nhóm chat");
   AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Mời "+ids.size()+" người vào nhóm")
    .setMessage("Người được mời phải chấp nhận trước khi tham gia.").setView(title)
    .setPositiveButton("Tạo nhóm",null).setNegativeButton("Hủy",null).create();
   Subscription[] request={null};ChatCustomizationUi.own(a,dialog,()->{if(request[0]!=null)request[0].remove();});
   dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
    String name=title.getText().toString().trim();
    if(name.isEmpty()||name.length()>150){title.setError("Nhập tên từ 1 đến 150 ký tự");return;}
    List<String> sorted=new ArrayList<>(ids);Collections.sort(sorted);String fingerprint=name+sorted;
    dialog.getButton(-1).setEnabled(false);dialog.getButton(-2).setEnabled(false);title.setEnabled(false);dialog.setCancelable(false);
    request[0]=Api.run(()->{
     ChatCustomizationClient.require();
     JSONObject result=RpcClient.object("create_group_conversation",Api.obj("p_title",name,"p_members",new JSONArray(sorted),"p_client_request_id",RequestIds.get("chat_group",fingerprint)));
     return UUID.fromString(result.getString("id")).toString();
    },new Api.Callback<String>(){
     public void success(String id){
      if(a.isFinishing()||a.isDestroyed()||!dialog.isShowing())return;
      a.startActivity(new Intent(a,ChatActivity.class).putExtra(ChatActivity.EXTRA_CHAT_ID,id));
      RequestIds.complete("chat_group",fingerprint);dialog.dismiss();
     }
     public void error(String error){if(a.isFinishing()||a.isDestroyed()||!dialog.isShowing())return;
      dialog.getButton(-1).setEnabled(true);dialog.getButton(-2).setEnabled(true);title.setEnabled(true);dialog.setCancelable(true);toast(a,error);
     }
    });
   }));dialog.show();
  });
 }
 public static void receipts(Activity a,String conversation,String message){Api.run(()->(JSONArray)RpcClient.call("get_read_receipts",Api.obj("p_conversation_id",conversation,"p_message_id",message)),new Api.Callback<JSONArray>(){public void success(JSONArray rows){StringBuilder text=new StringBuilder();for(int i=0;i<rows.length();i++){JSONObject r=rows.optJSONObject(i);text.append(r.optString("display_name","Thành viên")).append(" · ").append(r.optBoolean("read")?"Đã đọc":r.optBoolean("delivered")?"Đã giao":"Đã gửi").append("\n");}new AlertDialog.Builder(a).setTitle("Xác nhận từ thiết bị thành viên").setMessage(rows.length()==0?"Chưa có người nhận hợp lệ":text.toString()).setPositiveButton("Đóng",null).show();}public void error(String e){toast(a,e);}});}
 private static void toast(Activity a,String e){if(a.isFinishing()||a.isDestroyed())return;Toast.makeText(a,e,Toast.LENGTH_LONG).show();}
}
