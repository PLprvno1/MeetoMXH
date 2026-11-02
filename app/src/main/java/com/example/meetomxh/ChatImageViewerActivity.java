package com.example.meetomxh;
import android.os.Bundle;
import android.net.Uri;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.example.meetomxh.databinding.ActivityChatImageViewerBinding;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.storage.SupabaseStorage;
import java.util.*;
import org.json.JSONObject;
public class ChatImageViewerActivity extends AppCompatActivity {
 public static final String EXTRA_IMAGE_URL="image_url";private ActivityChatImageViewerBinding binding;private VideoView video;private Subscription request;
 @Override protected void onCreate(Bundle state){super.onCreate(state);binding=ActivityChatImageViewerBinding.inflate(getLayoutInflater());setContentView(binding.getRoot());binding.btnImageViewerBack.setOnClickListener(v->finish());String ref=getIntent().getStringExtra(EXTRA_IMAGE_URL);if(ref==null||!ref.startsWith("asset://")){finish();return;}request=Api.run(()->{JSONObject asset=SupabaseStorage.info(ref);asset.put("stream_url",SupabaseStorage.url(asset));asset.put("jwt",SessionManager.getInstance().access(false));return asset;},new Api.Callback<JSONObject>(){public void success(JSONObject asset){if(isFinishing()||isDestroyed())return;if(asset.optString("mime_type").startsWith("video/")){video=new VideoView(ChatImageViewerActivity.this);addContentView(video,new android.view.ViewGroup.LayoutParams(-1,-1));Map<String,String> headers=new HashMap<>();headers.put("apikey",SupabaseConfig.KEY);headers.put("Authorization","Bearer "+asset.optString("jwt"));video.setVideoURI(Uri.parse(asset.optString("stream_url")),headers);MediaController controls=new MediaController(ChatImageViewerActivity.this);controls.setAnchorView(video);video.setMediaController(controls);video.setOnErrorListener((mp,what,extra)->{Toast.makeText(ChatImageViewerActivity.this,"Không phát được video hoặc quyền xem đã thay đổi",Toast.LENGTH_LONG).show();return true;});video.start();}else Glide.with(ChatImageViewerActivity.this).applyDefaultRequestOptions(new com.bumptech.glide.request.RequestOptions().diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).skipMemoryCache(true)).load(ref).fitCenter().into(binding.ivFullImage);}public void error(String e){Toast.makeText(ChatImageViewerActivity.this,e,Toast.LENGTH_LONG).show();finish();}});}
 @Override protected void onStop(){if(video!=null)video.stopPlayback();super.onStop();}
 @Override protected void onDestroy(){if(request!=null)request.remove();if(binding!=null)Glide.with(getApplicationContext()).clear(binding.ivFullImage);binding=null;super.onDestroy();}
}