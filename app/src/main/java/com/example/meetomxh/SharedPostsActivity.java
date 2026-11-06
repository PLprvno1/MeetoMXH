package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.example.meetomxh.supabase.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Lists only posts/reels actually shared in this readable conversation. */
public final class SharedPostsActivity extends AppCompatActivity {
    public static final String EXTRA_CHAT_ID="chat_id";
    private String chatId; private LinearLayout rows; private TextView status; private Button more;
    private long beforeSeq; private boolean loading,hasMore=true; private final Set<String> seen=new HashSet<>();
    @Override protected void onCreate(Bundle saved){super.onCreate(saved);chatId=getIntent().getStringExtra(EXTRA_CHAT_ID);if(chatId==null){finish();return;}build();load();}
    private void build(){LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(16),dp(10),dp(16),dp(12));root.setBackgroundColor(getColor(R.color.app_bg));Button back=new Button(this);back.setText("‹  Bài viết đã chia sẻ");back.setAllCaps(false);back.setOnClickListener(v->finish());root.addView(back);status=new TextView(this);status.setTextColor(getColor(R.color.text_secondary));root.addView(status);ScrollView scroll=new ScrollView(this);rows=new LinearLayout(this);rows.setOrientation(1);scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));more=new Button(this);more.setText("Tải tin cũ hơn");more.setVisibility(View.GONE);more.setOnClickListener(v->load());root.addView(more);setContentView(root);}
    private void load(){if(loading||!hasMore)return;loading=true;more.setEnabled(false);status.setText("Đang tải nội dung đã chia sẻ…");long cursor=beforeSeq;
        Api.run(()->{JSONObject page=RpcClient.object("get_messages",Api.obj("p_conversation_id",chatId,"p_before_seq",cursor==0?null:cursor,"p_limit",100));return page.optJSONArray("items")==null?new JSONArray():page.getJSONArray("items");},new Api.Callback<JSONArray>(){public void success(JSONArray page){if(isFinishing())return;loading=false;more.setEnabled(true);hasMore=page.length()==100;for(int i=0;i<page.length();i++){JSONObject message=page.optJSONObject(i);if(message==null)continue;beforeSeq=message.optLong("seq");JSONObject post=message.optJSONObject("shared_post");if(post==null||!message.optBoolean("shared_post_available")||!post.optBoolean("original_available",true))continue;String id=post.optString("id","");if(id.isEmpty()||!seen.add(id))continue;add(post,message.optString("created_at"));}more.setVisibility(hasMore?View.VISIBLE:View.GONE);status.setText(rows.getChildCount()==0?(hasMore?"Chưa có bài viết/Reel được chia sẻ.":"Chưa có nội dung đã chia sẻ."):"");}public void error(String e){loading=false;more.setEnabled(true);status.setText(e+" · Chạm để thử lại");status.setOnClickListener(v->load());}});
    }
    private void add(JSONObject post,String sharedAt){LinearLayout card=new LinearLayout(this);card.setGravity(android.view.Gravity.CENTER_VERTICAL);card.setPadding(0,dp(8),0,dp(8));ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);card.addView(image,new LinearLayout.LayoutParams(dp(96),dp(76)));JSONArray media=post.optJSONArray("media");if(media!=null&&media.length()>0){JSONObject first=media.optJSONObject(0);if(first!=null)Glide.with(this).load(Dto.asset(Dto.nullable(first,"asset_id"))).centerCrop().placeholder(R.drawable.ic_media_error).into(image);}LinearLayout text=new LinearLayout(this);text.setOrientation(1);text.setPadding(dp(12),0,0,0);card.addView(text,new LinearLayout.LayoutParams(0,-2,1));TextView author=new TextView(this);JSONObject profile=post.optJSONObject("author");author.setText(profile==null?"Người dùng":profile.optString("display_name","Người dùng"));author.setTextColor(getColor(R.color.text_primary));author.setTextSize(15);author.setTypeface(null,1);text.addView(author);TextView caption=new TextView(this);String body=post.optString("body","");caption.setText(body.isEmpty()?(post.optBoolean("is_reel")?"Reel đã chia sẻ":"Bài viết đã chia sẻ"):body);caption.setTextColor(getColor(R.color.text_secondary));caption.setMaxLines(2);caption.setEllipsize(android.text.TextUtils.TruncateAt.END);text.addView(caption);TextView type=new TextView(this);String time="";try{time=new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm",java.util.Locale.getDefault()).format(ServerTime.parse(sharedAt).toDate());}catch(Exception ignored){}type.setText((post.optBoolean("is_reel")?"Reel":"Bài viết")+" · Đã chia sẻ"+(time.isEmpty()?"":" · "+time));type.setTextColor(getColor(R.color.text_secondary));text.addView(type);card.setOnClickListener(v->PostDetails.show(this,post.optString("id")));rows.addView(card,new LinearLayout.LayoutParams(-1,-2));View divider=new View(this);divider.setBackgroundColor(getColor(R.color.divider_color));rows.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));}
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
}
