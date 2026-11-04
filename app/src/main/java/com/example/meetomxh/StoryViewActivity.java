package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.example.meetomxh.databinding.ActivityStoryViewBinding;
import com.example.meetomxh.models.Story;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.RequestIds;
import com.example.meetomxh.supabase.StoryClient;
import com.example.meetomxh.ui.AvatarViews;
import java.util.ArrayList;
import java.util.List;

public class StoryViewActivity extends AppCompatActivity {
    public static final String EXTRA_AUTHOR_ID="extra_author_id",EXTRA_STORY_ID="extra_story_id";
    private ActivityStoryViewBinding b; private final Handler handler=new Handler(Looper.getMainLooper());
    private List<Story> rows=new ArrayList<>(); private int index; private float downX; private boolean paused;
    private Runnable pendingVideoLaunch;
    private final Runnable tick=()->{if(!paused)next();};
    @Override protected void onCreate(Bundle saved){super.onCreate(saved);b=ActivityStoryViewBinding.inflate(getLayoutInflater());setContentView(b.getRoot());
        b.btnCloseStoryView.setOnClickListener(v->finish()); b.btnSendStoryReply.setOnClickListener(v->sendReply());
        b.ivAuthorStoryAvatar.setOnClickListener(v->{if(index<rows.size()&&rows.get(index).isOwn())showViewers();});
        b.layoutStoryHeader.setOnLongClickListener(v->{if(index<rows.size()&&rows.get(index).isOwn()){pauseStory();confirmDelete();}return true;});
        b.ivStoryMedia.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN){downX=e.getX();paused=true;handler.removeCallbacks(tick);return true;}
            if(e.getAction()==MotionEvent.ACTION_UP){paused=false;float x=e.getX();if(Math.abs(x-downX)>40){} if(x< v.getWidth()*0.32f)previous();else if(x>v.getWidth()*0.68f)next();else schedule();return true;}return true;});
        String author=getIntent().getStringExtra(EXTRA_AUTHOR_ID); String selected=getIntent().getStringExtra(EXTRA_STORY_ID);
        Api.run(()->StoryClient.list(author),new Api.Callback<List<Story>>(){public void success(List<Story> result){rows=result;for(int i=0;i<rows.size();i++)if(rows.get(i).getId().equals(selected)){index=i;break;}show();}public void error(String e){toast(e);finish();}});
    }
    private void show(){if(index<0||index>=rows.size()){finish();return;}Story s=rows.get(index);
        b.tvStoryAuthorName.setText(s.getUserName());AvatarViews.load(b.ivAuthorStoryAvatar,s.getAvatarUrl(),s.getAvatarPreset());
        b.tvStoryTimeRemaining.setText("Tin 24 giờ · "+(index+1)+"/"+rows.size());b.tvStoryBackendNotice.setText(s.getCaption());
        b.tvStoryBackendNotice.setVisibility(s.getCaption().isEmpty()?View.GONE:View.VISIBLE);b.layoutStoryReplyBar.setVisibility(s.isOwn()?View.GONE:View.VISIBLE);
        Glide.with(this).load(s.getImageUrl()).placeholder(R.drawable.avatar_neutral).error(R.drawable.ic_media_error).into(b.ivStoryMedia);
        Api.run(()->{StoryClient.viewed(s.getId());return true;},new Api.Callback<Boolean>(){public void success(Boolean v){}public void error(String e){}});
        if(s.getMediaType().startsWith("video/")){pendingVideoLaunch=()->{pendingVideoLaunch=null;if(!paused&&!isFinishing()&&!isDestroyed()&&index<rows.size()&&rows.get(index)==s)startActivityForResult(new Intent(this,VideoPlayerActivity.class).putExtra(VideoPlayerActivity.EXTRA_ASSET,s.getImageUrl()).putExtra(VideoPlayerActivity.EXTRA_STORY_MODE,true),990);};handler.postDelayed(pendingVideoLaunch,180);}
        else schedule();
        b.ivStoryMedia.setContentDescription(s.getCaption());
    }
    private void pauseStory(){paused=true;handler.removeCallbacks(tick);if(pendingVideoLaunch!=null){handler.removeCallbacks(pendingVideoLaunch);pendingVideoLaunch=null;}}
    private void resumeStory(){if(isFinishing()||index>=rows.size())return;paused=false;Story s=rows.get(index);if(s.getMediaType().startsWith("video/")){pendingVideoLaunch=()->{pendingVideoLaunch=null;if(!paused&&!isFinishing()&&!isDestroyed()&&index<rows.size()&&rows.get(index)==s)startActivityForResult(new Intent(this,VideoPlayerActivity.class).putExtra(VideoPlayerActivity.EXTRA_ASSET,s.getImageUrl()).putExtra(VideoPlayerActivity.EXTRA_STORY_MODE,true),990);};handler.postDelayed(pendingVideoLaunch,180);}else schedule();}
    private void schedule(){handler.removeCallbacks(tick);if(!paused)handler.postDelayed(tick,5000);}
    private void next(){handler.removeCallbacks(tick);if(index+1<rows.size()){index++;show();}else finish();}
    private void previous(){handler.removeCallbacks(tick);if(index>0){index--;show();}else schedule();}
    private void sendReply(){String text=b.etStoryReply.getText().toString().trim();if(text.isEmpty()||index>=rows.size())return;Story s=rows.get(index);String key=s.getId()+"|"+text,id=RequestIds.get("story_reply_v1",key);
        b.btnSendStoryReply.setEnabled(false);Api.run(()->{StoryClient.reply(s.getId(),text,id);RequestIds.complete("story_reply_v1",key);return true;},new Api.Callback<Boolean>(){
            public void success(Boolean v){b.btnSendStoryReply.setEnabled(true);b.etStoryReply.setText("");toast("Đã gửi trả lời qua tin nhắn");}
            public void error(String e){b.btnSendStoryReply.setEnabled(true);toast(e);}});
    }
    private void showViewers(){if(index>=rows.size())return;String id=rows.get(index).getId();Api.run(()->StoryClient.viewers(id),new Api.Callback<List<org.json.JSONObject>>(){
        public void success(List<org.json.JSONObject> people){if(people.isEmpty()){toast("Chưa có lượt xem");return;}String[] names=new String[people.size()];for(int i=0;i<names.length;i++)names[i]=people.get(i).optString("display_name","Meeto");new AlertDialog.Builder(StoryViewActivity.this).setTitle("Người đã xem").setItems(names,null).setPositiveButton("Đóng",null).show();}
        public void error(String e){toast(e);}});}
    private void confirmDelete(){if(index>=rows.size())return;new AlertDialog.Builder(this).setTitle("Xóa tin?").setMessage("Tin này sẽ không còn hiển thị.").setNegativeButton("Hủy",(d,w)->resumeStory()).setOnCancelListener(d->resumeStory()).setPositiveButton("Xóa",(d,w)->{String id=rows.get(index).getId();Api.run(()->{StoryClient.delete(id);return true;},new Api.Callback<Boolean>(){public void success(Boolean v){toast("Đã xóa tin");finish();}public void error(String e){toast(e);resumeStory();}});}).show();}
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==990&&resultCode==RESULT_OK)next();}
    @Override protected void onStop(){handler.removeCallbacksAndMessages(null);paused=true;super.onStop();}
    @Override protected void onResume(){super.onResume();if(b!=null&&rows.size()>0){paused=false;if(!rows.get(index).getMediaType().startsWith("video/"))schedule();}}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
