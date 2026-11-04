package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.example.meetomxh.repositories.CommunityGroupRepository;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Forms;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.ui.AvatarViews;
import org.json.JSONArray;
import org.json.JSONObject;

public class CommunityGroupMembersActivity extends AppCompatActivity {
    private String groupId,afterTime,afterId; private LinearLayout form; private boolean loading,manager;
    @Override protected void onCreate(Bundle b){super.onCreate(b);groupId=getIntent().getStringExtra("group_id");form=Forms.start(this,"Thành viên nhóm");CommunityGroupRepository.get(groupId,new CommunityGroupRepository.Callback<JSONObject>(){public void success(JSONObject g){String role=g.optString("role");manager="owner".equals(role)||"admin".equals(role)||"moderator".equals(role);}public void error(String e){}});load();}
    private void load(){if(loading)return;loading=true;CommunityGroupRepository.members(groupId,afterTime,afterId,new CommunityGroupRepository.Callback<JSONArray>(){
        public void success(JSONArray rows){loading=false;if(rows.length()==0&&afterId==null)Forms.status(CommunityGroupMembersActivity.this,"Chưa có thành viên hiển thị.");for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null)add(row);}if(rows.length()==20){JSONObject last=rows.optJSONObject(19);afterTime=last.optString("joined_at");afterId=last.optString("user_id");Forms.button(form,"Tải thêm",CommunityGroupMembersActivity.this::load);}}
        public void error(String e){loading=false;Forms.status(CommunityGroupMembersActivity.this,e+" · chạm thử lại");}
    });}
    private void add(JSONObject m){String uid=m.optString("user_id");LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(8,8,8,8);ImageView avatar=new ImageView(this);row.addView(avatar,new LinearLayout.LayoutParams(48,48));AvatarViews.load(avatar,Dto.asset(Dto.nullable(m,"avatar_asset_id")),m.optString("avatar_preset","neutral"));TextView label=new TextView(this);label.setText(m.optString("display_name","Thành viên")+"\n"+role(m.optString("role")));label.setTextColor(ContextCompat.getColor(this,R.color.text_primary));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=12;row.addView(label,lp);form.addView(row);row.setOnClickListener(v->menu(m));}
    private String role(String role){switch(role){case "owner":return "Chủ nhóm";case "admin":return "Quản trị viên";case "moderator":return "Điều hành viên";default:return "Thành viên";}}
    private void menu(JSONObject m){String id=m.optString("user_id");if(id.equals(com.example.meetomxh.supabase.SessionManager.getInstance().uid())||!manager){startActivity(new Intent(this,ProfileActivity.class).putExtra("target_uid",id));return;}new AlertDialog.Builder(this).setTitle(m.optString("display_name","Thành viên")).setItems(new String[]{"Xem hồ sơ","Cấp moderator","Cấp admin","Hạ xuống thành viên","Xóa khỏi nhóm","Cấm tham gia nhóm","Chuyển quyền sở hữu"},(d,n)->{if(n==0){startActivity(new Intent(this,ProfileActivity.class).putExtra("target_uid",id));return;}String rpc=n<=3?"set_community_member_role":n==6?"transfer_group_owner":"manage_group_member";String payloadRole=n==1?"moderator":n==2?"admin":"member";String action=n==4?"remove":"ban";JSONObject args=n==6?Api.obj("p_group_id",groupId,"p_target",id):rpc.equals("set_community_member_role")?Api.obj("p_group_id",groupId,"p_user_id",id,"p_role",payloadRole):Api.obj("p_group_id",groupId,"p_user_id",id,"p_action",action);CommunityGroupRepository.action(rpc,args,new CommunityGroupRepository.Callback<JSONObject>(){public void success(JSONObject x){android.widget.Toast.makeText(CommunityGroupMembersActivity.this,"Đã cập nhật thành viên",android.widget.Toast.LENGTH_SHORT).show();recreate();}public void error(String e){android.widget.Toast.makeText(CommunityGroupMembersActivity.this,e,android.widget.Toast.LENGTH_LONG).show();}});}).show();}
}
