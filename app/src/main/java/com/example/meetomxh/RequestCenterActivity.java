package com.example.meetomxh;

import android.os.Bundle;
import android.content.Intent;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.ui.AvatarViews;
import org.json.*;

/** Recipient-owned follow requests, message requests, and Messenger group invitations. */
public final class RequestCenterActivity extends AppCompatActivity {
    private String mode,afterTime,afterId; private LinearLayout rows; private TextView status; private Button next; private boolean loading;
    @Override public void onCreate(Bundle saved){super.onCreate(saved);mode=getIntent().getStringExtra("mode");if(mode==null)mode="follow";
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(getColor(R.color.app_bg));int pad=dp(16);root.setPadding(pad,pad,pad,pad);
        Button back=new Button(this);back.setText("‹  "+("message".equals(mode)?"Tin nhắn chờ":"group".equals(mode)?"Lời mời nhóm chat":"Yêu cầu theo dõi"));back.setOnClickListener(v->finish());root.addView(back);
        status=new TextView(this);status.setTextColor(getColor(R.color.text_primary));root.addView(status);status.setOnClickListener(v->load(true));
        ScrollView scroll=new ScrollView(this);rows=new LinearLayout(this);rows.setOrientation(1);scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        next=new Button(this);next.setText("Tải thêm");next.setOnClickListener(v->load(false));root.addView(next);setContentView(root);load(true);
    }
    private String rpc(){return "message".equals(mode)?"list_message_requests":"group".equals(mode)?"list_conversation_invitations":"list_follow_requests";}
    private void load(boolean reset){if(loading)return;loading=true;if(reset){afterTime=null;afterId=null;rows.removeAllViews();}status.setText("Đang tải…");next.setEnabled(false);
        Api.run(()->(JSONArray)RpcClient.call(rpc(),Api.obj("p_after_time",afterTime,"p_after_id",afterId,"p_limit",30)),new Api.Callback<JSONArray>(){
            public void success(JSONArray page){if(isFinishing())return;loading=false;for(int i=0;i<page.length();i++){JSONObject r=page.optJSONObject(i);if(r==null)continue;add(r);afterTime=r.optString("created_at");afterId=r.optString("group".equals(mode)?"conversation_id":"profile_id");}next.setEnabled(true);next.setVisibility(page.length()==30?View.VISIBLE:View.GONE);status.setText(rows.getChildCount()==0?"Không có yêu cầu đang chờ.":"");}
            public void error(String e){loading=false;next.setEnabled(true);status.setText(e+" · Chạm để thử lại");}
        });
    }
    private void add(JSONObject r){LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(0,dp(12),0,dp(16));
        LinearLayout identity=new LinearLayout(this);ImageView avatar=new ImageView(this);identity.addView(avatar,new LinearLayout.LayoutParams(dp(44),dp(44)));AvatarViews.load(avatar,Dto.asset(Dto.nullable(r,"avatar_asset_id")),r.optString("avatar_preset","neutral"));TextView text=new TextView(this);text.setTextSize(16);text.setTextColor(getColor(R.color.text_primary));text.setPadding(dp(12),0,0,0);text.setText(("group".equals(mode)?r.optString("title","Nhóm chat")+"\n":"")+r.optString("display_name","Người dùng")+(r.has("body")?"\n"+r.optString("body"):""));identity.addView(text,new LinearLayout.LayoutParams(0,-2,1));card.addView(identity);
        LinearLayout actions=new LinearLayout(this);Button accept=new Button(this),decline=new Button(this);accept.setText("Chấp nhận");decline.setText("Từ chối");actions.addView(accept,new LinearLayout.LayoutParams(0,-2,1));actions.addView(decline,new LinearLayout.LayoutParams(0,-2,1));card.addView(actions);accept.setOnClickListener(v->resolve(r,true,card));decline.setOnClickListener(v->resolve(r,false,card));
        if("message".equals(mode)){Button more=new Button(this);more.setText("Chặn / Báo cáo");card.addView(more);more.setOnClickListener(v->new android.app.AlertDialog.Builder(this).setItems(new String[]{"Chặn người dùng","Báo cáo tài khoản"},(d,n)->{String id=r.optString("profile_id");if(n==1)startActivity(new Intent(this,ReportContentActivity.class).putExtra(ReportContentActivity.EXTRA_TARGET_TYPE,"profile").putExtra(ReportContentActivity.EXTRA_TARGET_ID,id));else new android.app.AlertDialog.Builder(this).setTitle("Chặn người dùng?").setNegativeButton("Hủy",null).setPositiveButton("Chặn",(x,y)->Api.run(()->RpcClient.call("set_block",Api.obj("p_target",id,"p_blocked",true)),new Api.Callback<Object>(){public void success(Object o){load(true);}public void error(String e){toast(e);}})).show();}).show());}
        rows.addView(card,new LinearLayout.LayoutParams(-1,-2));
    }
    private void resolve(JSONObject row,boolean accept,View card){if(!card.isEnabled())return;card.setEnabled(false);String name="message".equals(mode)?"resolve_message_request":"group".equals(mode)?"resolve_conversation_invitation":"resolve_follow_request";String key="message".equals(mode)?"p_sender":"group".equals(mode)?"p_conversation_id":"p_requester";
        Api.run(()->RpcClient.object(name,Api.obj(key,row.optString("group".equals(mode)?"conversation_id":"profile_id"),"p_accept",accept)),new Api.Callback<JSONObject>(){
            public void success(JSONObject r){load(true);String cid=Dto.nullable(r,"conversation_id");if(accept&&cid!=null)startActivity(new Intent(RequestCenterActivity.this,ChatActivity.class).putExtra(ChatActivity.EXTRA_CHAT_ID,cid));}
            public void error(String e){card.setEnabled(true);toast(e);}
        });
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
}
