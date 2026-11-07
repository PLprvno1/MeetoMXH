package com.example.meetomxh;

import android.content.Intent;
import android.os.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.appcompat.app.*;
import androidx.core.view.*;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.request.target.Target;
import com.example.meetomxh.models.Story;
import com.example.meetomxh.fragments.StoryViewersSheet;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.ui.*;
import org.json.JSONObject;
import java.util.*;

public final class StoryViewActivity extends AppCompatActivity {
 public static final String EXTRA_AUTHOR_ID="extra_author_id",EXTRA_STORY_ID="extra_story_id";
 private final Handler main=new Handler(Looper.getMainLooper());
 private final List<List<Story>> groups=new ArrayList<>();private final Set<String> recorded=new HashSet<>(),liking=new HashSet<>();
 private int group,index,generation;private Story current;private ExoPlayer player;private ReelPlaybackMonitor monitor;private Subscription request,validation;
 private PlayerView video;private ImageView image,avatar;private TextView author,caption,error;private EditText reply;private ImageButton like,send,pause;
 private View replyBar;private Button viewers;private ProgressBar loading;private StoryProgressView progress;
 private boolean resumed,held,manualPause,keyboard,sheet,ready,sending,destroyed;private long photoElapsed,lastTick,recheckAt,resumeVideo;private float downX,downY;private long downAt;
 private String selected,selectedAuthor;private boolean discoveryLoading;
 private final Set<String> expandedAuthors=new HashSet<>();
 private RequestManager images;private boolean retryError,invalidNavigation,closeOnError;
 private final Runnable ticker=new Runnable(){public void run(){
  if(destroyed||!resumed)return;long now=SystemClock.elapsedRealtime();long elapsed=now-lastTick;lastTick=now;
  if(current!=null){
   if(!current.isActive()&&error.getVisibility()!=View.VISIBLE){unavailable("Tin đã hết hạn. Chạm để bỏ qua.");}
   else if(ready&&!isPaused()){
    if(isVideo()){if(player!=null&&player.getDuration()>0)segments(player.getCurrentPosition()/(float)player.getDuration());}
    else{photoElapsed+=Math.max(0,elapsed);segments(photoElapsed/5000f);if(photoElapsed>=5000)move(1);}
   }
   if(now>=recheckAt&&!sheet){recheckAt=now+15000;validateCurrent();}
  }
  main.postDelayed(this,50);
 }};
 @Override protected void onCreate(Bundle state){super.onCreate(state);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);setContentView(R.layout.activity_story_view);
  View root=findViewById(R.id.story_view_root);ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{androidx.core.graphics.Insets b=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout()|WindowInsetsCompat.Type.ime());keyboard=insets.isVisible(WindowInsetsCompat.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom);syncPause();return insets;});WindowCompat.getInsetsController(getWindow(),root).setAppearanceLightStatusBars(false);
  video=findViewById(R.id.story_player);image=findViewById(R.id.story_image);avatar=findViewById(R.id.story_avatar);author=findViewById(R.id.story_author);caption=findViewById(R.id.story_caption);error=findViewById(R.id.story_error);reply=findViewById(R.id.story_reply);like=findViewById(R.id.story_like);send=findViewById(R.id.story_send);pause=findViewById(R.id.story_pause);replyBar=findViewById(R.id.story_reply_bar);viewers=findViewById(R.id.story_viewers);loading=findViewById(R.id.story_loading);progress=findViewById(R.id.story_segments);
  images=Glide.with(this);
  replyBar.setVisibility(View.GONE);viewers.setVisibility(View.GONE);
  findViewById(R.id.story_close).setOnClickListener(v->finish());findViewById(R.id.story_menu).setOnClickListener(v->menu());pause.setOnClickListener(v->{manualPause=!manualPause;syncPause();});like.setOnClickListener(v->toggleLike());send.setOnClickListener(v->sendReply());viewers.setOnClickListener(v->showViewers());error.setOnClickListener(v->{if(closeOnError||invalidNavigation)finish();else if(retryError){if(current!=null&&!groups.isEmpty())show(true);else loadDiscovery();}else move(1);});
  reply.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEND){sendReply();return true;}return false;});
  findViewById(R.id.story_gestures).setOnTouchListener((v,event)->gesture(event,v.getWidth()));
  getSupportFragmentManager().setFragmentResultListener("story_viewers_closed",this,(key,bundle)->{sheet=false;syncPause();validateCurrent();});
  getSupportFragmentManager().setFragmentResultListener(com.example.meetomxh.fragments.StoryPrivacySheet.RESULT,this,(key,bundle)->{
   String id=bundle.getString("story_id"),audience=bundle.getString("audience");
   if(audience!=null){for(List<Story> items:groups)for(Story story:items)if(Objects.equals(id,story.getId()))story.setAudience(audience);
    if(current!=null&&Objects.equals(id,current.getId()))current.setAudience(audience);
    StoryRings.invalidate();toast("Đã cập nhật quyền riêng tư Story");
   }
   sheet=false;syncPause();validateCurrent();
  });
  selected=state==null?getIntent().getStringExtra(EXTRA_STORY_ID):state.getString("story_id");
  photoElapsed=state==null?0:state.getLong("photo_elapsed");resumeVideo=state==null?0:state.getLong("video_position");
  selectedAuthor=state==null?getIntent().getStringExtra(EXTRA_AUTHOR_ID):state.getString("author_id",getIntent().getStringExtra(EXTRA_AUTHOR_ID));
  invalidNavigation=(selected!=null&&!StoryClient.validId(selected))||(selectedAuthor!=null&&!StoryClient.validId(selectedAuthor))||(selected==null&&selectedAuthor==null);
  if(invalidNavigation){unavailable("Liên kết Story không hợp lệ. Chạm để đóng rồi chọn lại tin.");return;}
  if(selected!=null)selected=selected.toLowerCase(Locale.ROOT);if(selectedAuthor!=null)selectedAuthor=selectedAuthor.toLowerCase(Locale.ROOT);
  loadDiscovery();
 }
 private void loadDiscovery(){
  if(discoveryLoading||destroyed||invalidNavigation)return;discoveryLoading=true;error.setVisibility(View.GONE);loading.setVisibility(View.VISIBLE);
  request=Api.run(()->{
   List<Story> all=StoryClient.list(null);String targetAuthor=selectedAuthor;
   if(selected!=null){String actual=StoryClient.get(selected).getAuthorId();if(targetAuthor!=null&&!targetAuthor.equals(actual))throw new IllegalStateException("Story không thuộc người đăng vừa chọn. Hãy mở lại tin từ khay Story.");targetAuthor=actual;}
   if(targetAuthor!=null){List<Story> own=StoryClient.list(targetAuthor);int insertion=all.size();for(int n=0;n<all.size();n++)if(targetAuthor.equals(all.get(n).getAuthorId())){insertion=n;break;}String owner=targetAuthor;all.removeIf(s->owner.equals(s.getAuthorId()));all.addAll(Math.min(insertion,all.size()),own);}
   return all;
  },new Api.Callback<List<Story>>(){
   public void success(List<Story> rows){discoveryLoading=false;if(destroyed)return;groups.clear();for(Story s:rows)if(Objects.equals(s.getId(),selected))selectedAuthor=s.getAuthorId();if(selectedAuthor!=null)expandedAuthors.add(selectedAuthor);LinkedHashMap<String,List<Story>> grouped=new LinkedHashMap<>();for(Story s:rows)if(s.isActive())grouped.computeIfAbsent(s.getAuthorId(),k->new ArrayList<>()).add(s);groups.addAll(grouped.values());
    boolean found=false;for(int g=0;g<groups.size()&&!found;g++)for(int n=0;n<groups.get(g).size();n++){Story s=groups.get(g).get(n);if(Objects.equals(s.getId(),selected)||(selected==null&&Objects.equals(s.getAuthorId(),selectedAuthor))){group=g;index=n;found=true;break;}}
    if(!found&&(selected!=null||selectedAuthor!=null)){unavailable("Người này chưa có tin còn hiệu lực hoặc bạn không còn quyền xem. Chạm để đóng; dùng dấu + trong khay để tạo tin của bạn.");closeOnError=true;return;}show(true);
   }
   public void error(String message){discoveryLoading=false;if(!destroyed)loadFailure(message);}
  });
 }
 private boolean isVideo(){return current!=null&&current.getMediaType().startsWith("video/");}
 private boolean isPaused(){return !resumed||held||manualPause||keyboard||sheet||sending;}
 private void syncPause(){if(player!=null){if(isPaused())player.pause();else if(monitor!=null&&!monitor.hasFailed())player.play();}if(pause!=null)pause.setImageResource(isPaused()?R.drawable.ic_reel_play:R.drawable.ic_reel_pause);if(current!=null&&ready&&!isPaused())record(current);}
 private void segments(float value){if(group>=0&&group<groups.size())progress.setSegments(groups.get(group).size(),index,value);}
 private void show(boolean preserve){
  if(groups.isEmpty()||group<0||group>=groups.size()){ready=false;stopMedia();finish();return;}if(index<0||index>=groups.get(group).size()){ready=false;stopMedia();finish();return;}
  Story candidate=groups.get(group).get(index);
  if(!expandedAuthors.contains(candidate.getAuthorId())){
   int token=++generation;if(request!=null)request.remove();stopMedia();ready=false;current=candidate;loading.setVisibility(View.VISIBLE);viewers.setVisibility(View.GONE);replyBar.setVisibility(View.GONE);error.setVisibility(View.GONE);image.setImageResource(R.drawable.ic_reel_video);image.setVisibility(View.VISIBLE);String keep=preserve||index>0?candidate.getId():null;String owner=candidate.getAuthorId();
   request=Api.run(()->StoryClient.list(owner),new Api.Callback<List<Story>>(){public void success(List<Story> rows){if(destroyed||token!=generation)return;expandedAuthors.add(owner);if(rows.isEmpty()){unavailable("Không còn tin để xem. Chạm để bỏ qua.");return;}groups.set(group,rows);index=0;if(keep!=null)for(int n=0;n<rows.size();n++)if(keep.equals(rows.get(n).getId()))index=n;show(preserve);}public void error(String message){if(token==generation&&!destroyed)loadFailure(message);}});return;
  }
  int token=++generation;if(request!=null)request.remove();if(validation!=null)validation.remove();stopMedia();ready=false;held=false;if(!preserve)manualPause=false;if(!preserve){photoElapsed=0;resumeVideo=0;}recheckAt=SystemClock.elapsedRealtime()+15000;
  current=groups.get(group).get(index);viewers.setVisibility(View.GONE);replyBar.setVisibility(View.GONE);Story requested=current;error.setVisibility(View.GONE);loading.setVisibility(View.VISIBLE);image.setVisibility(View.VISIBLE);image.setImageResource(R.drawable.ic_reel_video);if(!preserve)reply.setText("");segments(0);
  request=Api.run(()->StoryClient.get(requested.getId()),new Api.Callback<Story>(){
   public void success(Story s){if(destroyed||token!=generation)return;current=s;groups.get(group).set(index,s);if(!s.isActive()){unavailable("Tin đã hết hạn. Chạm để bỏ qua.");return;}
    author.setText(s.getUserName()+"\n"+StoryTime.relative(s.getCreatedAt()));AvatarViews.load(avatar,s.getAvatarUrl(),s.getAvatarPreset());caption.setText(s.getCaption());caption.setVisibility(s.getCaption().isEmpty()?View.GONE:View.VISIBLE);replyBar.setVisibility(s.isOwn()?View.GONE:View.VISIBLE);viewers.setVisibility(s.isOwn()?View.VISIBLE:View.GONE);viewers.setText(s.getViewsCount()+" người xem");reply.setHint("Trả lời "+s.getUserName()+"…");updateLike();
    if(isVideo())prepareVideo(s,token);else loadImage(s,token);
   }
   public void error(String message){if(!destroyed&&token==generation)loadFailure(message);}
  });
 }
 private void loadImage(Story s,int token){
  request=Api.run(()->StoryClient.media(s),new Api.Callback<StoryClient.Media>(){
   public void success(StoryClient.Media media){if(destroyed||token!=generation||!resumed)return;
    LazyHeaders.Builder headers=new LazyHeaders.Builder();for(Map.Entry<String,String> entry:media.headers.entrySet())headers.addHeader(entry.getKey(),entry.getValue());
    images.load(new GlideUrl(media.url,headers.build())).diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true).fitCenter().listener(new RequestListener<android.graphics.drawable.Drawable>(){
     public boolean onLoadFailed(GlideException e,Object model,Target<android.graphics.drawable.Drawable> target,boolean first){
      if(token==generation&&!destroyed){String message="Không tải được ảnh Story. Kiểm tra kết nối và thử lại.";
       if(e!=null)for(Throwable cause:e.getRootCauses())if(cause instanceof com.bumptech.glide.load.HttpException){int status=((com.bumptech.glide.load.HttpException)cause).getStatusCode();ErrorMapper.HttpError failure=new ErrorMapper.HttpError(status,"story_image");ErrorMapper.diagnostic("story.image",failure);message=ErrorMapper.message(failure);break;}
       String failureMessage=message;main.post(()->{if(token==generation&&!destroyed)loadFailure(failureMessage);});
      }return true;
     }
     public boolean onResourceReady(android.graphics.drawable.Drawable drawable,Object model,Target<android.graphics.drawable.Drawable> target,DataSource source,boolean first){if(token!=generation||destroyed||!resumed)return true;ready=true;loading.setVisibility(View.GONE);record(s);lastTick=SystemClock.elapsedRealtime();return false;}
    }).into(image);
   }
   public void error(String message){if(!destroyed&&token==generation)loadFailure(message);}
  });
 }
 private void prepareVideo(Story s,int token){
  request=Api.run(()->StoryClient.media(s),new Api.Callback<StoryClient.Media>(){
   public void success(StoryClient.Media media){if(destroyed||token!=generation||!resumed)return;try{
    DefaultDataSource.Factory data=new DefaultDataSource.Factory(StoryViewActivity.this,new DefaultHttpDataSource.Factory().setDefaultRequestProperties(media.headers));
    player=new ExoPlayer.Builder(StoryViewActivity.this,new DefaultRenderersFactory(StoryViewActivity.this).setEnableDecoderFallback(true)).setMediaSourceFactory(new DefaultMediaSourceFactory(data)).build();
    monitor=new ReelPlaybackMonitor(player,new ReelPlaybackMonitor.Callback(){public void waiting(){image.setVisibility(View.VISIBLE);}public void firstFrame(){if(token!=generation)return;image.setVisibility(View.GONE);loading.setVisibility(View.GONE);ready=true;record(s);syncPause();}public void failed(String message){if(token==generation)unavailable(message+" Chạm để bỏ qua.");}});
    monitor.begin(s.getId());monitor.attach(video);player.addListener(new Player.Listener(){public void onPlaybackStateChanged(int state){if(token!=generation)return;if(state==Player.STATE_BUFFERING)loading.setVisibility(View.VISIBLE);if(state==Player.STATE_READY&&monitor.hasFrame())loading.setVisibility(View.GONE);if(state==Player.STATE_ENDED&&ready)move(1);}});
    player.setMediaItem(new MediaItem.Builder().setUri(media.url).setMediaId(s.getId()).build());player.seekTo(resumeVideo);player.prepare();
    // Allow rendering to start, but view tracking/progress wait for the actual first frame.
    player.setPlayWhenReady(resumed&&!held&&!manualPause&&!keyboard&&!sheet&&!sending);
    // The schema has no remote video cover. Keep the placeholder until first frame rather
    // than downloading the same private MP4 a second time just to extract a poster.
   }catch(Exception failure){unavailable("Không mở được video. Chạm để bỏ qua.");}}
   public void error(String message){if(token==generation&&!destroyed)loadFailure(message);}
  });
 }
 private void record(Story s){if(s.isOwn()||!resumed||sheet||keyboard||!recorded.add(s.getId()))return;Api.run(()->{StoryClient.viewed(s.getId());return true;},new Api.Callback<Boolean>(){public void success(Boolean value){}public void error(String e){recorded.remove(s.getId());}});}
 private void validateCurrent(){if(current==null||!ready)return;String id=current.getId();int token=generation;validation=Api.run(()->StoryClient.get(id),new Api.Callback<Story>(){public void success(Story s){if(destroyed||token!=generation)return;if(!s.isActive()){unavailable("Tin đã hết hạn.");return;}current.setAudience(s.getAudience());for(List<Story> items:groups)for(Story story:items)if(id.equals(story.getId()))story.setAudience(s.getAudience());if(s.isOwn())viewers.setText(s.getViewsCount()+" người xem");}public void error(String message){if(!destroyed&&token==generation)loadFailure(message);}});}
 private void unavailable(String message){generation++;ready=false;retryError=false;closeOnError=false;if(request!=null)request.remove();if(validation!=null)validation.remove();stopMedia();image.setImageResource(R.drawable.ic_media_error);image.setVisibility(View.VISIBLE);error.setText(message);error.setVisibility(View.VISIBLE);loading.setVisibility(View.GONE);}
 private void loadFailure(String message){unavailable(message+"\nChạm để thử lại; chạm trái/phải vùng trống để chuyển tin.");retryError=true;}
 private void stopMedia(){if(images!=null)images.clear(image);if(monitor!=null){monitor.release();monitor=null;}if(player!=null){player.release();player=null;}}
 private void move(int step){if(groups.isEmpty()){loadDiscovery();return;}if(group<0||group>=groups.size()){finish();return;}if(sending||sheet)return;index+=step;if(index>=groups.get(group).size()){group++;index=0;}else if(index<0){group--;if(group>=0)index=groups.get(group).size()-1;else{group=0;index=0;}}show(false);}
 private void moveAuthor(int step){if(groups.isEmpty()||sending||sheet)return;group=Math.max(0,group+step);index=0;show(false);}
 private boolean gesture(MotionEvent e,int width){if(sending||sheet)return true;
  if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();downAt=SystemClock.elapsedRealtime();held=true;syncPause();return true;}
  if(e.getActionMasked()==MotionEvent.ACTION_UP){held=false;float dx=e.getX()-downX,dy=e.getY()-downY;
   if(Math.abs(dx)>48*getResources().getDisplayMetrics().density&&Math.abs(dx)>Math.abs(dy))moveAuthor(dx<0?1:-1);
   else if(dy< -60*getResources().getDisplayMetrics().density&&current!=null&&current.isOwn())showViewers();
   else if(SystemClock.elapsedRealtime()-downAt<250)move(e.getX()<width*.4f?-1:1);else syncPause();return true;}
  if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){held=false;syncPause();}return true;
 }
 private void updateLike(){if(current==null)return;like.setImageResource(current.isLiked()?R.drawable.ic_like_filled:R.drawable.ic_like_outline);like.setColorFilter(current.isLiked()?0xFFFF4275:0xFFFFFFFF);like.setEnabled(!liking.contains(current.getId()));}
 private void toggleLike(){if(current==null||!ready||!liking.add(current.getId()))return;Story s=current;boolean previous=s.isLiked();s.setLiked(!previous);updateLike();like.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).withEndAction(()->like.animate().scaleX(1).scaleY(1).setDuration(100).start()).start();Api.run(()->StoryClient.like(s.getId(),!previous),new Api.Callback<JSONObject>(){public void success(JSONObject r){liking.remove(s.getId());s.setLiked(r.optBoolean("liked",!previous));if(!destroyed&&current!=null&&Objects.equals(current.getId(),s.getId())){current.setLiked(s.isLiked());updateLike();}}public void error(String e){liking.remove(s.getId());s.setLiked(previous);if(!destroyed&&current!=null&&Objects.equals(current.getId(),s.getId())){updateLike();toast(e);}}});}
 private void sendReply(){if(current==null||!ready||sending||current.isOwn())return;String text=reply.getText().toString().trim();if(text.isEmpty())return;Story s=current;String key=s.getId()+"|"+text,id=RequestIds.get("story_reply_v1",key);sending=true;send.setEnabled(false);syncPause();Api.run(()->StoryClient.reply(s.getId(),text,id),new Api.Callback<JSONObject>(){
  public void success(JSONObject result){if(destroyed)return;sending=false;send.setEnabled(true);RequestIds.complete("story_reply_v1",key);String state=result.optString("request_state");if("rejected".equals(state)){toast("Yêu cầu tin nhắn đã bị từ chối.");syncPause();return;}reply.setText("");reply.clearFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(reply.getWindowToken(),0);toast("pending".equals(state)?"Đã gửi vào tin nhắn chờ":"Đã gửi trả lời qua Messenger");syncPause();}
  public void error(String message){if(destroyed)return;sending=false;send.setEnabled(true);toast(message);syncPause();}
 });}
 private void showViewers(){if(current==null||!current.isOwn()||sheet)return;sheet=true;syncPause();StoryViewersSheet dialog=StoryViewersSheet.create(current.getId());getSupportFragmentManager().setFragmentResultListener("story_viewers_closed",this,(k,b)->{sheet=false;syncPause();validateCurrent();});dialog.show(getSupportFragmentManager(),"story_viewers");}
 private void menu(){
  if(current==null||sheet)return;
  if(!current.isOwn()){deleteOrReportMenu();return;}
  sheet=true;syncPause();boolean[] next={false};
  new AlertDialog.Builder(this).setTitle("Tùy chọn Story")
   .setItems(new String[]{"Chỉnh sửa quyền riêng tư","Xóa tin"},(dialog,which)->{
    next[0]=true;sheet=false;
    if(which==0)showPrivacy();else deleteOrReportMenu();
   }).setNegativeButton("Đóng",null).setOnDismissListener(dialog->{if(!next[0]){sheet=false;syncPause();}}).show();
 }
 private void showPrivacy(){
  if(current==null||!current.isOwn()||getSupportFragmentManager().isStateSaved())return;
  if(getSupportFragmentManager().findFragmentByTag(com.example.meetomxh.fragments.StoryPrivacySheet.TAG)!=null)return;
  sheet=true;syncPause();com.example.meetomxh.fragments.StoryPrivacySheet.create(current.getId()).show(getSupportFragmentManager(),com.example.meetomxh.fragments.StoryPrivacySheet.TAG);
 }
 private void deleteOrReportMenu(){
  if(current==null||sheet)return;sheet=true;syncPause();Story s=current;boolean[] submitting={false};
  AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(s.isOwn()?"Xóa tin này?":"Tùy chọn Story").setNegativeButton("Đóng",null);
  if(s.isOwn())dialog.setMessage("Tin này sẽ không còn hiển thị.").setPositiveButton("Xóa tin",(d,w)->{
   submitting[0]=true;sheet=true;syncPause();Api.run(()->{StoryClient.delete(s.getId());return true;},new Api.Callback<Boolean>(){
    public void success(Boolean value){StoryRings.invalidate();if(!destroyed)finish();}
    public void error(String message){if(!destroyed){sheet=false;toast(message);syncPause();}}
   });
  });else dialog.setPositiveButton("Báo cáo tin",(d,w)->startActivity(new Intent(this,ReportContentActivity.class).putExtra(ReportContentActivity.EXTRA_TARGET_TYPE,"story").putExtra(ReportContentActivity.EXTRA_TARGET_ID,s.getId())));
  dialog.setOnDismissListener(d->{if(!submitting[0]){sheet=false;syncPause();}}).show();
 }
 private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
 @Override protected void onResume(){super.onResume();resumed=true;lastTick=SystemClock.elapsedRealtime();sheet=getSupportFragmentManager().findFragmentByTag("story_viewers")!=null||getSupportFragmentManager().findFragmentByTag(com.example.meetomxh.fragments.StoryPrivacySheet.TAG)!=null;if(current!=null){expandedAuthors.remove(current.getAuthorId());show(true);}else if(!discoveryLoading)loadDiscovery();main.removeCallbacks(ticker);main.post(ticker);}
 @Override protected void onStop(){resumed=false;if(player!=null)resumeVideo=player.getPlaybackState()==Player.STATE_ENDED?0:player.getCurrentPosition();generation++;discoveryLoading=false;if(request!=null)request.remove();if(validation!=null)validation.remove();stopMedia();main.removeCallbacks(ticker);super.onStop();}
 @Override protected void onSaveInstanceState(Bundle out){out.putString("story_id",current==null?selected:current.getId());out.putString("author_id",current==null?selectedAuthor:current.getAuthorId());out.putLong("photo_elapsed",photoElapsed);out.putLong("video_position",player==null?resumeVideo:player.getCurrentPosition());super.onSaveInstanceState(out);}
 @Override protected void onDestroy(){destroyed=true;generation++;if(request!=null)request.remove();if(validation!=null)validation.remove();main.removeCallbacksAndMessages(null);stopMedia();super.onDestroy();}
}
