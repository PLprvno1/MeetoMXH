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
 private String target,direction,afterTime,afterId;private boolean loading,more=true,allowed;
 private long epoch;private Subscription request;private TextView footer;private View progress;
 private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe;
 private final Set<String> updating=new HashSet<>();private final List<Subscription> mutations=new ArrayList<>();
 private final Runnable identityChanged=()->{if(epoch!=SessionManager.getInstance().generation())finish();};
 private final List<JSONObject> rows=new ArrayList<>();private Adapter adapter;private TextView status;
 @Override public void onCreate(Bundle saved){super.onCreate(saved);target=getIntent().getStringExtra("target_uid");if(target==null)target=SessionManager.getInstance().uid();direction="followers".equals(getIntent().getStringExtra("direction"))?"followers":"following";
  epoch=SessionManager.getInstance().generation();if(SessionManager.getInstance().getCurrentUser()==null){finish();return;}
  SessionManager.getInstance().identityListeners.add(identityChanged);
  setContentView(R.layout.activity_follow_list);
  com.google.android.material.appbar.MaterialToolbar toolbar=findViewById(R.id.followToolbar);
  toolbar.setTitle("followers".equals(direction)?"Người theo dõi":"Đang theo dõi");toolbar.setNavigationOnClickListener(v->finish());
  status=findViewById(R.id.followState);footer=findViewById(R.id.followFooter);progress=findViewById(R.id.followProgress);
  swipe=findViewById(R.id.swipeFollowList);swipe.setOnRefreshListener(this::refresh);
  status.setOnClickListener(v->refresh());footer.setOnClickListener(v->load());
  RecyclerView list=findViewById(R.id.followList);list.setLayoutManager(new LinearLayoutManager(this));adapter=new Adapter();list.setAdapter(adapter);
  swipe.setOnChildScrollUpCallback((parent,child)->list.canScrollVertically(-1));
  list.addOnScrollListener(new RecyclerView.OnScrollListener(){public void onScrolled(RecyclerView r,int dx,int dy){if(dy>0&&!r.canScrollVertically(1))load();}});refresh();
 }
 private boolean alive(){return !isFinishing()&&!isDestroyed()&&epoch==SessionManager.getInstance().generation();}
 private void stopLoading(){loading=false;progress.setVisibility(View.GONE);swipe.setRefreshing(false);footer.setVisibility(View.GONE);}
 private void state(String text){status.setText(text);status.setVisibility(View.VISIBLE);}
 private void refresh(){
  if(loading||!alive())return;loading=true;allowed=false;afterTime=null;afterId=null;more=true;rows.clear();adapter.notifyDataSetChanged();
  status.setVisibility(View.GONE);footer.setVisibility(View.GONE);progress.setVisibility(swipe.isRefreshing()?View.GONE:View.VISIBLE);
  request=Api.run(()->RpcClient.object("get_profile",Api.obj("p_target",target)),new Api.Callback<JSONObject>(){
   public void success(JSONObject profile){if(!alive())return;stopLoading();
    JSONObject settings=profile.optJSONObject("settings");
    boolean restricted=profile.optBoolean("account_private",settings==null||settings.optBoolean("account_private",true));
    allowed=target.equals(SessionManager.getInstance().uid())||!restricted;
    if(!allowed){more=false;state("Danh sách riêng tư\nChỉ chủ tài khoản được xem người theo dõi và đang theo dõi.");return;}load();
   }
   public void error(String error){if(alive()){stopLoading();state(error+"\nChạm để thử lại.");}}
  });
 }
 private void load(){if(loading||!more||!allowed||!alive())return;loading=true;status.setVisibility(View.GONE);
  if(rows.isEmpty())progress.setVisibility(View.VISIBLE);else{footer.setVisibility(View.VISIBLE);footer.setText("Đang tải thêm…");}
  final String time=afterTime,id=afterId;
  request=Api.run(()->(JSONArray)RpcClient.call("list_follows",Api.obj("p_target",target,"p_direction",direction,"p_after_time",time,"p_after_id",id,"p_limit",30)),new Api.Callback<JSONArray>(){
   public void success(JSONArray page){if(!alive())return;stopLoading();Set<String> ids=new HashSet<>();for(JSONObject r:rows)ids.add(r.optString("profile_id"));for(int i=0;i<page.length();i++){JSONObject r=page.optJSONObject(i);if(r==null)continue;if(ids.add(r.optString("profile_id")))rows.add(r);afterTime=r.optString("created_at");afterId=r.optString("profile_id");}more=page.length()==30;adapter.notifyDataSetChanged();if(rows.isEmpty())state("followers".equals(direction)?"Chưa có người theo dõi.":"Chưa theo dõi tài khoản nào.");}
   public void error(String e){if(!alive())return;stopLoading();rows.clear();adapter.notifyDataSetChanged();allowed=false;state(e+"\nKéo xuống hoặc chạm để thử lại.");}
  });
 }
 private void actions(JSONObject r){String id=r.optString("profile_id");if(updating.contains(id))return;boolean ownFollowers=target.equals(SessionManager.getInstance().uid())&&"followers".equals(direction);
  List<String> labels=new ArrayList<>();labels.add(r.optBoolean("requested")?"Hủy yêu cầu theo dõi":r.optBoolean("following")?"Bỏ theo dõi":"Theo dõi");if(ownFollowers)labels.add("Xóa người theo dõi");
  new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(r.optString("display_name","Người dùng")).setItems(labels.toArray(new String[0]),(d,n)->{
   boolean remove=n==1;boolean desired=!r.optBoolean("following")&&!r.optBoolean("requested");Runnable action=()->{
    if(!alive()||!updating.add(id))return;adapter.notifyDataSetChanged();
    mutations.add(Api.run(()->RpcClient.call(remove?"remove_follower":"set_follow",remove?Api.obj("p_follower",id):Api.obj("p_target",id,"p_follow",desired)),new Api.Callback<Object>(){
     public void success(Object v){if(!alive())return;updating.remove(id);if(request!=null)request.remove();loading=false;refresh();}
     public void error(String e){if(!alive())return;updating.remove(id);adapter.notifyDataSetChanged();Toast.makeText(FollowListActivity.this,e,Toast.LENGTH_LONG).show();}
    }));
   };
   if(remove||!desired)new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(remove?"Xóa người theo dõi?":"Xác nhận thay đổi theo dõi?").setNegativeButton("Hủy",null).setPositiveButton("Xác nhận",(x,y)->action.run()).show();else action.run();
  }).show();
 }
 private final class Adapter extends RecyclerView.Adapter<Holder>{
  public Holder onCreateViewHolder(android.view.ViewGroup p,int type){View row=getLayoutInflater().inflate(R.layout.item_follow_person,p,false);return new Holder(row,row.findViewById(R.id.followAvatar),row.findViewById(R.id.followName),row.findViewById(R.id.followAction));}
  public void onBindViewHolder(Holder h,int position){JSONObject r=rows.get(position);String id=r.optString("profile_id");h.name.setText(r.optString("display_name","Người dùng"));AvatarViews.load(h.avatar,Dto.asset(Dto.nullable(r,"avatar_asset_id")),r.optString("avatar_preset","neutral"));h.itemView.setOnClickListener(v->startActivity(new android.content.Intent(FollowListActivity.this,ProfileActivity.class).putExtra("target_uid",id)));h.action.setText(updating.contains(id)?"Đang cập nhật…":r.optBoolean("requested")?"Đã gửi yêu cầu":r.optBoolean("following")?"Đang theo dõi":"Theo dõi");h.action.setEnabled(!updating.contains(id));h.action.setVisibility(id.equals(SessionManager.getInstance().uid())?View.GONE:View.VISIBLE);h.action.setOnClickListener(v->actions(r));}
  @Override public void onViewRecycled(Holder holder){com.bumptech.glide.Glide.with(holder.avatar.getContext().getApplicationContext()).clear(holder.avatar);super.onViewRecycled(holder);}
  public int getItemCount(){return rows.size();}
 }
 private static final class Holder extends RecyclerView.ViewHolder{final ImageView avatar;final TextView name;final Button action;Holder(View v,ImageView a,TextView n,Button b){super(v);avatar=a;name=n;action=b;}}
 private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
 @Override protected void onResume(){super.onResume();if(adapter!=null&&!loading)refresh();}
 @Override protected void onDestroy(){if(request!=null)request.remove();for(Subscription item:mutations)item.remove();SessionManager.getInstance().identityListeners.remove(identityChanged);super.onDestroy();}
}
