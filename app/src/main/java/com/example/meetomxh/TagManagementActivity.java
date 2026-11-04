package com.example.meetomxh;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.PostDetails;
import org.json.JSONArray;
import org.json.JSONObject;

public final class TagManagementActivity extends AppCompatActivity {
    private LinearLayout list; private String policy="friends"; private boolean review=true;
    @Override protected void onCreate(Bundle state){super.onCreate(state);ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);int p=dp(16);list.setPadding(p,p,p,p);scroll.addView(list);setContentView(scroll);
        Button back=new Button(this);back.setText("‹  Quyền gắn thẻ & bài viết có gắn thẻ");back.setOnClickListener(v->finish());list.addView(back);
        Button policies=new Button(this);policies.setText("Cài đặt quyền gắn thẻ");policies.setOnClickListener(v->editSettings());list.addView(policies);
        TextView title=new TextView(this);title.setText("Bài viết có gắn thẻ bạn");title.setTextSize(18);title.setPadding(0,dp(16),0,dp(8));list.addView(title);load();
        Api.run(()->RpcClient.object("get_tagging_settings",Api.obj()),new Api.Callback<JSONObject>(){public void success(JSONObject o){policy=o.optString("tagging_policy","friends");review=o.optBoolean("tag_review_required",true);policies.setText("Quyền gắn thẻ: "+label(policy)+" · "+(review?"duyệt trước":"tự động chấp nhận"));}public void error(String e){toast(e);}});
    }
    private void editSettings(){String[] labels={"Mọi người","Chỉ bạn bè","Không ai"};String[] values={"everyone","friends","nobody"};int selected=policy.equals("everyone")?0:policy.equals("nobody")?2:1;
        new AlertDialog.Builder(this).setTitle("Ai được gắn thẻ bạn?").setSingleChoiceItems(labels,selected,(d,w)->{String next=values[w];d.dismiss();new AlertDialog.Builder(this).setTitle("Duyệt gắn thẻ").setMessage("Muốn duyệt từng thẻ trước khi hiển thị trên bài viết?").setNegativeButton("Tắt duyệt",(x,y)->saveSettings(next,false)).setPositiveButton("Bật duyệt",(x,y)->saveSettings(next,true)).show();}).setNegativeButton("Đóng",null).show();}
    private void saveSettings(String next,boolean required){Api.run(()->RpcClient.object("update_tagging_settings",Api.obj("p_policy",next,"p_review_required",required)),new Api.Callback<JSONObject>(){public void success(JSONObject o){policy=next;review=required;Toast.makeText(TagManagementActivity.this,"Đã lưu quyền gắn thẻ",Toast.LENGTH_SHORT).show();recreate();}public void error(String e){toast(e);}});}
    private void load(){list.removeViews(3,Math.max(0,list.getChildCount()-3));Api.run(()->(JSONArray)RpcClient.call("list_my_post_tags",Api.obj("p_state",null,"p_limit",30)),new Api.Callback<JSONArray>(){public void success(JSONArray rows){if(isFinishing())return;if(rows.length()==0){TextView empty=new TextView(TagManagementActivity.this);empty.setText("Chưa có bài viết được gắn thẻ.");empty.setPadding(0,dp(12),0,dp(12));list.addView(empty);return;}for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);JSONObject post=row==null?null:row.optJSONObject("post");if(post==null)continue;String postId=post.optString("id"),state=row.optString("state");LinearLayout item=new LinearLayout(TagManagementActivity.this);item.setOrientation(LinearLayout.VERTICAL);TextView summary=new TextView(TagManagementActivity.this);summary.setText(row.optString("tagged_by_name","Bạn bè")+" đã gắn thẻ bạn · "+post.optString("body","Bài viết có ảnh/video"));summary.setPadding(0,dp(10),0,dp(8));summary.setOnClickListener(v->PostDetails.show(TagManagementActivity.this,postId));item.addView(summary);if("pending".equals(state)){Button accept=new Button(TagManagementActivity.this);accept.setText("Chấp nhận");accept.setOnClickListener(v->resolve(postId,"approve"));item.addView(accept);Button reject=new Button(TagManagementActivity.this);reject.setText("Từ chối");reject.setOnClickListener(v->resolve(postId,"reject"));item.addView(reject);}else{Button remove=new Button(TagManagementActivity.this);remove.setText("Bỏ thẻ");remove.setOnClickListener(v->resolve(postId,"remove"));item.addView(remove);}list.addView(item);}}public void error(String e){TextView retry=new TextView(TagManagementActivity.this);retry.setText(e+" · Chạm để thử lại");retry.setOnClickListener(v->load());list.addView(retry);}});}
    private void resolve(String post,String action){Api.run(()->RpcClient.object("review_post_tag",Api.obj("p_post_id",post,"p_action",action)),new Api.Callback<JSONObject>(){public void success(JSONObject o){load();}public void error(String e){toast(e);}});}
    private String label(String p){return "everyone".equals(p)?"mọi người":"nobody".equals(p)?"không ai":"bạn bè";}
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private void toast(String e){Toast.makeText(this,e,Toast.LENGTH_LONG).show();}
}
