package com.example.meetomxh;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.ui.StoryProgressView;
import org.json.*;
import java.util.*;

/** Story-like playback of authorized featured images; does not create Story views or expiry. */
public final class FeaturedAlbumViewerActivity extends AppCompatActivity {
    private static final long PHOTO_MS=5000;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<JSONObject> albums=new ArrayList<>();
    private final List<JSONArray> photos=new ArrayList<>();
    private String owner,albumId,assetId;
    private long epoch,elapsed,lastTick,downAt,recheckAt;
    private int albumIndex,imageIndex,generation;
    private boolean resumed,ready,held,paused,destroyed;
    private float downX,downY;
    private ImageView image;
    private TextView title,counter,message;
    private Button pause,retry;
    private ProgressBar loading;
    private StoryProgressView progress;
    private Subscription request;

    public static Intent intent(Context context,String owner,String album){
        return new Intent(context,FeaturedAlbumViewerActivity.class).putExtra("owner_id",owner).putExtra("album_id",album);
    }
    private boolean alive(){return !destroyed&&!isFinishing()&&epoch==SessionManager.getInstance().generation();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextColor(Color.WHITE);v.setTextSize(size);return v;}
    private Button button(String text){Button b=new Button(this);b.setText(text);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setBackgroundColor(Color.TRANSPARENT);return b;}

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);epoch=SessionManager.getInstance().generation();
        owner=getIntent().getStringExtra("owner_id");albumId=getIntent().getStringExtra("album_id");
        if(owner==null||albumId==null){finish();return;}
        if(state!=null){albumId=state.getString("album_id",albumId);assetId=state.getString("asset_id");elapsed=state.getLong("elapsed");paused=state.getBoolean("paused");}
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{androidx.core.graphics.Insets b=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());v.setPadding(b.left,b.top,b.right,b.bottom);return insets;});
        WindowCompat.getInsetsController(getWindow(),root).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(),root).setAppearanceLightNavigationBars(false);
        progress=new StoryProgressView(this,null);LinearLayout.LayoutParams bars=new LinearLayout.LayoutParams(-1,dp(4));bars.setMargins(dp(12),dp(10),dp(12),0);root.addView(progress,bars);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(14),0,dp(4),0);root.addView(header);
        title=label("Ảnh nổi bật",17);title.setTypeface(null,Typeface.BOLD);title.setMaxLines(2);header.addView(title,new LinearLayout.LayoutParams(0,dp(58),1));title.setGravity(Gravity.CENTER_VERTICAL);
        pause=button("Tạm dừng");header.addView(pause);pause.setOnClickListener(v->{paused=!paused;pause.setText(paused?"Tiếp tục":"Tạm dừng");lastTick=SystemClock.elapsedRealtime();});
        Button close=button("✕");close.setContentDescription("Đóng trình xem ảnh nổi bật");header.addView(close,new LinearLayout.LayoutParams(dp(52),dp(52)));close.setOnClickListener(v->finish());
        FrameLayout media=new FrameLayout(this);root.addView(media,new LinearLayout.LayoutParams(-1,0,1));
        image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);media.addView(image,new FrameLayout.LayoutParams(-1,-1));
        image.setOnTouchListener((v,event)->gesture(event,v.getWidth()));
        loading=new ProgressBar(this);media.addView(loading,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER));
        LinearLayout errors=new LinearLayout(this);errors.setOrientation(LinearLayout.VERTICAL);errors.setGravity(Gravity.CENTER);errors.setPadding(dp(24),dp(24),dp(24),dp(24));
        message=label("",15);message.setGravity(Gravity.CENTER);errors.addView(message);retry=button("Thử lại");errors.addView(retry);retry.setOnClickListener(v->fetch());
        FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER);media.addView(errors,ep);
        LinearLayout footer=new LinearLayout(this);footer.setGravity(Gravity.CENTER_VERTICAL);root.addView(footer);
        Button previous=button("Ảnh trước");footer.addView(previous);previous.setOnClickListener(v->move(-1));
        counter=label("",13);counter.setGravity(Gravity.CENTER);footer.addView(counter,new LinearLayout.LayoutParams(0,dp(52),1));
        Button next=button("Ảnh tiếp");footer.addView(next);next.setOnClickListener(v->move(1));
        message.setVisibility(View.GONE);retry.setVisibility(View.GONE);
    }

    private final Runnable ticker=new Runnable(){public void run(){
        if(!resumed||destroyed)return;
        if(!alive()){finish();return;}
        long now=SystemClock.elapsedRealtime();long delta=Math.max(0,now-lastTick);lastTick=now;
        if(ready&&!held&&!paused){elapsed+=delta;segments();if(elapsed>=PHOTO_MS)move(1);}
        if(now>=recheckAt&&request==null)fetch();
        main.postDelayed(this,50);
    }};
    private void fetch(){
        if(!alive())return;
        if(request!=null)request.remove();
        // Recheck current album/photo ACLs on resume and periodically, including cached images.
        recheckAt=SystemClock.elapsedRealtime()+15000;
        request=Api.run(()->(JSONArray)RpcClient.call("list_profile_featured_albums",Api.obj("p_owner_id",owner)),new Api.Callback<JSONArray>(){
            public void success(JSONArray rows){
                request=null;if(!alive())return;
                albums.clear();photos.clear();
                for(int i=0;i<rows.length();i++){
                    JSONObject album=rows.optJSONObject(i);if(album==null)continue;JSONArray all=album.optJSONArray("items"),valid=new JSONArray();
                    if(all!=null)for(int n=0;n<all.length();n++){JSONObject photo=all.optJSONObject(n);if(photo!=null&&!photo.isNull("asset_id")&&!photo.optString("asset_id").isEmpty())valid.put(photo);}
                    if(valid.length()>0){albums.add(album);photos.add(valid);}
                }
                if(albums.isEmpty()){failure("Album không còn ảnh có thể xem.");return;}
                int selected=-1;for(int i=0;i<albums.size();i++)if(albumId.equals(albums.get(i).optString("id"))){selected=i;break;}
                albumIndex=selected>=0?selected:Math.min(albumIndex,albums.size()-1);
                JSONArray items=photos.get(albumIndex);int found=-1;for(int i=0;i<items.length();i++)if(items.optJSONObject(i).optString("asset_id").equals(assetId)){found=i;break;}
                imageIndex=found>=0?found:0;
                if(selected<0||found<0)elapsed=0;
                String current=items.optJSONObject(imageIndex).optString("asset_id");
                // Keep the current timer/image when a periodic refresh returns the same item.
                if(ready&&current.equals(assetId)){
                    albumId=albums.get(albumIndex).optString("id");updateLabels();segments();
                }else showImage(false);
            }
            public void error(String e){request=null;if(alive()){albums.clear();photos.clear();failure(e);}}
        });
    }
    private void updateLabels(){
        title.setText(albums.get(albumIndex).optString("title","Ảnh nổi bật"));
        counter.setText((albumIndex+1)+"/"+albums.size()+" album · "+(imageIndex+1)+"/"+photos.get(albumIndex).length()+" ảnh");
        image.setContentDescription(title.getText()+", ảnh "+(imageIndex+1));
    }
    private void segments(){if(!photos.isEmpty()){progress.setSegments(photos.get(albumIndex).length(),imageIndex,elapsed/(float)PHOTO_MS);progress.setContentDescription("Ảnh "+(imageIndex+1)+" trên "+photos.get(albumIndex).length());}}
    private void showImage(boolean reset){
        if(!alive()||albums.isEmpty())return;
        final int token=++generation;ready=false;if(reset)elapsed=0;
        if(recheckAt==Long.MAX_VALUE)recheckAt=SystemClock.elapsedRealtime()+15000;
        albumId=albums.get(albumIndex).optString("id");assetId=photos.get(albumIndex).optJSONObject(imageIndex).optString("asset_id");
        updateLabels();segments();image.setImageDrawable(null);loading.setVisibility(View.VISIBLE);message.setVisibility(View.GONE);retry.setVisibility(View.GONE);
        Glide.with(this).load(Dto.asset(assetId)).fitCenter().listener(new RequestListener<Drawable>(){
            public boolean onLoadFailed(@Nullable GlideException e,Object model,Target<Drawable> target,boolean first){
                main.post(()->{if(token==generation&&alive())failure("Không tải được ảnh. Thử lại hoặc nhấn Ảnh tiếp để bỏ qua.");});return false;
            }
            public boolean onResourceReady(Drawable resource,Object model,Target<Drawable> target,DataSource source,boolean first){
                main.post(()->{if(token==generation&&alive()){ready=true;loading.setVisibility(View.GONE);lastTick=SystemClock.elapsedRealtime();}});return false;
            }
        }).into(image);
    }
    private void failure(String text){
        generation++;ready=false;Glide.with(this).clear(image);image.setImageDrawable(null);loading.setVisibility(View.GONE);message.setText(text);message.setVisibility(View.VISIBLE);retry.setVisibility(View.VISIBLE);
        recheckAt=Long.MAX_VALUE;
    }
    private void move(int step){
        if(!alive()||albums.isEmpty())return;
        imageIndex+=step;
        if(imageIndex>=photos.get(albumIndex).length()){albumIndex++;imageIndex=0;if(albumIndex>=albums.size()){finish();return;}}
        else if(imageIndex<0){if(albumIndex>0){albumIndex--;imageIndex=photos.get(albumIndex).length()-1;}else imageIndex=0;}
        showImage(true);
    }
    private boolean gesture(MotionEvent e,int width){
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:held=true;downAt=SystemClock.elapsedRealtime();downX=e.getX();downY=e.getY();return true;
            case MotionEvent.ACTION_UP:
                held=false;lastTick=SystemClock.elapsedRealtime();
                if(SystemClock.elapsedRealtime()-downAt<250&&Math.abs(e.getX()-downX)<dp(30)&&Math.abs(e.getY()-downY)<dp(30))move(e.getX()<width*.4f?-1:1);
                return true;
            case MotionEvent.ACTION_CANCEL:held=false;lastTick=SystemClock.elapsedRealtime();return true;
            default:return true;
        }
    }
    @Override protected void onResume(){super.onResume();if(image==null||!alive()){finish();return;}resumed=true;held=false;pause.setText(paused?"Tiếp tục":"Tạm dừng");lastTick=SystemClock.elapsedRealtime();fetch();main.removeCallbacks(ticker);main.post(ticker);}
    @Override protected void onPause(){resumed=false;main.removeCallbacks(ticker);generation++;ready=false;if(request!=null){request.remove();request=null;}if(image!=null)Glide.with(this).clear(image);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("album_id",albumId);out.putString("asset_id",assetId);out.putLong("elapsed",elapsed);out.putBoolean("paused",paused);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){destroyed=true;generation++;main.removeCallbacksAndMessages(null);if(request!=null)request.remove();super.onDestroy();}
}
