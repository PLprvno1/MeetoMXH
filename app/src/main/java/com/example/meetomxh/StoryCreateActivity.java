package com.example.meetomxh;

import android.content.Intent;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.*;
import androidx.core.view.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.*;
import androidx.media3.ui.PlayerView;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.CompositionPlayer;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.FutureTarget;
import com.example.meetomxh.storage.SupabaseStorage;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.ui.*;
import com.example.meetomxh.music.MusicComposition;
import com.example.meetomxh.music.OpenverseMusicCache;
import com.example.meetomxh.music.OpenverseMusicClient;
import com.example.meetomxh.music.OpenverseMusicEditor;
import com.example.meetomxh.music.OpenverseMusicPickerActivity;
import com.example.meetomxh.music.OpenverseTrack;
import com.example.meetomxh.music.MusicPublishingClient;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Local composition -> rendered preview -> verified Storage upload -> existing Story RPC. */
public final class StoryCreateActivity extends AppCompatActivity{
 private final ExecutorService work=Executors.newSingleThreadExecutor();private final Handler main=new Handler(Looper.getMainLooper());private final StoryMediaExporter exporter=new StoryMediaExporter();
 private StoryCanvasView canvas;private ReelTrimTimelineView timeline;private PlayerView playerView;private ExoPlayer player;private CompositionPlayer compositionPlayer;private ReelPlaybackMonitor monitor;private ImageView exportedPreview;private Bitmap sourceBitmap,previewOverlay;
 private TextView status,musicInfo;private EditText caption;private Button publish,privacy;private CheckBox mute;private ProgressBar progress;
 private File pendingRender,pendingFlatten;
 private String pendingUri;private String appliedBase;private File folder,source,output;private String mime,audience="followers",asset,frozenCaption,frozenAudience;private long duration,start,end,position,outputDuration;private boolean busy,previewing,resumed,disposed,brushMode,musicCapabilityValidated,musicUploadPolicyValidated,musicUnavailable;private volatile int generation;private SupabaseStorage.Transfer transfer;private Subscription createRequest;private OpenverseTrack music;private JSONObject musicMetadata;private ActivityResultLauncher<Intent> musicPicker;private long epoch;
 private final Runnable identityChanged=()->{if(disposed||epoch==SessionManager.getInstance().generation())return;OpenverseMusicClient.clearSearchCache();music=null;musicMetadata=null;musicCapabilityValidated=false;if(transfer!=null)transfer.cancel();if(createRequest!=null)createRequest.remove();releasePlayer();finish();};
 private final ActivityResultLauncher<PickVisualMediaRequest> picker=registerForActivityResult(new ActivityResultContracts.PickVisualMedia(),uri->{if(uri!=null)stage(uri);});
 private final ActivityResultLauncher<Intent> camera=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{if(result.getResultCode()==RESULT_OK&&result.getData()!=null){String uri=result.getData().getStringExtra("story_media_uri");if(uri!=null)stage(Uri.parse(uri));}});
 private final Runnable tick=new Runnable(){public void run(){if(disposed||!resumed)return;Player active=compositionPlayer!=null?compositionPlayer:player;if(active!=null){long sourcePos=compositionPlayer!=null&&!previewing?start+active.getCurrentPosition():active.getCurrentPosition();if(video()&&!previewing&&!timeline.isInteracting()&&active.getPlayWhenReady()&&sourcePos>=end){active.pause();if(compositionPlayer!=null)active.seekTo(0);else active.seekTo(start);}if(video()&&!previewing)timeline.setPlayhead(sourcePos);}if(busy&&!previewing){int p=exporter.progress();progress.setIndeterminate(p<0);if(p>=0)progress.setProgress(p);}main.postDelayed(this,80);}};
 @Override protected void onCreate(Bundle saved){super.onCreate(saved);OpenverseMusicEditor.restoreState(this,saved);epoch=SessionManager.getInstance().generation();SessionManager.getInstance().identityListeners.add(identityChanged);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);setContentView(R.layout.activity_story_create);View root=findViewById(R.id.story_create_root);ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{androidx.core.graphics.Insets b=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout()|WindowInsetsCompat.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom);return insets;});WindowCompat.getInsetsController(getWindow(),root).setAppearanceLightStatusBars(false);
  canvas=findViewById(R.id.story_canvas);timeline=findViewById(R.id.story_trim);playerView=findViewById(R.id.story_edit_player);exportedPreview=findViewById(R.id.story_export_preview);status=findViewById(R.id.story_create_status);musicInfo=findViewById(R.id.story_music_info);caption=findViewById(R.id.story_create_caption);publish=findViewById(R.id.story_publish);privacy=findViewById(R.id.story_privacy);mute=findViewById(R.id.story_edit_mute);progress=findViewById(R.id.story_create_progress);
  musicPicker=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{if(epoch!=SessionManager.getInstance().generation()||result.getResultCode()!=RESULT_OK||result.getData()==null)return;try{OpenverseTrack selected=OpenverseTrack.fromDraft(new JSONObject(result.getData().getStringExtra(OpenverseMusicPickerActivity.EXTRA_TRACK_JSON)));if(selected==null||!OpenverseMusicCache.isManaged(this,selected))throw new IOException("Bản nhạc đã hết hạn khỏi bộ nhớ tạm. Hãy chọn lại.");editMusic(selected);}catch(Exception error){status.setText(error.getMessage()==null?"Không đọc được bài nhạc đã chọn.":error.getMessage());}});
  findViewById(R.id.story_create_back).setOnClickListener(v->back());getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){public void handleOnBackPressed(){back();}});
  findViewById(R.id.story_pick).setOnClickListener(v->{if(!busy&&asset==null)picker.launch(new PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE).build());});
  findViewById(R.id.story_camera).setOnClickListener(v->{if(!busy&&asset==null)camera.launch(new Intent(this,StoryCameraActivity.class));});
  findViewById(R.id.story_text_tool).setOnClickListener(v->textDialog(-1));findViewById(R.id.story_brush_tool).setOnClickListener(v->brushDialog());findViewById(R.id.story_filter_tool).setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Bộ lọc").setSingleChoiceItems(StoryComposition.FILTERS,canvas.model.filter,(d,n)->{canvas.checkpoint();canvas.model.filter=n;canvas.changed();d.dismiss();}).show());
  findViewById(R.id.story_music_tool).setOnClickListener(v->chooseMusic());musicInfo.setOnClickListener(v->{if(busy||asset!=null)return;if(music!=null)editMusic(music.copy());else if(musicUnavailable)showUnavailableMusicActions();});
  findViewById(R.id.story_crop_tool).setOnClickListener(v->{canvas.checkpoint();canvas.model.crop=!canvas.model.crop;canvas.changed();toast(canvas.model.crop?"Lấp đầy khung 9:16":"Hiển thị toàn bộ media");});findViewById(R.id.story_rotate_tool).setOnClickListener(v->{canvas.checkpoint();canvas.model.rotation=(canvas.model.rotation+90)%360;canvas.changed();});
  findViewById(R.id.story_undo).setOnClickListener(v->canvas.undo());findViewById(R.id.story_redo).setOnClickListener(v->canvas.redo());findViewById(R.id.story_next).setOnClickListener(v->renderPreview());publish.setOnClickListener(v->{if(previewing&&output!=null)upload();else renderPreview();});
  privacy.setOnClickListener(v->{if(!busy&&asset==null)AudienceChoices.choose(this,(value,label)->{audience=value;privacy.setText(label);});});
  mute.setOnCheckedChangeListener((b,value)->{if(music!=null){if(!busy&&!previewing)preparePlayer();}else if(player!=null)player.setVolume(value?0:1);if(!busy)invalidateOutput();});findViewById(R.id.story_edit_play).setOnClickListener(v->{Player active=compositionPlayer!=null?compositionPlayer:player;if(active!=null){if(monitor!=null&&monitor.hasFailed()){preparePlayer();return;}if(active.isPlaying())active.pause();else{if(active.getPlaybackState()==Player.STATE_ENDED)active.seekTo(previewing?0:compositionPlayer!=null?0:start);active.play();}}});
  canvas.setListener(new StoryCanvasView.Listener(){public void changed(){invalidateOutput();if(compositionPlayer!=null&&!previewing)preparePlayer();else if(player!=null&&!previewing&&!baseKey().equals(appliedBase))try{player.setVideoEffects(StoryMediaExporter.baseEffects(canvas.model));appliedBase=baseKey();}catch(RuntimeException e){status.setText("Không xem trước được hiệu ứng. Hãy thử xuất để xem kết quả.");}}public void editText(int index){textDialog(index);}});
  timeline.setListener(new ReelTrimTimelineView.Listener(){public void rangeChanged(long a,long b,long seek){start=a;end=b;invalidateOutput();scrub(seek);range();}public void scrub(long at){StoryCreateActivity.this.scrub(at);}public void interactionFinished(){if(music!=null&&!busy)preparePlayer();}});
  if(saved!=null){String path=saved.getString("source");if(path!=null&&draftFile(new File(path))&&new File(path).isFile()){
    source=new File(path);folder=source.getParentFile();mime=saved.getString("mime");start=saved.getLong("start");end=saved.getLong("end");duration=saved.getLong("duration");position=saved.getLong("position");outputDuration=saved.getLong("output_duration");audience=saved.getString("audience","followers");privacy.setText(AudienceChoices.label(audience));asset=saved.getString("asset");frozenCaption=saved.getString("frozen_caption");frozenAudience=saved.getString("frozen_audience");mute.setChecked(saved.getBoolean("mute"));musicMetadata=parseJson(saved.getString("music_metadata"));music=restoreTrack(saved.getString("music_draft"));updateMusicInfo();
    try{File state=new File(folder,"composition.json");if(state.isFile())canvas.model.restore(read(state));}catch(Exception e){toast("Không khôi phục được lớp chỉnh sửa.");}
    String result=saved.getString("output");if(result!=null&&draftFile(new File(result))&&new File(result).isFile())output=new File(result);
    if(saved.getString("pending_uri")!=null){stage(Uri.parse(saved.getString("pending_uri")));return;}loadSource(saved.getBoolean("previewing")&&output!=null);return;
  }}if(saved!=null&&saved.getString("pending_uri")!=null){stage(Uri.parse(saved.getString("pending_uri")));return;}setBusy(false);
 }
 private String baseKey(){return canvas.model.filter+":"+canvas.model.rotation+":"+canvas.model.crop;}
 private boolean video(){return mime!=null&&mime.startsWith("video/");}
 private void stage(Uri uri){if(busy||asset!=null)return;pendingUri=uri.toString();setBusy(true);releasePlayer();int token=++generation;status.setText("Đang mở media đã chọn…");
  if("content".equals(uri.getScheme()))try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}
  work.execute(()->{File dir=null;try{if(!"content".equals(uri.getScheme())&&!"file".equals(uri.getScheme()))throw new IOException("Nguồn media không hợp lệ.");dir=new File(getFilesDir(),"story-drafts/"+UUID.randomUUID());if(!dir.mkdirs())throw new IOException("Không tạo được bản nháp.");File file=new File(dir,"source.media");long budget=Math.max(0,getFilesDir().getUsableSpace()-64L*1024*1024);
   try(InputStream in="file".equals(uri.getScheme())?new FileInputStream(new File(uri.getPath())):getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(file)){if(in==null)throw new IOException("Không đọc được media.");byte[] bytes=new byte[32768];int n;long total=0;while((n=in.read(bytes))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("Đã dừng chuẩn bị.");if((total+=n)>budget)throw new IOException("Không đủ bộ nhớ để chỉnh sửa media.");out.write(bytes,0,n);}}
   BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);String type;long length=0;
   if(bounds.outWidth>0&&bounds.outHeight>0)type="image/jpeg";else{MediaMetadataRetriever r=new MediaMetadataRetriever();try{r.setDataSource(file.getAbsolutePath());length=Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));if(length<1000||!"yes".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)))throw new IOException("Video cần có hình và dài ít nhất 1 giây.");type="video/mp4";}finally{r.release();}}
   File ready=file;String selectedType=type;long selectedDuration=length;main.post(()->{if(disposed||token!=generation){deleteDraft(ready.getParentFile());return;}deleteDraft(folder);folder=ready.getParentFile();pendingUri=null;source=ready;sourceBitmap=null;canvas.setMedia(null,selectedType.startsWith("video/"));mime=selectedType;duration=selectedDuration;start=0;end=Math.min(60000,duration);output=null;outputDuration=0;musicMetadata=null;brushMode=false;canvas.reset();position=0;if("file".equals(uri.getScheme()))deleteCameraFile(new File(uri.getPath()));loadSource(false);});
  }catch(Exception error){if(dir!=null)deleteDraft(dir);main.post(()->{if(!disposed&&token==generation){setBusy(false);status.setText("Không mở được media: "+error.getMessage());}});}});
 }
 private void loadSource(boolean preview){setBusy(true);File file=source;int token=generation;work.execute(()->{try{Bitmap bitmap;if(video()){MediaMetadataRetriever r=new MediaMetadataRetriever();try{r.setDataSource(file.getAbsolutePath());if(Build.VERSION.SDK_INT>=27)bitmap=r.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,720,1280);else{Bitmap original=r.getFrameAtTime(0);if(original==null)throw new IOException("Không đọc được video.");float scale=Math.min(1,1280f/Math.max(original.getWidth(),original.getHeight()));bitmap=Bitmap.createScaledBitmap(original,Math.max(1,Math.round(original.getWidth()*scale)),Math.max(1,Math.round(original.getHeight()*scale)),true);if(bitmap!=original)original.recycle();}}finally{r.release();}}
   else{FutureTarget<Bitmap> target=Glide.with(getApplicationContext()).asBitmap().load(file).submit(1440,2560);bitmap=target.get().copy(Bitmap.Config.ARGB_8888,false);Glide.with(getApplicationContext()).clear(target);}
   if(bitmap==null)throw new IOException("Không đọc được khung hình.");Bitmap ready=bitmap;main.post(()->{if(disposed||token!=generation){if(ready!=null)ready.recycle();return;}sourceBitmap=ready;canvas.setMedia(ready,video());if(video())timeline.setSource(source,duration,start,end);setBusy(false);showMode(preview);range();});
  }catch(Exception e){main.post(()->{if(!disposed&&token==generation){setBusy(false);status.setText("Không giải mã được media. Hãy chọn media khác.");}});}});}
 private void range(){status.setText(video()?"Đoạn "+ReelTrimTimelineView.format(start)+" – "+ReelTrimTimelineView.format(end)+" · "+ReelTrimTimelineView.format(end-start)+" / 60s":"Kéo chữ để di chuyển, dùng hai ngón để phóng to hoặc xoay.");}
 private void scrub(long time){Player active=compositionPlayer!=null?compositionPlayer:player;if(active!=null){active.pause();if(compositionPlayer!=null)active.seekTo(Math.max(0,Math.min(end-start-1,time-start)));else active.seekTo(Math.max(0,Math.min(duration-1,time)));}}
 private boolean renderedVideo(){return video()||(previewing&&musicMetadata!=null);}
 private void showMode(boolean value){previewing=value;caption.setVisibility(value?View.VISIBLE:View.GONE);publish.setVisibility(value?View.VISIBLE:View.GONE);privacy.setVisibility(value?View.VISIBLE:View.GONE);findViewById(R.id.story_next).setVisibility(value?View.GONE:View.VISIBLE);findViewById(R.id.story_pick).setVisibility(value?View.GONE:View.VISIBLE);findViewById(R.id.story_camera).setVisibility(value?View.GONE:View.VISIBLE);canvas.setVisibility(value?View.GONE:View.VISIBLE);findViewById(R.id.story_tools).setVisibility(value?View.GONE:View.VISIBLE);timeline.setVisibility(video()&&!value?View.VISIBLE:View.GONE);mute.setVisibility(video()&&!value?View.VISIBLE:View.GONE);findViewById(R.id.story_edit_play).setVisibility(video()||value&&musicMetadata!=null||!value&&music!=null?View.VISIBLE:View.GONE);playerView.setVisibility(video()||value&&musicMetadata!=null?View.VISIBLE:View.GONE);exportedPreview.setVisibility(value&&!renderedVideo()?View.VISIBLE:View.GONE);publish.setText(value?"Đăng Story":"Xem trước");if(value&&!renderedVideo())Glide.with(this).load(output).fitCenter().into(exportedPreview);if(resumed)preparePlayer();setBusy(busy);}
 private void preparePlayer(){if(OpenverseMusicEditor.isShowing(this))return;if(source!=null&&resumed&&!busy){OpenverseTrack pending=OpenverseMusicEditor.pending(this,source.getAbsolutePath());if(pending!=null){editMusic(pending);return;}}releasePlayer();boolean outputMusic=previewing&&musicMetadata!=null&&!video();boolean photoMusic=!previewing&&!video()&&music!=null;playerView.setVisibility(video()||outputMusic?View.VISIBLE:View.GONE);if((!video()&&!outputMusic&&!photoMusic)||!resumed||busy||source==null)return;File file=previewing?output:source;if(file==null)return;if(!previewing&&musicUnavailable){status.setText("Bản nhạc đã hết khỏi cache. Hãy chọn lại bài hoặc mở thông tin nhạc để bỏ nhạc.");return;}
  if(photoMusic){try{
    if(!OpenverseMusicCache.isManaged(this,music))throw new IOException("Bản nhạc không còn hợp lệ trong cache. Hãy chọn lại.");
    compositionPlayer=MusicComposition.previewPlayer(this);CompositionPlayer observed=compositionPlayer;
    compositionPlayer.addListener(new Player.Listener(){
     public void onPlaybackStateChanged(int state){if(compositionPlayer==observed&&state==Player.STATE_ENDED){observed.pause();observed.seekTo(0);}}
     public void onPlayerError(PlaybackException error){if(compositionPlayer==observed){observed.pause();status.setText("Không nghe thử được Story ảnh. Hãy kiểm tra bài nhạc hoặc xuất lại.");}}
    });
    compositionPlayer.setComposition(MusicComposition.audio(music,music.stillDurationMs));
    compositionPlayer.prepare();compositionPlayer.seekTo(Math.max(0,Math.min(music.stillDurationMs-1,position)));compositionPlayer.play();return;
   }catch(Exception error){releasePlayer();status.setText(error.getMessage()==null?"Không chuẩn bị được âm thanh Story ảnh.":error.getMessage());return;}}

  if(!previewing&&video()&&music!=null){try{List<androidx.media3.common.Effect> effects=StoryMediaExporter.baseEffects(canvas.model);previewOverlay=canvas.model.overlay();effects.add(new androidx.media3.effect.OverlayEffect(Collections.singletonList(androidx.media3.effect.BitmapOverlay.createStaticBitmapOverlay(previewOverlay))));
    Composition composition=MusicComposition.video(source,start,end,mute.isChecked(),music.sourceVolume,music,music.musicVolume,effects,Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL);compositionPlayer=MusicComposition.previewPlayer(this);CompositionPlayer observed=compositionPlayer;playerView.setPlayer(compositionPlayer);
    compositionPlayer.addListener(new Player.Listener(){public void onPlaybackStateChanged(int state){if(compositionPlayer!=observed)return;progress.setVisibility(state==Player.STATE_BUFFERING?View.VISIBLE:View.GONE);if(state==Player.STATE_READY)exportedPreview.setVisibility(View.GONE);if(state==Player.STATE_ENDED){observed.pause();observed.seekTo(0);}}public void onPlayerError(PlaybackException error){if(compositionPlayer==observed){progress.setVisibility(View.GONE);status.setText("Không xem trước được bản phối Story này. Hãy thử lại hoặc xuất để kiểm tra.");}}});
    compositionPlayer.setComposition(composition);compositionPlayer.prepare();compositionPlayer.seekTo(Math.max(0,Math.min(end-start-1,position-start)));compositionPlayer.play();return;
   }catch(Exception error){releasePlayer();status.setText(error.getMessage()==null?"Không chuẩn bị được bản xem trước có nhạc.":error.getMessage());return;}}
  player=new ExoPlayer.Builder(this,new DefaultRenderersFactory(this).setEnableDecoderFallback(true)).build();ExoPlayer observed=player;monitor=new ReelPlaybackMonitor(player,new ReelPlaybackMonitor.Callback(){public void waiting(){exportedPreview.setVisibility(View.VISIBLE);if(!previewing)exportedPreview.setImageBitmap(sourceBitmap);progress.setIndeterminate(true);progress.setVisibility(View.VISIBLE);}public void firstFrame(){exportedPreview.setVisibility(View.GONE);if(!busy)progress.setVisibility(View.GONE);}public void failed(String message){progress.setVisibility(View.GONE);status.setText(message+" Nhấn Phát hoặc quay lại để thử lại.");}});monitor.begin("story_draft");monitor.attach(playerView);if(previewing)Glide.with(this).load(file).frame(0).fitCenter().into(exportedPreview);if(!previewing){player.setVideoEffects(StoryMediaExporter.baseEffects(canvas.model));appliedBase=baseKey();}player.setMediaItem(new MediaItem.Builder().setUri(Uri.fromFile(file)).setMediaId("story_draft").build());player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);player.setHandleAudioBecomingNoisy(true);player.setVolume(previewing?1f:mute.isChecked()?0f:1f);player.seekTo(previewing?0:Math.max(start,Math.min(end-1,position)));player.prepare();player.play();player.addListener(new Player.Listener(){public void onPlaybackStateChanged(int state){if(player==observed&&state==Player.STATE_ENDED)player.pause();}});
 }
 private void releasePlayer(){appliedBase=null;if(compositionPlayer!=null){position=start+compositionPlayer.getCurrentPosition();playerView.setPlayer(null);compositionPlayer.release();compositionPlayer=null;}if(previewOverlay!=null){previewOverlay.recycle();previewOverlay=null;}if(player!=null){position=player.getCurrentPosition();if(monitor!=null){monitor.release();monitor=null;}player.release();player=null;}}
 private void invalidateOutput(){if(busy||asset!=null)return;frozenCaption=null;frozenAudience=null;if(output!=null)output.delete();output=null;outputDuration=0;musicMetadata=null;musicUploadPolicyValidated=false;if(previewing)showMode(false);}
 private void renderPreview(){
  if(busy||source==null||sourceBitmap==null)return;
  if(output!=null){showMode(true);return;}
  if(musicUnavailable||music!=null&&!OpenverseMusicCache.isManaged(this,music)){
   musicUnavailable=true;status.setText("Bản nhạc không còn hợp lệ trong cache. Hãy chọn lại hoặc bỏ nhạc trước khi xuất.");return;
  }
  final boolean sourceVideo=video();
  final OpenverseTrack renderMusic=music==null?null:music.copy();
  try{if(renderMusic!=null&&(!sourceVideo||mute.isChecked()||!MusicComposition.hasAudio(source)))renderMusic.sourceVolume=0;}
  catch(Exception invalid){status.setText("Không đọc được âm thanh nguồn. Hãy thử mở lại media.");return;}
  final long expectedMs=sourceVideo?end-start:renderMusic==null?0:renderMusic.stillDurationMs;
  final long renderStart=start,renderEnd=end;
  final boolean renderMute=mute.isChecked(),asVideo=sourceVideo||renderMusic!=null;
  final File renderSource=source;
  final StoryComposition snapshot=new StoryComposition();snapshot.restore(canvas.model.json());
  final int token=++generation;
  setBusy(true);releasePlayer();brushMode=false;canvas.brush(false,-1,.008f,false);
  status.setText("Đang xuất Story · giữ ứng dụng mở để hoàn tất…");
  final File result=new File(folder,"render-"+UUID.randomUUID()+(asVideo?".mp4":".jpg"));
  final File flattened=new File(folder,"still-"+UUID.randomUUID()+".jpg");
  pendingRender=result;pendingFlatten=flattened;
  StoryMediaExporter.Callback done=new StoryMediaExporter.Callback(){
   public void success(File file){
    if(disposed||token!=generation){file.delete();flattened.delete();return;}
    work.execute(()->{
     try{
      final long actual=validate(file,renderMusic,expectedMs,asVideo);
      final JSONObject metadata=renderMusic==null?null:renderMusic.metadata(MusicComposition.usableDurationMs(renderMusic,actual));
      if(metadata!=null&&!OpenverseMusicClient.validPublishedMetadata(metadata,actual))
       throw new IOException("Metadata nhạc không khớp âm thanh đã xuất. Hãy xuất lại.");
      main.post(()->{
       if(disposed||token!=generation){file.delete();flattened.delete();return;}
       outputDuration=actual;musicMetadata=metadata;output=file;flattened.delete();pendingRender=null;pendingFlatten=null;
       setBusy(false);showMode(true);
       status.setText(String.format(Locale.ROOT,"Thành phẩm %.1f MiB · Sẵn sàng đăng",file.length()/1048576f));
      });
     }catch(Exception error){com.example.meetomxh.music.MusicDiagnostics.failure("story_output_validate_metadata",error);file.delete();flattened.delete();main.post(()->failed(error.getMessage()));}
    });
   }
   public void error(String message){flattened.delete();failed(message);}
   private void failed(String message){
    if(!disposed&&token==generation){discardRenderFiles();musicMetadata=null;setBusy(false);preparePlayer();
     status.setText(message==null?"Không xuất được Story. Bản nguồn vẫn giữ nguyên; hãy thử lại.":message);}
   }
  };
  if(sourceVideo){
   exporter.video(this,renderSource,result,snapshot,renderStart,renderEnd,renderMute,
    renderMusic==null?100:renderMusic.sourceVolume,renderMusic,renderMusic==null?0:renderMusic.musicVolume,done);
  }else{
   final Bitmap photo=sourceBitmap;
   work.execute(()->{
    Bitmap bitmap=null;
    try{
     if(disposed||token!=generation||Thread.currentThread().isInterrupted())return;
     bitmap=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888);snapshot.drawPhoto(new Canvas(bitmap),photo);
     try(OutputStream out=new FileOutputStream(flattened)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,90,out))throw new IOException("Không lưu được ảnh Story.");}
     if(disposed||token!=generation||Thread.currentThread().isInterrupted()){flattened.delete();return;}
     if(renderMusic==null){
      if(!flattened.renameTo(result))throw new IOException("Không lưu được ảnh Story.");
      main.post(()->done.success(result));
     }else main.post(()->{
      if(disposed||token!=generation){result.delete();flattened.delete();return;}
      exporter.photoWithMusic(this,flattened,result,renderMusic.stillDurationMs,renderMusic,renderMusic.musicVolume,done);
     });
    }catch(Exception error){result.delete();flattened.delete();main.post(()->done.error("Không xuất được ảnh Story. Kiểm tra dung lượng rồi thử lại."));}
    finally{if(bitmap!=null)bitmap.recycle();}
   });
  }
 }
 private long validate(File file,OpenverseTrack renderedMusic,long expectedMs,boolean asVideo)throws Exception{
  if(file.length()<=0||file.length()>50L*1024*1024)throw new IOException("Thành phẩm vượt 50 MiB hoặc không hợp lệ. Hãy cắt đoạn ngắn hơn.");
  if(!asVideo)return 0;
  long actual;String container;int containerRotation;
  MediaMetadataRetriever r=new MediaMetadataRetriever();
  try{r.setDataSource(file.getAbsolutePath());actual=Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
   container=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
   String rotationValue=renderedMusic==null?null:r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
   containerRotation=rotationValue==null?0:Integer.parseInt(rotationValue);
   android.util.Log.i("MeetoMusic","stage=story_container bytes="+file.length()+" mime="+container+" durationMs="+actual+" expectedMs="+expectedMs+" rotation="+containerRotation);
   if(actual<1000||actual>60000||Math.abs(actual-expectedMs)>300||!"yes".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)))
    throw new IOException("Video xuất không đúng thời lượng đã chọn.");
  }finally{r.release();}
  if(renderedMusic!=null){
   android.media.MediaExtractor extractor=new android.media.MediaExtractor();
   long videoUs=0,audioUs=0;int width=0,height=0,rotation=containerRotation,videoTracks=0,audioTracks=0;String videoMime="",audioMime="";
   try{
    extractor.setDataSource(file.getAbsolutePath());
    for(int i=0;i<extractor.getTrackCount();i++){
     android.media.MediaFormat f=extractor.getTrackFormat(i);String type=f.getString(android.media.MediaFormat.KEY_MIME);
     if(type!=null&&type.startsWith("video/")){videoTracks++;videoMime=type;width=f.getInteger(android.media.MediaFormat.KEY_WIDTH);height=f.getInteger(android.media.MediaFormat.KEY_HEIGHT);
      if(f.containsKey(android.media.MediaFormat.KEY_ROTATION))rotation=f.getInteger(android.media.MediaFormat.KEY_ROTATION);
      if(f.containsKey(android.media.MediaFormat.KEY_DURATION))videoUs=f.getLong(android.media.MediaFormat.KEY_DURATION);}
     if(type!=null&&type.startsWith("audio/")){audioTracks++;audioMime=type;if(f.containsKey(android.media.MediaFormat.KEY_DURATION))audioUs=f.getLong(android.media.MediaFormat.KEY_DURATION);}
    }
   }finally{extractor.release();}
   android.util.Log.i("MeetoMusic","stage=story_tracks videoCount="+videoTracks+" audioCount="+audioTracks+" videoMime="+videoMime+" audioMime="+audioMime+" encoded="+width+"x"+height+" rotation="+rotation+" containerRotation="+containerRotation+" videoUs="+videoUs+" audioUs="+audioUs);
   if(!"video/mp4".equals(container))throw new IOException("Thành phẩm Story có nhạc không phải container MP4. Hãy xuất lại.");
   if(videoTracks!=1||audioTracks!=1)throw new IOException("Story xuất có "+videoTracks+" luồng hình và "+audioTracks+" luồng tiếng; cần đúng một H.264 và một AAC.");
   com.example.meetomxh.music.StoryMusicExportChecks.validate(file.length(),actual,videoUs,audioUs,width,height,rotation,videoMime,audioMime,expectedMs);
  }
  return actual;
 }
 private void discardRenderFiles(){if(pendingRender!=null){pendingRender.delete();pendingRender=null;}if(pendingFlatten!=null){pendingFlatten.delete();pendingFlatten=null;}}

 private boolean outputIsVideo(){return video()||musicMetadata!=null;}
 private void chooseMusic(){if(busy||source==null||asset!=null)return;releasePlayer();musicPicker.launch(new Intent(StoryCreateActivity.this,OpenverseMusicPickerActivity.class));}
 private void editMusic(OpenverseTrack selected){
  releasePlayer();boolean sourceAudio=false;if(video())try{sourceAudio=MusicComposition.hasAudio(source);}catch(Exception ignored){}
  OpenverseTrack editorTrack=selected.copy();if(!video()||!sourceAudio||mute.isChecked())editorTrack.sourceVolume=0;
  OpenverseMusicEditor.PreviewFactory mixedPreview=video()?draft->{
   Composition composition=MusicComposition.video(source,start,end,draft.sourceVolume==0,draft.sourceVolume,draft,draft.musicVolume,
    Collections.emptyList(),Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL);
   CompositionPlayer mixed=MusicComposition.previewPlayer(this);
   try{mixed.setComposition(composition);mixed.prepare();return mixed;}catch(RuntimeException error){mixed.release();throw error;}
  }:null;
  OpenverseMusicEditor.show(this,editorTrack,video()?end-start:editorTrack.stillDurationMs,sourceAudio,!video(),source.getAbsolutePath(),mixedPreview,
   new OpenverseMusicEditor.Callback(){
    public void saved(OpenverseTrack value){music=value;if(video())mute.setChecked(value.sourceVolume==0);musicUnavailable=!OpenverseMusicCache.isManaged(StoryCreateActivity.this,value);musicMetadata=null;invalidateOutput();updateMusicInfo();preparePlayer();}
    public void removed(){music=null;musicUnavailable=false;musicMetadata=null;invalidateOutput();updateMusicInfo();preparePlayer();}
    public void change(){chooseMusic();}
    public void closed(){preparePlayer();}
   });
 }
 private void updateMusicInfo(){if(musicInfo==null)return;if(music==null&&!musicUnavailable){musicInfo.setVisibility(View.GONE);return;}String title=music==null?(musicMetadata==null?"Bài nhạc":musicMetadata.optString("title","Bài nhạc")):music.title;String creator=music==null?(musicMetadata==null?"":musicMetadata.optString("artist",musicMetadata.optString("creator",""))):music.creator;musicInfo.setText("♫ "+title+(creator.isEmpty()?"":" · "+creator)+" · "+(musicUnavailable?"Nguồn tạm đã hết hạn · chạm để chọn lại hoặc bỏ nhạc":"CC0 · Chạm để chỉnh sửa"));musicInfo.setVisibility(View.VISIBLE);}
 private void showUnavailableMusicActions(){new AlertDialog.Builder(this).setTitle("Không còn tệp nhạc tạm").setMessage("Bản xuất đã lưu vẫn giữ nguyên. Khi cần xuất lại, hãy chọn bài khác hoặc bỏ nhạc.").setNegativeButton("Để sau",null).setNeutralButton("Bỏ nhạc",(d,w)->{musicUnavailable=false;musicMetadata=null;invalidateOutput();updateMusicInfo();preparePlayer();}).setPositiveButton("Chọn bài khác",(d,w)->chooseMusic()).show();}
 private OpenverseTrack restoreTrack(String value){try{OpenverseTrack restored=OpenverseTrack.fromDraft(new JSONObject(value));musicUnavailable=restored==null&&(musicMetadata!=null||value!=null)||restored!=null&&!OpenverseMusicCache.isManaged(this,restored);return restored;}catch(Exception ignored){musicUnavailable=musicMetadata!=null||value!=null;return null;}}
 private JSONObject parseJson(String value){try{return value==null?null:new JSONObject(value);}catch(Exception ignored){return null;}}
 private void upload(){
  if(busy||output==null||!output.isFile())return;
  if(musicUnavailable&&musicMetadata==null){status.setText("Bản nhạc không còn trong cache và thiếu metadata đã xác minh. Hãy chọn lại hoặc bỏ nhạc trước khi đăng.");return;}
  if(musicMetadata!=null&&!com.example.meetomxh.music.OpenverseMusicClient.validPublishedMetadata(musicMetadata,outputDuration)){status.setText("Metadata nhạc không khớp thời lượng hoặc đoạn nguồn. Hãy xuất lại Story.");return;}
  if(output.length()>50L*1024*1024){status.setText("Story vượt giới hạn upload.");return;}
  if(musicMetadata!=null&&!musicCapabilityValidated){setBusy(true);status.setText("Đang xác minh máy chủ lưu thông tin nguồn nhạc…");Api.run(()->{MusicPublishingClient.requireEnabled();return true;},new Api.Callback<Boolean>(){
   public void success(Boolean enabled){if(disposed||epoch!=SessionManager.getInstance().generation())return;musicCapabilityValidated=true;setBusy(false);upload();}
   public void error(String e){if(!disposed&&epoch==SessionManager.getInstance().generation()){setBusy(false);status.setText(e+" Có thể bỏ nhạc để đăng theo luồng hiện tại.");}}
  });return;}
  if(musicMetadata!=null&&!musicUploadPolicyValidated){setBusy(true);status.setText("Đang kiểm tra dung lượng video Story trên Cloud…");Api.run(()->RpcClient.object("get_reel_upload_policy",Api.obj()),new Api.Callback<JSONObject>(){
   public void success(JSONObject policy){if(disposed||epoch!=SessionManager.getInstance().generation())return;long limit=VideoPublishLimits.effective(policy.optLong("max_bytes",0));
    if(!policy.optBoolean("automatic_video_distribution")||limit<=0){setBusy(false);status.setText("Cloud chưa xác nhận giới hạn video Task 47. Chưa tải Story có nhạc lên.");return;}
    if(output.length()>limit){setBusy(false);status.setText("Story vượt giới hạn Storage hiện tại. Hãy xuất đoạn ngắn hơn.");return;}
    musicUploadPolicyValidated=true;setBusy(false);upload();
   }
   public void error(String e){if(!disposed&&epoch==SessionManager.getInstance().generation()){setBusy(false);status.setText("Chưa kiểm tra được giới hạn Storage. Chưa tải Story có nhạc lên. "+e);}}
  });return;}
  if(frozenCaption==null){frozenCaption=caption.getText().toString().trim();frozenAudience=audience;}
  setBusy(true);progress.setIndeterminate(false);if(asset!=null){create();return;}
  SupabaseStorage.UploadListener listener=new SupabaseStorage.UploadListener(){
   public void onProgress(int p){if(!disposed){progress.setProgress(p);status.setText("Đang tải Story "+p+"%");}}
   public void onSuccess(String ref){if(!disposed){asset=ref;create();}}
   public void onFailure(String e){if(!disposed){setBusy(false);status.setText("Upload chưa hoàn tất. Bản chỉnh sửa vẫn được giữ. "+e);}}
  };
  transfer=outputIsVideo()?SupabaseStorage.uploadVideo(getContentResolver(),output,"stories",SessionManager.getInstance().uid(),listener):SupabaseStorage.uploadImage(getContentResolver(),output,"stories",SessionManager.getInstance().uid(),listener);
 }
 private void create(){status.setText("Đang xác nhận đăng Story…");String key=asset+"|"+frozenCaption+"|"+frozenAudience+(musicMetadata==null?"":"|"+musicMetadata),id=RequestIds.get("create_story_v1",key);createRequest=Api.run(()->{if(musicMetadata==null)return StoryClient.create(asset,frozenCaption,frozenAudience,id);if(!com.example.meetomxh.music.OpenverseMusicClient.validPublishedMetadata(musicMetadata,outputDuration))throw new IOException("Metadata nhạc không khớp thời lượng video Story.");return RpcClient.object("create_story_with_music",Api.obj("p_media_asset_id",Dto.assetId(asset),"p_caption",frozenCaption,"p_audience",frozenAudience,"p_client_request_id",id,"p_duration_ms",(int)outputDuration,"p_music_metadata",musicMetadata));},new Api.Callback<JSONObject>(){public void success(JSONObject result){if(disposed)return;RequestIds.complete("create_story_v1",key);StoryRings.invalidate();setResult(RESULT_OK);toast("Đã đăng tin");busy=false;finish();}public void error(String e){if(!disposed){setBusy(false);status.setText("Chưa xác nhận đăng thành công. Nhấn Đăng Story để thử lại. "+e);}}});}
 private void setBusy(boolean value){busy=value;boolean edit=!value&&asset==null;canvas.setEnabled(edit&&!previewing);timeline.setEnabled(edit);for(int id:new int[]{R.id.story_pick,R.id.story_camera,R.id.story_text_tool,R.id.story_brush_tool,R.id.story_filter_tool,R.id.story_crop_tool,R.id.story_rotate_tool,R.id.story_undo,R.id.story_redo,R.id.story_next,R.id.story_music_tool})findViewById(id).setEnabled(edit&&(sourceBitmap!=null||id==R.id.story_pick||id==R.id.story_camera));musicInfo.setEnabled(!value&&(music!=null||musicUnavailable)&&asset==null);privacy.setEnabled(!value&&frozenCaption==null);caption.setEnabled(!value&&frozenCaption==null);mute.setEnabled(edit);publish.setEnabled(!value&&source!=null&&sourceBitmap!=null);progress.setVisibility(value?View.VISIBLE:View.GONE);}
 private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(32,16,32,16);return l;}
 private void palette(LinearLayout parent,int[] color){LinearLayout row=new LinearLayout(this);for(int c:new int[]{Color.WHITE,Color.BLACK,0xFFFF4275,0xFFFFD740,0xFF69F0AE,0xFF40C4FF,0xFFB388FF}){TextView dot=new TextView(this);dot.setText("●");dot.setTextSize(26);dot.setGravity(Gravity.CENTER);dot.setTextColor(c);dot.setOnClickListener(v->{color[0]=c;toast("Đã chọn màu");});row.addView(dot,new LinearLayout.LayoutParams(0,64,1));}parent.addView(row);}
 private void textDialog(int index){if(busy||previewing||source==null)return;if(index<0&&canvas.model.texts.size()>=30){toast("Tối đa 30 lớp chữ");return;}brushMode=false;canvas.brush(false,-1,.008f,false);StoryComposition.TextLayer old=index<0?new StoryComposition.TextLayer():canvas.model.texts.get(index);LinearLayout root=column();EditText input=new EditText(this);input.setText(old.content);input.setHint("Nhập chữ hoặc emoji…");input.setMinLines(2);input.setMaxLines(5);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1000)});root.addView(input);int[] color={old.color};palette(root,color);
  Spinner font=new Spinner(this);String[] fonts={"sans-serif","serif","monospace","cursive"};font.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,fonts));font.setSelection(Math.max(0,Arrays.asList(fonts).indexOf(old.font)));root.addView(font);SeekBar size=new SeekBar(this);size.setMax(85);size.setProgress(Math.max(0,(int)(old.size*720)-15));size.setContentDescription("Cỡ chữ");root.addView(size);
  Spinner align=new Spinner(this);align.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Căn trái","Căn giữa","Căn phải"}));align.setSelection(old.alignment);root.addView(align);CheckBox bg=new CheckBox(this);bg.setText("Nền chữ tối");bg.setChecked(old.background!=0);root.addView(bg);CheckBox shadow=new CheckBox(this);shadow.setText("Đổ bóng chữ");shadow.setChecked(old.shadow);root.addView(shadow);
  AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(index<0?"Thêm chữ":"Sửa lớp chữ").setView(root).setNegativeButton("Hủy",null).setPositiveButton("Xong",(d,w)->{if(input.getText().toString().trim().isEmpty())return;canvas.checkpoint();StoryComposition.TextLayer text=index<0?new StoryComposition.TextLayer():canvas.model.texts.get(index);text.content=input.getText().toString();text.color=color[0];text.font=fonts[font.getSelectedItemPosition()];text.size=(size.getProgress()+15)/720f;text.alignment=align.getSelectedItemPosition();text.background=bg.isChecked()?0xAA000000:0;text.shadow=shadow.isChecked();if(index<0){text.z=canvas.model.texts.size();canvas.model.texts.add(text);}canvas.changed();});if(index>=0)dialog.setNeutralButton("Xóa lớp",(d,w)->canvas.removeText(index));dialog.show();
 }
 private void brushDialog(){if(busy||previewing||source==null)return;if(brushMode){brushMode=false;canvas.brush(false,-1,.008f,false);toast("Đã thoát chế độ vẽ");return;}LinearLayout root=column();int[] color={-1};palette(root,color);SeekBar width=new SeekBar(this);width.setMax(39);width.setProgress(5);width.setContentDescription("Độ dày cọ");root.addView(width);CheckBox eraser=new CheckBox(this);eraser.setText("Tẩy nét vẽ");root.addView(eraser);new AlertDialog.Builder(this).setTitle("Cọ vẽ · nhấn Vẽ lần nữa để thoát").setView(root).setNegativeButton("Hủy",null).setNeutralButton("Xóa nét vẽ",(d,w)->canvas.clearDrawing()).setPositiveButton("Bắt đầu",(d,w)->{brushMode=true;canvas.brush(true,color[0],(width.getProgress()+1)/720f,eraser.isChecked());}).show();}
 private void back(){if(busy){if(!previewing&&source!=null)new AlertDialog.Builder(this).setMessage("Dừng xuất? Video nguồn và chỉnh sửa vẫn được giữ.").setNegativeButton("Tiếp tục",null).setPositiveButton("Dừng",(d,w)->{generation++;pendingUri=null;exporter.cancel();discardRenderFiles();setBusy(false);preparePlayer();}).show();else toast(previewing?"Đang đăng Story, vui lòng đợi kết quả.":"Đang mở media đã chọn, vui lòng đợi.");return;}if(previewing&&asset==null){frozenCaption=null;frozenAudience=null;showMode(false);return;}if(source!=null)new AlertDialog.Builder(this).setMessage("Thoát và xóa bản nháp này?").setNegativeButton("Ở lại",null).setPositiveButton("Thoát",(d,w)->finish()).show();else finish();}
 private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
 private void deleteCameraFile(File file){try{if(file.getCanonicalPath().startsWith(getCacheDir().getCanonicalPath()+File.separator+"story-camera-"))file.delete();}catch(IOException ignored){}}
 private boolean draftFile(File f){try{return f.getCanonicalPath().startsWith(new File(getFilesDir(),"story-drafts").getCanonicalPath()+File.separator);}catch(IOException e){return false;}}
 private void deleteDraft(File dir){if(dir==null||!draftFile(dir))return;File[] files=dir.listFiles();if(files!=null)for(File f:files)if(f.isFile()&&draftFile(f))f.delete();dir.delete();}
 private String read(File file)throws IOException{try(Reader r=new InputStreamReader(new FileInputStream(file),java.nio.charset.StandardCharsets.UTF_8)){StringBuilder s=new StringBuilder();char[] b=new char[4096];int n;while((n=r.read(b))!=-1)s.append(b,0,n);return s.toString();}}
 @Override protected void onResume(){super.onResume();resumed=true;if(source!=null&&!busy)preparePlayer();main.removeCallbacks(tick);main.post(tick);}
 @Override protected void onStop(){resumed=false;releasePlayer();main.removeCallbacks(tick);super.onStop();}
 @Override protected void onSaveInstanceState(Bundle b){OpenverseMusicEditor.saveState(this,b);b.putString("pending_uri",pendingUri);if(source!=null){Player active=compositionPlayer!=null?compositionPlayer:player;long savedPosition=active==null?position:compositionPlayer!=null&&!previewing?start+active.getCurrentPosition():active.getCurrentPosition();b.putString("source",source.getAbsolutePath());b.putString("mime",mime);b.putString("output",output==null?null:output.getAbsolutePath());b.putLong("duration",duration);b.putLong("start",start);b.putLong("end",end);b.putLong("position",savedPosition);b.putLong("output_duration",outputDuration);b.putString("music_metadata",musicMetadata==null?null:musicMetadata.toString());try{b.putString("music_draft",music==null?null:music.draftJson().toString());}catch(Exception ignored){b.putString("music_draft",null);}b.putString("audience",audience);b.putBoolean("mute",mute.isChecked());b.putBoolean("previewing",previewing);b.putString("asset",asset);b.putString("frozen_caption",frozenCaption);b.putString("frozen_audience",frozenAudience);try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(folder,"composition.json")),java.nio.charset.StandardCharsets.UTF_8)){writer.write(canvas.model.json());}catch(IOException e){}}super.onSaveInstanceState(b);}
 @Override protected void onDestroy(){disposed=true;generation++;SessionManager.getInstance().identityListeners.remove(identityChanged);main.removeCallbacksAndMessages(null);exporter.cancel();discardRenderFiles();if(transfer!=null)transfer.cancel();if(createRequest!=null)createRequest.remove();releasePlayer();canvas.release();timeline.release();work.shutdownNow();if(isFinishing())deleteDraft(folder);super.onDestroy();}
}
