package com.example.meetomxh;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.ui.AvatarViews;
import org.json.*;
import java.util.*;

public class FollowListActivity extends AppCompatActivity {
    private String target,direction,afterTime,afterId; private boolean loading,more=true;
    private final List<Row> rows=new ArrayList<>(); private Adapter adapter; private TextView status;
    @Override protected void onCreate(Bundle state){super.onCreate(state);target=getIntent().getStringExtra("target_uid");direction=getIntent().getStringExtra("direction");
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xfff4f6f8);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(16,12,16,12);
        TextView back=new TextView(this);back.setText("‹");back.setTextSize(32);back.setOnClickListener(v->finish());header.addView(back);
        TextView title=new TextView(this);title.setText("followers".equals(direction)?"Người theo dõi":"Đang theo dõi");title.setTextSize(20);title.setPadding(16,0,0,0);header.addView(title);root.addView(header);
        status=new TextView(this);status.setPadding(16,8,16,8);root.addView(status);
        RecyclerView list=new RecyclerView(this);list.setLayoutManager(new LinearLayoutManager(this));adapter=new Adapter();list.setAdapter(adapter);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        TextView retry=new TextView(this);retry.setText("Thử lại");retry.setGravity(Gravity.CENTER);retry.setPadding(16,14,16,14);retry.setOnClickListener(v->{afterTime=null;afterId=null;rows.clear();adapter.notifyDataSetChanged();load();});root.addView(retry);
        list.addOnScrollListener(new RecyclerView.OnScrollListener(){@Override public void onScrolled(RecyclerView r,int dx,int dy){if(dy>0&&!r.canScrollVertically(1)&&more&&!loading)load();}});
        setContentView(root);load();
    }
    private void load(){if(loading||!more)return;loading=true;status.setText("Đang tải…");final String t=afterTime,i=afterId;
        Api.run(()->(JSONArray)RpcClient.call("list_follows",Api.obj("p_target",target,"p_direction",direction,"p_after_time",t,"p_after_id",i,"p_limit",30)),new Api.Callback<JSONArray>(){
            public void success(JSONArray data){loading=false;for(int n=0;n<data.length();n++){JSONObject o=data.optJSONObject(n);if(o==null)continue;Row row=new Row(o.optString("profile_id"),o.optString("display_name","Meeto user"),Dto.asset(Dto.nullable(o,"avatar_asset_id")),o.optString("avatar_preset","neutral"),o.optString("created_at"),o.optBoolean("following"));rows.add(row);if(n==data.length()-1){afterTime=row.time;afterId=row.id;}}more=data.length()==30;adapter.notifyDataSetChanged();status.setText(rows.isEmpty()?"Chưa có tài khoản nào trong danh sách":"");}
            public void error(String e){loading=false;status.setText(e);}
        });
    }
    private final class Adapter extends RecyclerView.Adapter<Holder>{public Holder onCreateViewHolder(android.view.ViewGroup p,int t){LinearLayout row=new LinearLayout(p.getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(12,8,12,8);ImageView avatar=new ImageView(p.getContext());avatar.setId(View.generateViewId());row.addView(avatar,new LinearLayout.LayoutParams(48,48));TextView name=new TextView(p.getContext());name.setId(View.generateViewId());name.setTextSize(16);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,-2,1);np.leftMargin=12;row.addView(name,np);Button follow=new Button(p.getContext());follow.setId(View.generateViewId());row.addView(follow);return new Holder(row,avatar,name,follow);}
        public void onBindViewHolder(Holder h,int position){Row r=rows.get(position);h.name.setText(r.name);AvatarViews.load(h.avatar,r.avatar,r.preset);h.itemView.setOnClickListener(v->startActivity(new android.content.Intent(FollowListActivity.this,ProfileActivity.class).putExtra("target_uid",r.id)));h.follow.setText(r.following?"Đang theo dõi":"Theo dõi");h.follow.setOnClickListener(v->{h.follow.setEnabled(false);boolean value=!r.following;Api.run(()->RpcClient.object("set_follow",Api.obj("p_target",r.id,"p_follow",value)),new Api.Callback<JSONObject>(){public void success(JSONObject o){r.following=o.optBoolean("following");h.follow.setText(r.following?"Đang theo dõi":"Theo dõi");h.follow.setEnabled(true);}public void error(String e){h.follow.setEnabled(true);Toast.makeText(FollowListActivity.this,e,Toast.LENGTH_SHORT).show();}});});}
        public int getItemCount(){return rows.size();}}
    private static final class Holder extends RecyclerView.ViewHolder{final ImageView avatar;final TextView name;final Button follow;Holder(View v,ImageView a,TextView n,Button f){super(v);avatar=a;name=n;follow=f;}}
    private static final class Row{final String id,name,avatar,preset,time;boolean following;Row(String i,String n,String a,String p,String t,boolean f){id=i;name=n;avatar=a;preset=p;time=t;following=f;}}
}
