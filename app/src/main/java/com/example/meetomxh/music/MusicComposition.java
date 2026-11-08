package com.example.meetomxh.music;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaMetadataRetriever;
import android.media.MediaFormat;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.audio.GainProcessor;
import androidx.media3.transformer.*;
import androidx.media3.effect.Presentation;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One bounded audio timeline for preview and export. Legacy v1 never loops. */
public final class MusicComposition {
    private MusicComposition(){}

    public static CompositionPlayer previewPlayer(Context context){
        return new CompositionPlayer.Builder(context).setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true).build();
    }
    public static Composition video(File source,long startMs,long endMs,boolean mute,int sourceVolume,
            OpenverseTrack music,int musicVolume,List<androidx.media3.common.Effect> videoEffects,int hdrMode)throws Exception{
        if(source==null||music==null||startMs<0||endMs<=startMs)throw new IllegalArgumentException("Invalid music composition");
        long length=endMs-startMs;boolean sourceHasAudio=hasAudio(source);
        android.util.Log.i("MeetoMusic","stage=video_composition sourceExists="+source.isFile()+" hasSourceAudio="+sourceHasAudio
                +" startMs="+startMs+" endMs="+endMs+" mute="+mute+" sourceVolume="+sourceVolume+" musicVolume="+musicVolume);
        List<AudioProcessor> sourceEffects=!mute&&sourceHasAudio&&sourceVolume<100
                ?Collections.singletonList(gain(source,sourceVolume)):Collections.emptyList();
        EditedMediaItem visual=new EditedMediaItem.Builder(clipped(source,startMs*1000,endMs*1000))
                .setDurationUs(mediaDurationUs(source)).setRemoveAudio(mute).setFrameRate(30)
                .setEffects(new Effects(sourceEffects,videoEffects)).build();
        List<EditedMediaItemSequence> sequences=new ArrayList<>();
        sequences.add(!mute&&sourceHasAudio?EditedMediaItemSequence.withAudioAndVideoFrom(Collections.singletonList(visual))
                :EditedMediaItemSequence.withVideoFrom(Collections.singletonList(visual)));
        sequences.add(audioSequence(music,length,musicVolume));
        return new Composition.Builder(sequences).setHdrMode(hdrMode).build();
    }
    public static Composition photo(File flattenedImage,int durationMs,OpenverseTrack music,int musicVolume)throws Exception{
        if(flattenedImage==null||!flattenedImage.isFile()||durationMs<5000||durationMs>60000||music==null)
            throw new IllegalArgumentException("Invalid still Story composition");
        MediaItem still=new MediaItem.Builder().setUri(Uri.fromFile(flattenedImage)).setImageDurationMs(durationMs).build();
        EditedMediaItem visual=new EditedMediaItem.Builder(still).setFrameRate(30)
                .setEffects(new Effects(Collections.emptyList(),Collections.singletonList(
                        Presentation.createForWidthAndHeight(720,1280,Presentation.LAYOUT_SCALE_TO_FIT)))).build();
        List<EditedMediaItemSequence> sequences=new ArrayList<>();
        sequences.add(EditedMediaItemSequence.withVideoFrom(Collections.singletonList(visual)));
        sequences.add(audioSequence(music,durationMs,musicVolume));
        return new Composition.Builder(sequences).build();
    }
    public static Composition audio(OpenverseTrack music,long durationMs)throws Exception{
        return new Composition.Builder(audioSequence(music,durationMs,music.musicVolume)).build();
    }
    private static EditedMediaItemSequence audioSequence(OpenverseTrack music,long outputMs,int volume)throws Exception{
        File file=new File(music.localPath);
        if(!file.isFile())throw new IllegalStateException("Tệp nhạc đã hết khỏi cache. Hãy chọn lại bài nhạc.");
        long sourceUs=audioDurationUs(file);
        if(music.library==null?sourceUs/1000!=music.durationMs:Math.abs(sourceUs-music.durationMs*1000)>1000)
            throw new IllegalStateException("Thời lượng tệp nhạc đã thay đổi. Hãy chọn lại bài nhạc.");
        long offset=effectiveStartMs(music,outputMs);music.startMs=(int)offset;
        long used=compositionDurationMs(music,outputMs);music.selectedDurationMs=used;
        List<MusicLoopTimeline.Segment> plan=music.library!=null
                ?MusicLoopTimeline.plan(sourceUs,offset*1000,used*1000)
                :Collections.singletonList(new MusicLoopTimeline.Segment(offset*1000,Math.min(sourceUs,(offset+used)*1000),0));
        android.util.Log.i("MeetoMusic","stage=music_timeline library="+(music.library!=null)+" fileBytes="+file.length()
                +" sourceUs="+sourceUs+" offsetMs="+offset+" usedMs="+used+" segments="+plan.size());
        List<EditedMediaItem> items=new ArrayList<>();
        for(int i=0;i<plan.size();i++){
            MusicLoopTimeline.Segment segment=plan.get(i);
            List<AudioProcessor> processors=new ArrayList<>();processors.add(gain(file,volume));
            if(plan.size()>1){
                final boolean fadeIn=i>0,fadeOut=i<plan.size()-1;final long durationUs=segment.durationUs();
                processors.add(new GainProcessor(new GainProcessor.GainProvider(){
                    public float getGainFactorAtSamplePosition(long frame,int sampleRate){
                        return MusicLoopTimeline.joinGain(frame,sampleRate,durationUs,fadeIn,fadeOut);
                    }
                    public long isUnityUntil(long frame,int sampleRate){
                        if(getGainFactorAtSamplePosition(frame,sampleRate)!=1f)return C.TIME_UNSET;
                        if(!fadeOut)return C.TIME_END_OF_SOURCE;
                        long frames=Math.max(1,Math.round(durationUs*sampleRate/1_000_000d));
                        long ramp=Math.min(Math.max(1,Math.round(sampleRate*.002)),Math.max(1,frames/(fadeIn?2:1)));
                        return Math.max(frame+1,frames-ramp+1);
                    }
                }));
            }
            // durationUs describes the full source BEFORE clipping (Media3 1.11.1 contract).
            items.add(new EditedMediaItem.Builder(clipped(file,segment.sourceStartUs,segment.sourceEndUs))
                    .setDurationUs(sourceUs).setRemoveVideo(true)
                    .setEffects(new Effects(processors,Collections.emptyList())).build());
        }
        return EditedMediaItemSequence.withAudioFrom(items);
    }
    public static int effectiveStartMs(OpenverseTrack track,long outputDurationMs){
        if(track==null||track.durationMs<=0||outputDurationMs<1000||outputDurationMs>60000)
            throw new IllegalArgumentException("Invalid music output duration");
        if(track.library!=null){
            if(track.startMs<0||track.startMs>=track.durationMs)throw new IllegalArgumentException("Vị trí bắt đầu nhạc không hợp lệ.");
            return track.startMs;
        }
        long maximumStart=Math.max(0,track.durationMs-outputDurationMs);
        long start=Math.min(maximumStart,Math.max(0,track.startMs));
        if(track.durationMs-start<outputDurationMs-300)
            throw new IllegalStateException("Đoạn nhạc ngắn hơn video quá 300 ms. Hãy chọn bài dài hơn hoặc cắt video ngắn hơn.");
        return (int)start;
    }
    public static long usableDurationMs(OpenverseTrack track,long outputDurationMs){
        if(track==null||track.durationMs<=0||track.startMs<0||track.startMs>=track.durationMs
                ||outputDurationMs<1000||outputDurationMs>60000)throw new IllegalArgumentException("Invalid music segment");
        if(track.library!=null){
            long used=track.selectedDurationMs>0?track.selectedDurationMs:outputDurationMs;
            if(used<1000||used>60000||Math.abs(used-outputDurationMs)>300)
                throw new IllegalStateException("Âm thanh không khớp thời lượng bản xuất. Hãy xuất lại.");
            return used;
        }
        long selected=track.selectedDurationMs>0?Math.min(track.selectedDurationMs,track.durationMs-track.startMs):track.durationMs-track.startMs;
        long used=Math.min(outputDurationMs,selected);
        if(used<1000||outputDurationMs-used>300)
            throw new IllegalStateException("Đoạn nhạc ngắn hơn video quá 300 ms. Hãy chọn bài dài hơn hoặc cắt video ngắn hơn.");
        return used;
    }
    private static long compositionDurationMs(OpenverseTrack track,long outputDurationMs){
        if(track.library!=null)return outputDurationMs;
        long used=Math.min(outputDurationMs,track.durationMs-track.startMs);
        if(used<1000||outputDurationMs-used>300)throw new IllegalStateException("Đoạn nhạc ngắn hơn video quá 300 ms. Hãy chọn bài dài hơn hoặc cắt video ngắn hơn.");
        return used;
    }
    public static boolean hasAudio(File file)throws Exception{
        MediaExtractor e=new MediaExtractor();try{e.setDataSource(file.getAbsolutePath());for(int i=0;i<e.getTrackCount();i++){
            String mime=e.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/"))return true;}return false;
        }finally{e.release();}
    }
    public static long audioDurationMs(File file)throws Exception{return audioDurationUs(file)/1000;}
    private static long audioDurationUs(File file)throws Exception{
        MediaExtractor e=new MediaExtractor();try{
            e.setDataSource(file.getAbsolutePath());long duration=0;boolean found=false;
            for(int i=0;i<e.getTrackCount();i++){MediaFormat f=e.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("audio/")){found=true;if(f.containsKey(MediaFormat.KEY_DURATION))duration=Math.max(duration,f.getLong(MediaFormat.KEY_DURATION));}}
            if(!found)return 0;
            if(duration<=0)throw new IllegalStateException("Nguồn nhạc không có thời lượng audio hợp lệ.");return duration;
        }finally{e.release();}
    }
    private static long mediaDurationUs(File file)throws Exception{
        MediaExtractor e=new MediaExtractor();long duration=0;
        try{e.setDataSource(file.getAbsolutePath());for(int i=0;i<e.getTrackCount();i++){MediaFormat f=e.getTrackFormat(i);if(f.containsKey(MediaFormat.KEY_DURATION))duration=Math.max(duration,f.getLong(MediaFormat.KEY_DURATION));}}
        finally{e.release();}
        if(duration<=0){MediaMetadataRetriever r=new MediaMetadataRetriever();try{r.setDataSource(file.getAbsolutePath());String value=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);if(value!=null)duration=Long.parseLong(value)*1000;}finally{r.release();}}
        if(duration<=0)throw new IllegalStateException("Không xác định được thời lượng media nguồn.");return duration;
    }
    private static MediaItem clipped(File file,long startUs,long endUs){return new MediaItem.Builder().setUri(Uri.fromFile(file))
            .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionUs(startUs).setEndPositionUs(endUs).build()).build();}
    private static AudioProcessor gain(File file,int percent)throws Exception{
        MediaExtractor e=new MediaExtractor();try{e.setDataSource(file.getAbsolutePath());int channels=2;
            for(int i=0;i<e.getTrackCount();i++){MediaFormat f=e.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("audio/")){if(f.containsKey(MediaFormat.KEY_CHANNEL_COUNT))channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);break;}}
            if(channels<1||channels>8)throw new IllegalStateException("Số kênh âm thanh này chưa được hỗ trợ.");
            float scale=Math.max(0,Math.min(100,percent))/100f;float[] matrix=new float[channels*channels];
            for(int i=0;i<channels;i++)matrix[i*channels+i]=scale;
            ChannelMixingAudioProcessor processor=new ChannelMixingAudioProcessor();
            processor.putChannelMixingMatrix(new ChannelMixingMatrix(channels,channels,matrix));return processor;
        }finally{e.release();}
    }
}
