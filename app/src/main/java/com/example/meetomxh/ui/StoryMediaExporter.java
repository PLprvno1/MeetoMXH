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
import com.example.meetomxh.music.OpenverseTrack;
import java.io.File;
import java.util.*;

public final class StoryMediaExporter {
 public interface Callback{void success(File output);void error(String message);}
 private Transformer transformer;private File output;private Bitmap overlay;private final ProgressHolder progress=new ProgressHolder();

 public static List<Effect> baseEffects(StoryComposition model){List<Effect> list=new ArrayList<>();if(model.rotation!=0)list.add(new ScaleAndRotateTransformation.Builder().setRotationDegrees(-model.rotation).build());list.add(Presentation.createForWidthAndHeight(720,1280,model.crop?Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP:Presentation.LAYOUT_SCALE_TO_FIT));
  if(model.filter!=0){float[] a=StoryComposition.colors(model.filter);float[] gl={a[0],a[5],a[10],0,a[1],a[6],a[11],0,a[2],a[7],a[12],0,a[4]/255f,a[9]/255f,a[14]/255f,1};list.add((RgbMatrix)(time,hdr)->gl);}return list;}

 public void video(Context context,File source,File destination,StoryComposition model,long start,long end,boolean mute,Callback callback){
  video(context,source,destination,model,start,end,mute,100,null,0,callback);
 }
 public void video(Context context,File source,File destination,StoryComposition model,long start,long end,boolean mute,
        int sourceVolume,OpenverseTrack music,int musicVolume,Callback callback){
  cancel();output=destination;overlay=model.overlay();List<Effect> effects=baseEffects(model);effects.add(new OverlayEffect(Collections.singletonList(BitmapOverlay.createStaticBitmapOverlay(overlay))));
  EditedMediaItem item=new EditedMediaItem.Builder(new MediaItem.Builder().setUri(Uri.fromFile(source)).setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(end).build()).build()).setRemoveAudio(mute).setEffects(new Effects(Collections.emptyList(),effects)).build();
  Composition composition;
  try{
   if(music!=null)composition=MusicComposition.video(source,start,end,mute,sourceVolume,music,musicVolume,effects,Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL);
   else{EditedMediaItemSequence sequence=mute?EditedMediaItemSequence.withVideoFrom(Collections.singletonList(item)):EditedMediaItemSequence.withAudioAndVideoFrom(Collections.singletonList(item));composition=new Composition.Builder(sequence).setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build();}
   start(context,destination,composition,callback,3_000_000);
  }catch(Exception error){transformer=null;output=null;destination.delete();freeOverlay();callback.error("Không thể chuẩn bị bản Story có nhạc trên thiết bị này. Bản nháp vẫn được giữ.");}
 }

 public void photoWithMusic(Context context,File flattenedJpeg,File destination,int durationMs,
        OpenverseTrack music,int musicVolume,Callback callback){
  cancel();output=destination;
  try{Composition composition=MusicComposition.photo(flattenedJpeg,durationMs,music,musicVolume);
   start(context,destination,composition,callback,2_600_000);
  }catch(Exception error){transformer=null;output=null;destination.delete();callback.error("Không thể tạo Story ảnh có nhạc trên thiết bị này. Bản nháp vẫn được giữ.");}
 }

 private void start(Context context,File destination,Composition composition,Callback callback,int bitrate){
  transformer=new Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
    .setEncoderFactory(new DefaultEncoderFactory.Builder(context).setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(bitrate).build()).setEnableFallback(true).build())
    .addListener(new Transformer.Listener(){
      public void onCompleted(Composition c,ExportResult result){transformer=null;output=null;freeOverlay();callback.success(destination);}
      public void onError(Composition c,ExportResult r,ExportException e){transformer=null;output=null;freeOverlay();destination.delete();callback.error("Không xuất được Story trên thiết bị này. Hãy thử đoạn khác hoặc kiểm tra định dạng audio.");}
    }).build();
  try{transformer.start(composition,destination.getAbsolutePath());}
  catch(RuntimeException error){cancel();callback.error("Không thể bắt đầu xuất Story.");}
 }
 public int progress(){return transformer!=null&&transformer.getProgress(progress)==Transformer.PROGRESS_STATE_AVAILABLE?progress.progress:-1;}
 private void freeOverlay(){if(overlay!=null){overlay.recycle();overlay=null;}}
 public void cancel(){if(transformer!=null){transformer.cancel();transformer=null;}if(output!=null){output.delete();output=null;}freeOverlay();}
}
