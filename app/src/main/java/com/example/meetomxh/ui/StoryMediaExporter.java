package com.example.meetomxh.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.effect.*;
import androidx.media3.transformer.*;
import com.example.meetomxh.music.MusicComposition;
import com.example.meetomxh.music.MusicLibraryCache;
import com.example.meetomxh.music.OpenverseTrack;
import java.io.File;
import java.io.IOException;
import java.util.*;

public final class StoryMediaExporter {
 public interface Callback{void success(File output);void error(String message);}
 private Transformer transformer;private File output;private Bitmap overlay;
 private MusicLibraryCache.Lease musicLease;private long generation;
 private final ProgressHolder progress=new ProgressHolder();

 public static List<Effect> baseEffects(StoryComposition model){
  List<Effect> list=new ArrayList<>();
  if(model.rotation!=0)list.add(new ScaleAndRotateTransformation.Builder().setRotationDegrees(-model.rotation).build());
  list.add(Presentation.createForWidthAndHeight(720,1280,model.crop?Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP:Presentation.LAYOUT_SCALE_TO_FIT));
  if(model.filter!=0){float[] a=StoryComposition.colors(model.filter);float[] gl={a[0],a[5],a[10],0,a[1],a[6],a[11],0,a[2],a[7],a[12],0,a[4]/255f,a[9]/255f,a[14]/255f,1};list.add((RgbMatrix)(time,hdr)->gl);}
  return list;
 }
 public void video(Context context,File source,File destination,StoryComposition model,long start,long end,boolean mute,Callback callback){
  video(context,source,destination,model,start,end,mute,100,null,0,callback);
 }
 public void video(Context context,File source,File destination,StoryComposition model,long start,long end,boolean mute,
                   int sourceVolume,OpenverseTrack music,int musicVolume,Callback callback){
  cancel();long run=generation;output=destination;
  try{
   requireSpace(destination);lease(context,music);overlay=model.overlay();
   List<Effect> effects=baseEffects(model);effects.add(new OverlayEffect(Collections.singletonList(BitmapOverlay.createStaticBitmapOverlay(overlay))));
   Composition composition;
   if(music!=null)composition=MusicComposition.video(source,start,end,mute,sourceVolume,music,musicVolume,effects,Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL);
   else{
    EditedMediaItem item=new EditedMediaItem.Builder(new MediaItem.Builder().setUri(Uri.fromFile(source)).setClippingConfiguration(
      new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(end).build()).build())
      .setRemoveAudio(mute).setEffects(new Effects(Collections.emptyList(),effects)).build();
    EditedMediaItemSequence sequence=mute?EditedMediaItemSequence.withVideoFrom(Collections.singletonList(item)):EditedMediaItemSequence.withAudioAndVideoFrom(Collections.singletonList(item));
    composition=new Composition.Builder(sequence).setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build();
   }
   start(context,destination,composition,callback,3_000_000,run);
  }catch(Exception error){fail(run,destination,callback,message(error));}
 }
 public void photoWithMusic(Context context,File flattenedJpeg,File destination,int durationMs,
                            OpenverseTrack music,int musicVolume,Callback callback){
  cancel();long run=generation;output=destination;
  try{
   requireSpace(destination);lease(context,music);
   start(context,destination,MusicComposition.photo(flattenedJpeg,durationMs,music,musicVolume),callback,2_600_000,run);
  }catch(Exception error){fail(run,destination,callback,message(error));}
 }
 private void lease(Context context,OpenverseTrack music)throws IOException{
  if(music!=null&&music.library!=null){
   musicLease=new MusicLibraryCache(context).acquire(music.library);
   if(musicLease==null)throw new IOException("Tệp nhạc không còn hợp lệ. Hãy tải lại bài nhạc.");
  }
 }
 private void requireSpace(File destination)throws IOException{
  File parent=destination.getParentFile();
  if(parent==null||!parent.isDirectory()||parent.getUsableSpace()<64L*1024*1024)
   throw new IOException("Không đủ dung lượng để xuất Story. Hãy giải phóng ít nhất 64 MiB rồi thử lại.");
 }
 private void start(Context context,File destination,Composition composition,Callback callback,int bitrate,long run){
  transformer=new Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
   .setEncoderFactory(new DefaultEncoderFactory.Builder(context).setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(bitrate).build()).setEnableFallback(true).build())
   .addListener(new Transformer.Listener(){
    public void onCompleted(Composition c,ExportResult result){
     if(run!=generation)return;
     android.util.Log.i("MeetoMusic","stage=story_transformer_completed bytes="+destination.length());
     transformer=null;output=null;releaseResources();callback.success(destination);
    }
    public void onError(Composition c,ExportResult r,ExportException error){
     if(run!=generation)return;
     com.example.meetomxh.music.MusicDiagnostics.failure("story_transformer",error);
     fail(run,destination,callback,"Không xuất được Story (mã Media3 "+error.errorCode+"). Hãy kiểm tra dung lượng/codec rồi thử lại. Bản nguồn vẫn được giữ.");
    }
   }).build();
  try{transformer.start(composition,destination.getAbsolutePath());}
  catch(RuntimeException error){com.example.meetomxh.music.MusicDiagnostics.failure("story_transformer_start",error);fail(run,destination,callback,"Không thể bắt đầu xuất Story. Hãy thử lại.");}
 }
 private void fail(long run,File destination,Callback callback,String message){
  if(run!=generation)return;
  cancel();destination.delete();callback.error(message);
 }
 private static String message(Exception error){
  return error.getMessage()!=null&&(error instanceof IOException||error instanceof IllegalStateException)
    ?error.getMessage():"Không chuẩn bị được Story có nhạc trên thiết bị này. Hãy thử bài khác. Bản nguồn vẫn được giữ.";
 }
 public int progress(){return transformer!=null&&transformer.getProgress(progress)==Transformer.PROGRESS_STATE_AVAILABLE?progress.progress:-1;}
 private void releaseResources(){
  if(overlay!=null){overlay.recycle();overlay=null;}
  if(musicLease!=null){musicLease.close();musicLease=null;}
 }
 public void cancel(){
  generation++;Transformer old=transformer;transformer=null;
  try{if(old!=null)old.cancel();}catch(RuntimeException ignored){/* Always release files/leases even after a codec failure. */}
  finally{if(output!=null){output.delete();output=null;}releaseResources();}
 }
}
