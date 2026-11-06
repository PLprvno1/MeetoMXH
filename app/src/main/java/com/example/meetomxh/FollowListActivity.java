package com.example.meetomxh;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.ui.AvatarViews;
import org.json.*;
import java.util.*;

public final class FollowListActivity extends AppCompatActivity {
 private String target,direction,afterTime,afterId;private boolean loading,more=true;
 private final List<JSONObject> rows=new ArrayList<>();private Adapter adapter;private TextView status;
 @Override public void onCreate(Bundle saved){super.onCreate(saved);target=getIntent().getStringExtra("target_uid");if(target==null)target=SessionManager.getInstance().uid();direction="followers".equals(getIntent().getStringExtra("direction"))?"followers":"following";
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(getColor(R.color.app_bg));Button back=new Button(this);back.setText("‹  "+("followers".equals(direction)?"Người theo dõi":"Đang theo dõi"));back.setOnClickListener(v->finish());root.addView(back);
  status=new TextView(this);status.setTextColor(getColor(R.color.text_primary));status.setPadding(dp(16),dp(8),dp(16),dp(8));status.setOnClickListener(v->refresh());root.addView(status);
  RecyclerView list=new RecyclerView(this);list.setLayoutManager(new LinearLayoutManager(this));adapter=new Adapter();list.setAdapter(adapter);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
  list.addOnScrollListener(new RecyclerView.OnScrollListener(){public void onScrolled(RecyclerView r,int dx,int dy){if(dy>0&&!r.canScrollVertically(1))load();}});setContentView(root);load();
 }
 private void refresh(){if(loading)return;afterTime=null;afterId=null;more=true;rows.clear();adapter.notifyDataSetChanged();load();}
 private void load(){if(loading||!more)return;loading=true;status.setText("Đang tải…");
  Api.run(()->(JSONArray)RpcClient.call("list_follows",Api.obj("p_target",target,"p_direction",direction,"p_after_time",afterTime,"p_after_id",afterId,"p_limit",30)),new Api.Callback<JSONArray>(){
   public void success(JSONArray page){loading=false;Set<String> ids=new HashSet<>();for(JSONObject r:rows)ids.add(r.optString("profile_id"));for(int i=0;i<page.length();i++){JSONObject r=page.optJSONObject(i);if(r==null)continue;if(ids.add(r.optString("profile_id")))rows.add(r);afterTime=r.optString("created_at");afterId=r.optString("profile_id");}more=page.length()==30;adapter.notifyDataSetChanged();status.setText(rows.isEmpty()?"Chưa có tài khoản nào trong danh sách.":"");}
   public void error(String e){loading=false;status.setText(e+" · Chạm để thử lại");}
  });
 }
 private void actions(JSONObject r){String id=r.optString("profile_id");boolean ownFollowers=target.equals(SessionManager.getInstance().uid())&&"followers".equals(direction);
  List<String> labels=new ArrayList<>();labels.add(r.optBoolean("requested")?"Hủy yêu cầu theo dõi":r.optBoolean("following")?"Bỏ theo dõi":"Theo dõi");if(ownFollowers)labels.add("Xóa người theo dõi");
  new android.app.AlertDialog.Builder(this).setTitle(r.optString("display_name","Người dùng")).setItems(labels.toArray(new String[0]),(d,n)->{
   boolean remove=n==1;boolean desired=!r.optBoolean("following")&&!r.optBoolean("requested");Runnable action=()->Api.run(()->RpcClient.call(remove?"remove_follower":"set_follow",remove?Api.obj("p_follower",id):Api.obj("p_target",id,"p_follow",desired)),new Api.Callback<Object>(){public void success(Object v){refresh();}public void error(String e){Toast.makeText(FollowListActivity.this,e,Toast.LENGTH_LONG).show();}});
   if(remove||!desired)new android.app.AlertDialog.Builder(this).setTitle(remove?"Xóa người theo dõi?":"Xác nhận thay đổi theo dõi?").setNegativeButton("Hủy",null).setPositiveButton("Xác nhận",(x,y)->action.run()).show();else action.run();
  }).show();
 }
 private final class Adapter extends RecyclerView.Adapter<Holder>{
  public Holder onCreateViewHolder(android.view.ViewGroup p,int type){LinearLayout row=new LinearLayout(p.getContext());row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(dp(16),dp(8),dp(16),dp(8));ImageView avatar=new ImageView(p.getContext());row.addView(avatar,new LinearLayout.LayoutParams(dp(48),dp(48)));TextView name=new TextView(p.getContext());name.setTextSize(16);name.setTextColor(getColor(R.color.text_primary));name.setPadding(dp(12),0,dp(8),0);row.addView(name,new LinearLayout.LayoutParams(0,-2,1));Button action=new Button(p.getContext());action.setAllCaps(false);row.addView(action);return new Holder(row,avatar,name,action);}
  public void onBindViewHolder(Holder h,int position){JSONObject r=rows.get(position);String id=r.optString("profile_id");h.name.setText(r.optString("display_name","Người dùng"));AvatarViews.load(h.avatar,Dto.asset(Dto.nullable(r,"avatar_asset_id")),r.optString("avatar_preset","neutral"));h.itemView.setOnClickListener(v->startActivity(new android.content.Intent(FollowListActivity.this,ProfileActivity.class).putExtra("target_uid",id)));h.action.setText(r.optBoolean("requested")?"Đã gửi yêu cầu":r.optBoolean("following")?"Đang theo dõi":"Theo dõi");h.action.setVisibility(id.equals(SessionManager.getInstance().uid())?View.GONE:View.VISIBLE);h.action.setOnClickListener(v->actions(r));}
  public int getItemCount(){return rows.size();}
 }
 private static final class Holder extends RecyclerView.ViewHolder{final ImageView avatar;final TextView name;final Button action;Holder(View v,ImageView a,TextView n,Button b){super(v);avatar=a;name=n;action=b;}}
 private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
}
